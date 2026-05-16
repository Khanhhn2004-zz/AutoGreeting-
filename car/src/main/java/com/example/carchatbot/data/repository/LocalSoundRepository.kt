package com.example.carchatbot.data.repository

import android.content.Context
import android.net.Uri
import com.example.carchatbot.boot.BootSoundCacheRepository
import com.example.carchatbot.data.local.UserPreferencesRepository
import com.example.carchatbot.data.model.SoundSourceType
import com.example.carchatbot.runtime.AppRuntimePolicies
import com.example.carchatbot.utils.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalSoundRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val fileRepository: FileRepository,
    private val bootSoundCacheRepository: BootSoundCacheRepository,
    private val appLogger: AppLogger
) {
    suspend fun importPickedSound(soundIndex: Int, pickedUri: Uri): Uri? {
        return withContext(Dispatchers.IO) {
            val expectedGeneration = userPreferencesRepository.soundGeneration.first()
            if (userPreferencesRepository.cleanupInProgress.first()) {
                appLogger.log("LocalSoundRepository", "Skipping import while cleanup is in progress")
                return@withContext null
            }

            val tempSourceFile = copyPickedFileToTemp(soundIndex, pickedUri) ?: return@withContext null
            try {
                if (!tempSourceFile.exists() || tempSourceFile.length() < MIN_VALID_SOUND_BYTES) {
                    appLogger.log("LocalSoundRepository", "Imported local sound $soundIndex is too small")
                    return@withContext null
                }

                val savedFile = fileRepository.replaceFile(
                    sourceFile = tempSourceFile,
                    fileName = AppRuntimePolicies.fixedLocalSoundFileName(soundIndex),
                    directoryName = SOUND_STORAGE_DIRECTORY
                ) { stagedFile ->
                    val currentGeneration = userPreferencesRepository.soundGeneration.first()
                    val cleanupInProgress = userPreferencesRepository.cleanupInProgress.first()
                    stagedFile.length() >= MIN_VALID_SOUND_BYTES &&
                        currentGeneration == expectedGeneration &&
                        !cleanupInProgress
                }

                if (savedFile == null || !savedFile.exists() || savedFile.length() < MIN_VALID_SOUND_BYTES) {
                    savedFile?.delete()
                    appLogger.log("LocalSoundRepository", "Local sound $soundIndex was not committed")
                    return@withContext null
                }

                val storedUri = savedFile.toURI().toString()
                persistLocalSoundMetadata(soundIndex, storedUri, expectedGeneration, pickedUri)
                if (soundIndex == BootSoundCacheRepository.STARTUP_SOUND_INDEX) {
                    refreshStartupSoundCache(savedFile)
                }
                appLogger.log("LocalSoundRepository", "Imported local sound $soundIndex to $storedUri")
                Uri.parse(storedUri)
            } finally {
                deleteIfExists(tempSourceFile)
            }
        }
    }

    private suspend fun refreshStartupSoundCache(sourceFile: File) {
        val cacheEntry = bootSoundCacheRepository.replaceStartupSoundCache(
            soundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
            sourceFile = sourceFile
        )
        if (cacheEntry != null) {
            appLogger.log(
                "LocalSoundRepository",
                "Startup sound cache ready",
                "file=${cacheEntry.cacheFile.name} bytes=${cacheEntry.byteCount} soundIndex=${cacheEntry.soundIndex} updatedAt=${cacheEntry.updatedAtMillis}"
            )
            return
        }

        val readiness = bootSoundCacheRepository.inspectReadiness()
        appLogger.log(
            "LocalSoundRepository",
            "Startup sound cache not ready after local import",
            readiness.toLogDetails()
        )
    }

    private suspend fun persistLocalSoundMetadata(
        soundIndex: Int,
        storedUri: String,
        expectedGeneration: Long,
        pickedUri: Uri
    ) {
        if (soundIndex == GOODBYE_SOUND_INDEX) {
            // Keep server availability untouched for local imports.
        }

        val currentUserId = userPreferencesRepository.userId.first()
        if (!currentUserId.isNullOrBlank()) {
            userPreferencesRepository.saveStoredSoundOwnerUserId(currentUserId)
        }

        if (soundIndex == GOODBYE_SOUND_INDEX) {
            userPreferencesRepository.saveSoundUri2(storedUri)
        } else {
            userPreferencesRepository.saveSoundUri1(storedUri)
        }

        userPreferencesRepository.saveSoundSourceType(soundIndex, SoundSourceType.USER_LOCAL)
        userPreferencesRepository.saveSoundLabel(
            soundIndex,
            AppRuntimePolicies.displayNameFromStoredUri(pickedUri.toString()) ?: fixedLocalLabel(soundIndex)
        )
        userPreferencesRepository.saveSoundAcceptedGeneration(soundIndex, expectedGeneration)
    }

    private fun fixedLocalLabel(soundIndex: Int): String {
        return if (soundIndex == GOODBYE_SOUND_INDEX) {
            "goodbye"
        } else {
            "hello"
        }
    }

    private fun copyPickedFileToTemp(soundIndex: Int, pickedUri: Uri): File? {
        return try {
            val tempDirectory = File(context.cacheDir, "sound_imports").apply { mkdirs() }
            val tempFile = File(
                tempDirectory,
                "${AppRuntimePolicies.fixedLocalSoundFileName(soundIndex)}.${System.nanoTime()}.tmp"
            )

            openPickedUri(pickedUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            tempFile
        } catch (e: Exception) {
            appLogger.logException("LocalSoundRepository", e, "Failed to copy picked sound to temp storage")
            null
        }
    }

    private fun openPickedUri(pickedUri: Uri) = when (pickedUri.scheme?.lowercase()) {
        "file" -> runCatching { File(pickedUri.path.orEmpty()).inputStream() }.getOrNull()
        else -> context.contentResolver.openInputStream(pickedUri)
    }

    private fun deleteIfExists(file: File?) {
        if (file == null) return
        runCatching {
            if (file.exists() && !file.delete()) {
                appLogger.log(
                    "LocalSoundRepository",
                    "Temp import file could not be deleted: ${file.absolutePath}"
                )
            }
        }
    }

    companion object {
        private const val GOODBYE_SOUND_INDEX = 2
        private const val MIN_VALID_SOUND_BYTES = 2048L
        private const val SOUND_STORAGE_DIRECTORY = "sound_assets_v2"
    }
}
