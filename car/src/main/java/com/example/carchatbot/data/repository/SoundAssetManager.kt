package com.example.carchatbot.data.repository

import android.content.Context
import android.net.Uri
import com.example.carchatbot.boot.BootSoundCacheRepository
import com.example.carchatbot.R
import com.example.carchatbot.data.local.UserPreferencesRepository
import com.example.carchatbot.data.model.GoodbyeServerAvailability
import com.example.carchatbot.data.model.SoundSourceType
import com.example.carchatbot.data.remote.model.DownloadRequest
import com.example.carchatbot.runtime.AppRuntimePolicies
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SoundAssetManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val iotStatusRepository: IotStatusRepository,
    private val fileRepository: FileRepository,
    private val bootSoundCacheRepository: BootSoundCacheRepository,
    private val appLogger: com.example.carchatbot.utils.AppLogger
) {
    sealed interface ManagedSoundRefreshResult {
        data class Ready(val uri: Uri) : ManagedSoundRefreshResult
        data object MissingOnServer : ManagedSoundRefreshResult
        data object Unavailable : ManagedSoundRefreshResult
    }

    private data class ManagedRefreshAttempt(
        val result: ManagedSoundRefreshResult,
        val retryable: Boolean
    )

    private val helloRefreshMutex = Mutex()
    private val goodbyeRefreshMutex = Mutex()

    fun managedSoundDirectory(): File = File(context.filesDir, SOUND_STORAGE_DIRECTORY).apply { mkdirs() }

    data class RequiredSoundWarmupResult(
        val helloUri: Uri?,
        val goodbyeUri: Uri?
    ) {
        fun allCustomSoundsReady(): Boolean = helloUri != null

        fun missingSoundIndexes(): List<Int> = buildList {
            if (helloUri == null) add(1)
        }
    }

    suspend fun ensureSoundAvailable(soundIndex: Int): Uri? {
        invalidateStoredSoundsForCurrentUserIfNeeded()
        return if (soundIndex == GOODBYE_SOUND_INDEX) {
            resolveGoodbyePlaybackSound()
        } else {
            resolveStoredSound(soundIndex)
        }
    }

    private suspend fun resolveStoredSound(soundIndex: Int): Uri? {
        val currentStoredUri = getStoredUri(soundIndex)
        val currentGeneration = userPreferencesRepository.soundGeneration.first()
        val acceptedGeneration = getAcceptedGeneration(soundIndex)
        val cleanupInProgress = userPreferencesRepository.cleanupInProgress.first()
        if (!currentStoredUri.isNullOrBlank()) {
            if (cleanupInProgress || (acceptedGeneration != null && acceptedGeneration != currentGeneration)) {
                clearStoredSoundState(soundIndex)
                return null
            }

            val sourceType = getStoredSourceType(soundIndex)
            if (isReusableManagedSound(soundIndex, currentStoredUri, sourceType)) {
                if (soundIndex == GOODBYE_SOUND_INDEX && isServerManagedSoundFile(soundIndex, currentStoredUri)) {
                    userPreferencesRepository.saveGoodbyeServerAvailability(GoodbyeServerAvailability.PRESENT)
                }
                return Uri.parse(currentStoredUri)
            }

            appLogger.log(
                "SoundAssetManager",
                "Discarding stale or invalid stored sound for index $soundIndex: $currentStoredUri"
            )
            clearStoredSoundState(soundIndex)
        }

        return null
    }

    suspend fun downloadLatestSound(soundIndex: Int): Uri? {
        return when (val result = refreshManagedSound(soundIndex)) {
            is ManagedSoundRefreshResult.Ready -> result.uri
            ManagedSoundRefreshResult.MissingOnServer,
            ManagedSoundRefreshResult.Unavailable -> null
        }
    }

    suspend fun refreshManagedSound(soundIndex: Int): ManagedSoundRefreshResult {
        return refreshMutexFor(soundIndex).withLock {
            withContext(Dispatchers.IO) {
                var lastAttempt = ManagedRefreshAttempt(
                    result = ManagedSoundRefreshResult.Unavailable,
                    retryable = false
                )
                repeat(MANAGED_DOWNLOAD_ATTEMPTS) { attempt ->
                    lastAttempt = refreshManagedSoundOnce(soundIndex)
                    when (lastAttempt.result) {
                        is ManagedSoundRefreshResult.Ready,
                        ManagedSoundRefreshResult.MissingOnServer -> return@withContext lastAttempt.result

                        ManagedSoundRefreshResult.Unavailable -> {
                            val hasAttemptsRemaining = attempt < MANAGED_DOWNLOAD_ATTEMPTS - 1
                            if (!lastAttempt.retryable || !hasAttemptsRemaining) {
                                return@withContext lastAttempt.result
                            }

                            val nextAttemptNumber = attempt + 2
                            appLogger.log(
                                "SoundAssetManager",
                                "Retrying transient download for sound $soundIndex (attempt $nextAttemptNumber/$MANAGED_DOWNLOAD_ATTEMPTS)"
                            )
                            delay(MANAGED_DOWNLOAD_RETRY_DELAY_MS)
                        }
                    }
                }

                lastAttempt.result
            }
        }
    }

    private suspend fun refreshManagedSoundOnce(soundIndex: Int): ManagedRefreshAttempt {
        val token = userPreferencesRepository.accessToken.first()
        val userId = userPreferencesRepository.userId.first()
        val expectedGeneration = userPreferencesRepository.soundGeneration.first()
        val cleanupInProgress = userPreferencesRepository.cleanupInProgress.first()
        if (token.isNullOrBlank() || userId.isNullOrBlank()) {
            appLogger.log("SoundAssetManager", "Cannot download sound $soundIndex: missing token or userId")
            return ManagedRefreshAttempt(
                result = ManagedSoundRefreshResult.Unavailable,
                retryable = false
            )
        }

        if (cleanupInProgress) {
            appLogger.log("SoundAssetManager", "Cannot download sound $soundIndex while cleanup is in progress")
            return ManagedRefreshAttempt(
                result = ManagedSoundRefreshResult.Unavailable,
                retryable = false
            )
        }

        val requestType = if (soundIndex == GOODBYE_SOUND_INDEX) "goodbye" else "hello"
        return when (val downloadResult = iotStatusRepository.downloadSound(
            token = token,
            request = DownloadRequest(userId = userId, type = requestType)
        )) {
            is IotStatusRepository.DownloadSoundResult.Success -> {
                val safeName = buildServerFilename(soundIndex)
                val savedFile = fileRepository.saveFile(
                    body = downloadResult.body,
                    fileName = safeName,
                    directoryName = SOUND_STORAGE_DIRECTORY
                ) { stagedFile ->
                    val currentGeneration = userPreferencesRepository.soundGeneration.first()
                    val cleanupStillBlocked = userPreferencesRepository.cleanupInProgress.first()
                    stagedFile.length() >= MIN_VALID_SOUND_BYTES &&
                        currentGeneration == expectedGeneration &&
                        !cleanupStillBlocked
                }
                val commitStillAllowed =
                    userPreferencesRepository.soundGeneration.first() == expectedGeneration &&
                        !userPreferencesRepository.cleanupInProgress.first()
                if (savedFile == null || !savedFile.exists() || savedFile.length() < MIN_VALID_SOUND_BYTES) {
                    savedFile?.delete()
                    appLogger.log("SoundAssetManager", "Downloaded sound $soundIndex failed local validation")
                    return ManagedRefreshAttempt(
                        result = ManagedSoundRefreshResult.Unavailable,
                        retryable = commitStillAllowed
                    )
                }

                val storedUri = savedFile.toURI().toString()
                saveStoredUri(soundIndex, storedUri, userId)
                userPreferencesRepository.saveSoundSourceType(soundIndex, SoundSourceType.SERVER_MANAGED)
                userPreferencesRepository.saveSoundLabel(
                    soundIndex,
                    downloadResult.filename ?: AppRuntimePolicies.fixedServerSoundFileName(soundIndex)
                )
                userPreferencesRepository.saveSoundAcceptedGeneration(soundIndex, expectedGeneration)
                if (soundIndex == BootSoundCacheRepository.STARTUP_SOUND_INDEX) {
                    refreshStartupSoundCache(
                        sourceFile = savedFile,
                        failureMessage = "Startup sound cache not ready after server download"
                    )
                }
                appLogger.log("SoundAssetManager", "Downloaded latest sound $soundIndex to $storedUri")
                ManagedRefreshAttempt(
                    result = ManagedSoundRefreshResult.Ready(Uri.parse(storedUri)),
                    retryable = false
                )
            }

            IotStatusRepository.DownloadSoundResult.MissingOnServer -> {
                appLogger.log("SoundAssetManager", "Server does not provide sound $soundIndex for current account")
                ManagedRefreshAttempt(
                    result = ManagedSoundRefreshResult.MissingOnServer,
                    retryable = false
                )
            }

            IotStatusRepository.DownloadSoundResult.UnauthorizedOrBlocked -> {
                appLogger.log("SoundAssetManager", "Cannot download sound $soundIndex because remote sync is unavailable")
                ManagedRefreshAttempt(
                    result = ManagedSoundRefreshResult.Unavailable,
                    retryable = false
                )
            }

            IotStatusRepository.DownloadSoundResult.Failure -> {
                appLogger.log("SoundAssetManager", "Failed to download sound $soundIndex due to transient error")
                ManagedRefreshAttempt(
                    result = ManagedSoundRefreshResult.Unavailable,
                    retryable = true
                )
            }
        }
    }

    private suspend fun refreshStartupSoundCache(
        sourceFile: File,
        failureMessage: String
    ) {
        val cacheEntry = bootSoundCacheRepository.replaceStartupSoundCache(
            soundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
            sourceFile = sourceFile
        )
        if (cacheEntry != null) {
            appLogger.log(
                "SoundAssetManager",
                "Startup sound cache ready",
                "file=${cacheEntry.cacheFile.name} bytes=${cacheEntry.byteCount} soundIndex=${cacheEntry.soundIndex} updatedAt=${cacheEntry.updatedAtMillis}"
            )
            return
        }

        val readiness = bootSoundCacheRepository.inspectReadiness()
        appLogger.log(
            "SoundAssetManager",
            failureMessage,
            readiness.toLogDetails()
        )
    }

    suspend fun prepareBundledStartupSoundCache(): Boolean {
        return withContext(Dispatchers.IO) {
            val tempFile = File(
                context.cacheDir,
                "default_startup_sound-${System.nanoTime()}.audio"
            )
            try {
                context.resources.openRawResource(
                    AppRuntimePolicies.defaultSoundResIdForIndex(BootSoundCacheRepository.STARTUP_SOUND_INDEX)
                ).use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val cacheEntry = bootSoundCacheRepository.replaceStartupSoundCache(
                    soundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
                    sourceFile = tempFile
                )
                if (cacheEntry != null) {
                    appLogger.log(
                        "SoundAssetManager",
                        "Startup sound cache ready",
                        "source=bundled_default file=${cacheEntry.cacheFile.name} bytes=${cacheEntry.byteCount} soundIndex=${cacheEntry.soundIndex} updatedAt=${cacheEntry.updatedAtMillis}"
                    )
                    return@withContext true
                }

                val readiness = bootSoundCacheRepository.inspectReadiness()
                appLogger.log(
                    "SoundAssetManager",
                    "Startup sound cache not ready after bundled default copy",
                    readiness.toLogDetails()
                )
                false
            } catch (e: Exception) {
                appLogger.logException(
                    "SoundAssetManager",
                    e,
                    "Startup sound cache not ready after bundled default copy"
                )
                false
            } finally {
                runCatching {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
            }
        }
    }

    suspend fun deleteAllManagedSoundFiles() {
        deleteManagedSoundFiles(ownerUserId = null, soundIndex = null)
    }

    suspend fun ensureRequiredSoundsAvailable(): Boolean {
        return warmupRequiredSounds().allCustomSoundsReady()
    }

    suspend fun warmupRequiredSounds(): RequiredSoundWarmupResult {
        invalidateStoredSoundsForCurrentUserIfNeeded()

        val hello = resolveStoredSound(1) ?: downloadLatestSound(1)
        val goodbye = resolveGoodbyePlaybackSound()
        return RequiredSoundWarmupResult(
            helloUri = hello,
            goodbyeUri = goodbye
        )
    }

    private suspend fun getStoredUri(soundIndex: Int): String? {
        return if (soundIndex == 2) {
            userPreferencesRepository.soundUri2.first()
        } else {
            userPreferencesRepository.soundUri1.first()
        }
    }

    private suspend fun saveStoredUri(soundIndex: Int, storedUri: String) {
        saveStoredUri(soundIndex, storedUri, userPreferencesRepository.userId.first())
    }

    private suspend fun saveStoredUri(soundIndex: Int, storedUri: String, ownerUserId: String?) {
        if (soundIndex == 2) {
            userPreferencesRepository.saveSoundUri2(storedUri)
        } else {
            userPreferencesRepository.saveSoundUri1(storedUri)
        }

        if (!ownerUserId.isNullOrBlank()) {
            userPreferencesRepository.saveStoredSoundOwnerUserId(ownerUserId)
        }
    }

    private suspend fun clearStoredUri(soundIndex: Int) {
        if (soundIndex == 2) {
            userPreferencesRepository.clearSoundUri2()
        } else {
            userPreferencesRepository.clearSoundUri1()
        }
    }

    suspend fun invalidateStoredSoundsForCurrentUserIfNeeded() {
        val currentUserId = userPreferencesRepository.userId.first()
        val storedOwnerUserId = userPreferencesRepository.storedSoundOwnerUserId.first()
        val soundUri1 = userPreferencesRepository.soundUri1.first()
        val soundUri2 = userPreferencesRepository.soundUri2.first()

        if (
            AppRuntimePolicies.shouldInvalidateStoredSoundsForUser(
                soundUri1 = soundUri1,
                soundUri2 = soundUri2,
                storedOwnerUserId = storedOwnerUserId,
                currentUserId = currentUserId
            )
        ) {
            appLogger.log(
                "SoundAssetManager",
                "Clearing stored sounds because owner mismatch or legacy ownerless data was found. currentUserId=$currentUserId, storedOwnerUserId=$storedOwnerUserId"
            )
            deleteAllManagedSoundFiles()
            userPreferencesRepository.clearSoundUri1()
            userPreferencesRepository.clearSoundUri2()
            userPreferencesRepository.clearSoundSourceType(1)
            userPreferencesRepository.clearSoundSourceType(2)
            userPreferencesRepository.clearSoundLabel(1)
            userPreferencesRepository.clearSoundLabel(2)
            userPreferencesRepository.clearSoundAcceptedGeneration(1)
            userPreferencesRepository.clearSoundAcceptedGeneration(2)
            userPreferencesRepository.clearStoredSoundOwnerUserId()
            userPreferencesRepository.clearGoodbyeServerAvailability()
        }
    }

    private suspend fun resolveGoodbyePlaybackSound(): Uri? {
        val storedSound = resolveStoredSound(GOODBYE_SOUND_INDEX)
        if (storedSound != null) {
            return storedSound
        }

        return when (val downloadResult = refreshManagedSound(GOODBYE_SOUND_INDEX)) {
            is ManagedSoundRefreshResult.Ready -> downloadResult.uri
            ManagedSoundRefreshResult.MissingOnServer -> defaultGoodbyeSoundUri()
            ManagedSoundRefreshResult.Unavailable -> defaultGoodbyeSoundUri()
        }
    }

    private fun refreshMutexFor(soundIndex: Int): Mutex {
        return if (soundIndex == GOODBYE_SOUND_INDEX) {
            goodbyeRefreshMutex
        } else {
            helloRefreshMutex
        }
    }

    private suspend fun deleteManagedSoundFiles(ownerUserId: String?, soundIndex: Int?) {
        withContext(Dispatchers.IO) {
            val managedDirectory = managedSoundDirectory()
            val expectedNames = managedSoundFileNames(ownerUserId, soundIndex)

            managedDirectory.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && expectedNames.any { expectedName -> isManagedVariantFile(it.name, expectedName) } }
                ?.forEach { file ->
                    runCatching {
                        if (file.exists() && !file.delete()) {
                            appLogger.log(
                                "SoundAssetManager",
                                "Managed sound cache file could not be deleted: ${file.absolutePath}"
                            )
                        }
                    }.onFailure { throwable ->
                        appLogger.logException(
                            "SoundAssetManager",
                            throwable,
                            "Failed to delete managed sound cache file ${file.absolutePath}"
                        )
                    }
                }
        }
    }

    private fun buildServerFilename(soundIndex: Int): String {
        return AppRuntimePolicies.fixedServerSoundFileName(soundIndex)
    }

    private fun managedSoundFileNames(ownerUserId: String?, soundIndex: Int?): List<String> {
        return when {
            ownerUserId != null && soundIndex != null -> listOf(AppRuntimePolicies.fixedServerSoundFileName(soundIndex))
            soundIndex != null -> listOf(
                AppRuntimePolicies.fixedLocalSoundFileName(soundIndex),
                AppRuntimePolicies.fixedServerSoundFileName(soundIndex)
            )
            else -> listOf(
                AppRuntimePolicies.fixedLocalSoundFileName(1),
                AppRuntimePolicies.fixedLocalSoundFileName(2),
                AppRuntimePolicies.fixedServerSoundFileName(1),
                AppRuntimePolicies.fixedServerSoundFileName(2)
            )
        }
    }

    private fun isManagedVariantFile(fileName: String, expectedName: String): Boolean {
        return fileName == expectedName ||
            fileName.startsWith("$expectedName.") ||
            fileName.startsWith("$expectedName-")
    }

    private suspend fun getStoredSourceType(soundIndex: Int): SoundSourceType {
        return if (soundIndex == GOODBYE_SOUND_INDEX) {
            userPreferencesRepository.soundSourceType2.first()
        } else {
            userPreferencesRepository.soundSourceType1.first()
        }
    }

    private suspend fun getAcceptedGeneration(soundIndex: Int): Long? {
        return if (soundIndex == GOODBYE_SOUND_INDEX) {
            userPreferencesRepository.sound2AcceptedGeneration.first()
        } else {
            userPreferencesRepository.sound1AcceptedGeneration.first()
        }
    }

    private suspend fun clearStoredSoundState(soundIndex: Int) {
        if (soundIndex == GOODBYE_SOUND_INDEX) {
            userPreferencesRepository.clearSoundUri2()
            userPreferencesRepository.clearSoundSourceType(2)
            userPreferencesRepository.clearSoundLabel(2)
            userPreferencesRepository.clearSoundAcceptedGeneration(2)
            userPreferencesRepository.clearGoodbyeServerAvailability()
        } else {
            userPreferencesRepository.clearSoundUri1()
            userPreferencesRepository.clearSoundSourceType(1)
            userPreferencesRepository.clearSoundLabel(1)
            userPreferencesRepository.clearSoundAcceptedGeneration(1)
        }
    }

    private fun defaultGoodbyeSoundUri(): Uri {
        return Uri.parse("android.resource://${context.packageName}/${R.raw.default_goodbye_sound}")
    }

    private fun isReusableManagedSound(
        soundIndex: Int,
        storedUri: String,
        sourceType: SoundSourceType
    ): Boolean {
        return try {
            val file = File(URI(storedUri))
            if (!file.exists() || file.length() < MIN_VALID_SOUND_BYTES) {
                return false
            }

            when (sourceType) {
                SoundSourceType.USER_LOCAL -> file.name == AppRuntimePolicies.fixedLocalSoundFileName(soundIndex)
                SoundSourceType.SERVER_MANAGED -> file.name == AppRuntimePolicies.fixedServerSoundFileName(soundIndex)
                SoundSourceType.DEFAULT_GOODBYE -> soundIndex == GOODBYE_SOUND_INDEX
                SoundSourceType.NONE -> file.name == AppRuntimePolicies.fixedLocalSoundFileName(soundIndex) ||
                    file.name == AppRuntimePolicies.fixedServerSoundFileName(soundIndex)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isServerManagedSoundFile(soundIndex: Int, storedUri: String): Boolean {
        return try {
            val file = File(URI(storedUri))
            file.name == AppRuntimePolicies.fixedServerSoundFileName(soundIndex)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val GOODBYE_SOUND_INDEX = 2
        private const val MIN_VALID_SOUND_BYTES = 2048L
        private const val MANAGED_DOWNLOAD_ATTEMPTS = 3
        private const val MANAGED_DOWNLOAD_RETRY_DELAY_MS = 1_000L
        private const val SOUND_STORAGE_DIRECTORY = "sound_assets_v2"
    }
}
