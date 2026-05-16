package com.example.carchatbot.data.repository

import android.content.Context
import com.example.carchatbot.data.local.UserPreferencesRepository
import com.example.carchatbot.data.model.SoundSourceType
import com.example.carchatbot.runtime.AppRuntimePolicies
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackSoundResolution(
    val soundIndex: Int,
    val uriString: String,
    val sourceType: SoundSourceType
)

@Singleton
class PlaybackSoundResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val soundAssetManager: SoundAssetManager
) {
    suspend fun resolve(soundIndex: Int, preferRefresh: Boolean = false): PlaybackSoundResolution? {
        val localUri = localUriFor(soundIndex)
        var fallbackUri = soundAssetManager.ensureSoundAvailable(soundIndex)?.toString()
        if (fallbackUri.isNullOrBlank() && preferRefresh) {
            fallbackUri = soundAssetManager.downloadLatestSound(soundIndex)?.toString()
        }
        val fallbackSourceType = when {
            fallbackUri.isNullOrBlank() -> SoundSourceType.NONE
            soundIndex == GOODBYE_SOUND_INDEX && fallbackUri.startsWith(ANDROID_RESOURCE_PREFIX) ->
                SoundSourceType.DEFAULT_GOODBYE

            else -> SoundSourceType.SERVER_MANAGED
        }

        return choosePreferredSource(
            soundIndex = soundIndex,
            localUri = localUri,
            isLocalValid = isPlayableLocalUri(localUri),
            fallbackUri = fallbackUri,
            fallbackSourceType = fallbackSourceType
        )
    }

    private suspend fun localUriFor(soundIndex: Int): String? {
        val sourceType = if (soundIndex == GOODBYE_SOUND_INDEX) {
            userPreferencesRepository.soundSourceType2.first()
        } else {
            userPreferencesRepository.soundSourceType1.first()
        }
        if (sourceType != SoundSourceType.USER_LOCAL) {
            return null
        }

        val storedUri = if (soundIndex == GOODBYE_SOUND_INDEX) {
            userPreferencesRepository.soundUri2.first()
        } else {
            userPreferencesRepository.soundUri1.first()
        }

        if (!storedUri.isNullOrBlank()) {
            return storedUri
        }

        val fixedLocalFile = File(context.filesDir, AppRuntimePolicies.fixedLocalSoundFileName(soundIndex))
        return fixedLocalFile.takeIf { it.exists() }?.toURI()?.toString()
    }

    companion object {
        private const val GOODBYE_SOUND_INDEX = 2
        private const val ANDROID_RESOURCE_PREFIX = "android.resource://"

        fun choosePreferredSource(
            soundIndex: Int,
            localUri: String?,
            isLocalValid: Boolean,
            fallbackUri: String?,
            fallbackSourceType: SoundSourceType
        ): PlaybackSoundResolution? {
            if (!localUri.isNullOrBlank() && isLocalValid) {
                return PlaybackSoundResolution(
                    soundIndex = soundIndex,
                    uriString = localUri,
                    sourceType = SoundSourceType.USER_LOCAL
                )
            }

            if (!fallbackUri.isNullOrBlank() && fallbackSourceType != SoundSourceType.NONE) {
                return PlaybackSoundResolution(
                    soundIndex = soundIndex,
                    uriString = fallbackUri,
                    sourceType = fallbackSourceType
                )
            }

            return null
        }

        fun isPlayableLocalUri(uriString: String?): Boolean {
            if (uriString.isNullOrBlank()) {
                return false
            }

            return try {
                val parsedUri = URI(uriString.trim())
                when (parsedUri.scheme?.lowercase()) {
                    "file" -> {
                        val file = File(parsedUri)
                        file.exists() && file.length() > 0L
                    }

                    "content",
                    "android.resource" -> true

                    else -> false
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
