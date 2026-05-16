package com.example.carchatbot.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.carchatbot.boot.StartupMode
import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.boot.BootSoundCacheReadiness
import com.example.carchatbot.boot.BootSoundCacheReadinessReason
import com.example.carchatbot.boot.BootSoundCacheRepository
import com.example.carchatbot.data.local.UserPreferencesRepository
import com.example.carchatbot.data.repository.IotStatusRepository
import com.example.carchatbot.data.repository.LocalSoundRepository
import com.example.carchatbot.data.repository.SoundAssetManager
import com.example.carchatbot.runtime.AppRuntimePolicies
import com.example.carchatbot.support.SupportLogAppsScriptUploader
import com.example.carchatbot.support.SupportLogUploadResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StartupCacheUiState(
    val ready: Boolean,
    val message: String
) {
    companion object {
        fun checking(): StartupCacheUiState = StartupCacheUiState(
            ready = false,
            message = "\u0110ang ki\u1ec3m tra cache kh\u1edfi \u0111\u1ed9ng"
        )

        fun from(readiness: BootSoundCacheReadiness): StartupCacheUiState {
            return StartupCacheUiState(
                ready = readiness.isReady,
                message = when (readiness.reason) {
                    BootSoundCacheReadinessReason.READY ->
                        "S\u1eb5n s\u00e0ng ph\u00e1t khi kh\u1edfi \u0111\u1ed9ng l\u1ea1i"

                    BootSoundCacheReadinessReason.METADATA_NOT_READY ->
                        "Cache kh\u1edfi \u0111\u1ed9ng \u0111ang chu\u1ea9n b\u1ecb - ch\u1edd v\u00e0i gi\u00e2y"

                    BootSoundCacheReadinessReason.INVALID_METADATA,
                    BootSoundCacheReadinessReason.AUDIO_TOO_SMALL ->
                        "File \u00e2m thanh c\u00f3 nh\u01b0ng cache kh\u1edfi \u0111\u1ed9ng l\u1ed7i - t\u1ea3i l\u1ea1i ho\u1eb7c \u0111\u1ed5i file"

                    BootSoundCacheReadinessReason.MISSING_METADATA,
                    BootSoundCacheReadinessReason.MISSING_AUDIO_FILE ->
                        "Ch\u01b0a s\u1eb5n s\u00e0ng ph\u00e1t khi kh\u1edfi \u0111\u1ed9ng l\u1ea1i - t\u1ea3i l\u1ea1i ho\u1eb7c \u0111\u1ed5i file"
                }
            )
        }
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val iotStatusRepository: IotStatusRepository,
    private val localSoundRepository: LocalSoundRepository,
    private val soundAssetManager: SoundAssetManager,
    private val bootSoundCacheRepository: BootSoundCacheRepository,
    private val bootPlaybackStateStore: BootPlaybackStateStore,
    private val supportLogAppsScriptUploader: SupportLogAppsScriptUploader,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val savedSoundUri1 = userPreferencesRepository.soundUri1
    val savedSoundUri2 = userPreferencesRepository.soundUri2
    val soundLabel1 = userPreferencesRepository.soundLabel1
    val soundLabel2 = userPreferencesRepository.soundLabel2
    val soundSourceType1 = userPreferencesRepository.soundSourceType1
    val soundSourceType2 = userPreferencesRepository.soundSourceType2

    private val _iotStatus = MutableStateFlow<Boolean?>(null)
    val iotStatus: StateFlow<Boolean?> = _iotStatus

    private val _startupCacheUiState = MutableStateFlow(StartupCacheUiState.checking())
    val startupCacheUiState: StateFlow<StartupCacheUiState> = _startupCacheUiState

    init {
        viewModelScope.launch {
            _iotStatus.value = AppRuntimePolicies.resolveInitialIotStatus(
                userPreferencesRepository.iotStatus.first()
            )
            refreshIotStatus()
        }
        observeStartupSoundCacheReadiness()
        startDemoExpirationCheck()
    }

    private fun startDemoExpirationCheck() {
        viewModelScope.launch {
            userPreferencesRepository.demoExpirationTime.collect { expirationTime ->
                if (expirationTime != null && System.currentTimeMillis() > expirationTime) {
                    Log.d("MainViewModel", "Demo expired, cleaning up services and data")

                    stopAllAppServices("demo expiration")
                    awaitPlaybackIdleAfterCleanupStop()

                    soundAssetManager.deleteAllManagedSoundFiles()
                    userPreferencesRepository.saveIsDemoConsumed(true)
                    userPreferencesRepository.clearUserSession()
                    userPreferencesRepository.clearDemoExpirationTime()

                    _navigateToLoginEvent.emit(Unit)
                }
            }
        }
    }

    fun fetchIotStatus() {
        viewModelScope.launch {
            refreshIotStatus()
        }
    }

    private suspend fun refreshIotStatus() {
        _iotStatus.value = iotStatusRepository.getIotStatus()
    }

    fun importLocalSound(soundIndex: Int, uri: Uri) {
        viewModelScope.launch {
            localSoundRepository.importPickedSound(soundIndex, uri)
            if (soundIndex == BootSoundCacheRepository.STARTUP_SOUND_INDEX) {
                refreshStartupCacheReadiness()
            }
        }
    }

    fun refreshStartupCacheStatus() {
        viewModelScope.launch {
            refreshStartupCacheReadiness()
        }
    }

    fun prepareDemoStartupSoundCache() {
        viewModelScope.launch {
            soundAssetManager.prepareBundledStartupSoundCache()
            refreshStartupCacheReadiness()
        }
    }

    private fun observeStartupSoundCacheReadiness() {
        viewModelScope.launch {
            savedSoundUri1.distinctUntilChanged().collect {
                if (refreshStartupCacheReadiness()) {
                    return@collect
                }

                repeat(STARTUP_CACHE_READINESS_RETRY_COUNT) {
                    delay(STARTUP_CACHE_READINESS_RETRY_DELAY_MS)
                    if (refreshStartupCacheReadiness()) {
                        return@collect
                    }
                }
            }
        }
    }

    private suspend fun refreshStartupCacheReadiness(): Boolean {
        val readiness = bootSoundCacheRepository.inspectReadiness()
        _startupCacheUiState.value = StartupCacheUiState.from(readiness)
        return readiness.isReady
    }

    val playOnOpenEnabled = userPreferencesRepository.playOnOpenEnabled
    val startupMode = userPreferencesRepository.startupMode
    val bootPlaybackState = bootPlaybackStateStore.stateFlow

    fun setPlayOnOpenEnabled(isEnabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.savePlayOnOpenEnabled(isEnabled)
        }
    }

    fun setStartupMode(startupMode: StartupMode) {
        viewModelScope.launch {
            userPreferencesRepository.saveStartupMode(startupMode)
            userPreferencesRepository.savePlayOnOpenEnabled(startupMode == StartupMode.APP_AUTO_OPEN)
        }
    }

    val floatingButtonEnabled = userPreferencesRepository.floatingButtonEnabled
    val isLoggedIn = userPreferencesRepository.isLoggedIn
    val isRemoteSyncBlocked = userPreferencesRepository.isRemoteSyncBlocked
    val demoExpirationTime = userPreferencesRepository.demoExpirationTime

    private val _navigateToLoginEvent = MutableSharedFlow<Unit>()
    val navigateToLoginEvent = _navigateToLoginEvent.asSharedFlow()

    fun setFloatingButtonEnabled(isEnabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.saveFloatingButtonEnabled(isEnabled)
        }
    }

    fun logout() {
        viewModelScope.launch {
            val nextGeneration = userPreferencesRepository.soundGeneration.first() + 1L
            userPreferencesRepository.saveCleanupInProgress(true)
            try {
                userPreferencesRepository.saveSoundGeneration(nextGeneration)
                stopAllAppServices("manual logout")
                awaitPlaybackIdleAfterCleanupStop()
                soundAssetManager.deleteAllManagedSoundFiles()
                bootSoundCacheRepository.clearStartupSoundCache()
                userPreferencesRepository.clearUserSessionForTeardown(nextGeneration)
            } finally {
                userPreferencesRepository.saveCleanupInProgress(false)
            }
            _navigateToLoginEvent.emit(Unit)
        }
    }

    suspend fun uploadSupportLog(): SupportLogUploadResult {
        return supportLogAppsScriptUploader.upload()
    }

    private fun stopAllAppServices(reason: String) {
        try {
            context.stopService(Intent(context, com.example.carchatbot.boot.BootPlaybackService::class.java))
            context.stopService(Intent(context, com.example.carchatbot.service.SoundPlayerService::class.java))
            context.stopService(Intent(context, com.example.carchatbot.service.FloatingButtonService::class.java))
            context.stopService(Intent(context, com.example.carchatbot.service.CoreService::class.java))
            Log.d("MainViewModel", "All services stopped on $reason")
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error stopping services on $reason", e)
        }
    }

    private suspend fun awaitPlaybackIdleAfterCleanupStop() {
        repeat(10) {
            if (
                com.example.carchatbot.service.SoundPlayerState.isManualPlaybackIdle() &&
                !bootPlaybackStateStore.readState().isActive()
            ) {
                return
            }
            kotlinx.coroutines.delay(100)
        }
        if (!bootPlaybackStateStore.readState().isActive()) {
            com.example.carchatbot.service.SoundPlayerState.clearManualPlaybackForTeardown()
        }
    }

    private companion object {
        const val STARTUP_CACHE_READINESS_RETRY_COUNT = 4
        const val STARTUP_CACHE_READINESS_RETRY_DELAY_MS = 1_000L
    }
}
