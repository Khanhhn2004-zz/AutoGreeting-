package com.example.carchatbot.ui.login

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.example.carchatbot.boot.BootSoundCacheRepository
import com.example.carchatbot.data.local.UserPreferencesRepository
import com.example.carchatbot.data.repository.SoundAssetManager
import com.example.carchatbot.data.remote.IotApiService
import com.example.carchatbot.runtime.AppRuntimePolicies
import com.example.carchatbot.service.SoundPlayerService
import com.example.carchatbot.service.SoundPlayerState
import com.example.carchatbot.worker.DownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val iotApiService: IotApiService,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val workManager: WorkManager,
    private val iotStatusRepository: com.example.carchatbot.data.repository.IotStatusRepository,
    private val soundAssetManager: SoundAssetManager,
    private val bootSoundCacheRepository: BootSoundCacheRepository,
    private val appLogger: com.example.carchatbot.utils.AppLogger,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState

    val isDemoConsumed: StateFlow<Boolean> = userPreferencesRepository.isDemoConsumed
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), false)

    fun startDemo() {
        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            appLogger.log("LoginViewModel", "Demo mode activated")

            if (System.currentTimeMillis() < 1735700000000L) {
                val error = "Vui long chinh dung ngay gio he thong truoc khi dung thu"
                appLogger.log("LoginViewModel", "Demo failed: invalid system time")
                _loginState.value = LoginState.Error(error)
                return@launch
            }

            try {
                val expirationTime = System.currentTimeMillis() + 60 * 60 * 1000
                userPreferencesRepository.saveDemoExpirationTime(expirationTime)
                userPreferencesRepository.saveIsLoggedIn(true)
                val startupCacheReady = soundAssetManager.prepareBundledStartupSoundCache()
                appLogger.log(
                    "LoginViewModel",
                    if (startupCacheReady) {
                        "Demo startup sound cache ready"
                    } else {
                        "Demo startup sound cache not ready"
                    }
                )

                appLogger.log("LoginViewModel", "Demo mode successful, expires in 1 hour")
                _loginState.value = LoginState.Success
            } catch (e: Exception) {
                appLogger.logException("LoginViewModel", e, "Demo activation failed")
                _loginState.value = LoginState.Error("Khong the kich hoat che do dung thu: ${e.message}")
            }
        }
    }

    fun login(phoneNumber: String, password: String) {
        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            appLogger.log("LoginViewModel", "Login attempt for phone: ${phoneNumber.take(3)}***")

            try {
                val previousUserId = userPreferencesRepository.userId.first()
                val previousPhoneNumber = userPreferencesRepository.phoneNumber.first()
                when (
                    iotStatusRepository.login(
                    phoneNumber = phoneNumber,
                    password = password,
                    persistLoginState = false
                )
                ) {
                    is com.example.carchatbot.data.repository.IotStatusRepository.LoginResult.Success -> {
                        val currentUserId = userPreferencesRepository.userId.first()
                        val shouldClearStoredSounds = AppRuntimePolicies.shouldClearStoredSoundsOnAccountChange(
                            previousUserId = previousUserId,
                            nextUserId = currentUserId,
                            previousPhoneNumber = previousPhoneNumber,
                            nextPhoneNumber = phoneNumber
                        )
                        if (shouldClearStoredSounds) {
                            prepareManagedSoundRefresh("account_switch_login")
                        }
                        soundAssetManager.invalidateStoredSoundsForCurrentUserIfNeeded()
                        iotStatusRepository.finalizeLoginSession()
                        appLogger.log("LoginViewModel", "Login successful")
                        _loginState.value = LoginState.Success

                        val soundUri1 = userPreferencesRepository.soundUri1.first()
                        val soundUri2 = userPreferencesRepository.soundUri2.first()
                        if (AppRuntimePolicies.shouldAutoDownloadSounds(soundUri1, soundUri2)) {
                            android.util.Log.d("LoginViewModel", "Primary server sound missing after login. Enqueuing DownloadWorker.")
                            appLogger.logWorker("DownloadWorker", "Sound download enqueued after login success")
                            workManager.enqueueUniqueWork(
                                DownloadWorker.UNIQUE_WORK_NAME,
                                ExistingWorkPolicy.KEEP,
                                DownloadWorker.createRequest("login_success_followup")
                            )
                        }
                    }

                    is com.example.carchatbot.data.repository.IotStatusRepository.LoginResult.InvalidCredentials -> {
                        appLogger.log("LoginViewModel", "Login failed: invalid credentials")
                        _loginState.value = LoginState.Error("Dang nhap that bai. Kiem tra so dien thoai hoac mat khau.")
                    }

                    is com.example.carchatbot.data.repository.IotStatusRepository.LoginResult.NetworkError -> {
                        appLogger.log("LoginViewModel", "Login failed: network timeout or connection issue")
                        _loginState.value = LoginState.Error("Ket noi mang khong on dinh. Vui long kiem tra mang va thu lai.")
                    }

                    is com.example.carchatbot.data.repository.IotStatusRepository.LoginResult.UnexpectedError -> {
                        appLogger.log("LoginViewModel", "Login failed: unexpected backend error")
                        _loginState.value = LoginState.Error("Dang nhap that bai do loi he thong. Vui long thu lai.")
                    }
                }
            } catch (e: Exception) {
                appLogger.logException("LoginViewModel", e, "Login error")
                _loginState.value = LoginState.Error("Loi: ${e.message}")
            }
        }
    }

    private suspend fun prepareManagedSoundRefresh(reason: String) {
        val nextGeneration = userPreferencesRepository.soundGeneration.first() + 1L
        userPreferencesRepository.saveCleanupInProgress(true)
        try {
            userPreferencesRepository.saveSoundGeneration(nextGeneration)
            awaitPlaybackIdleAfterCleanupStop()
            soundAssetManager.deleteAllManagedSoundFiles()
            bootSoundCacheRepository.clearStartupSoundCache()
            clearStoredSoundState()
            appLogger.log("LoginViewModel", "Prepared managed sound refresh for $reason at generation $nextGeneration")
        } finally {
            userPreferencesRepository.saveCleanupInProgress(false)
        }
    }

    private suspend fun clearStoredSoundState() {
        userPreferencesRepository.clearSoundUri1()
        userPreferencesRepository.clearSoundUri2()
        userPreferencesRepository.clearSoundSourceType(1)
        userPreferencesRepository.clearSoundSourceType(2)
        userPreferencesRepository.clearSoundLabel(1)
        userPreferencesRepository.clearSoundLabel(2)
        userPreferencesRepository.clearSoundAcceptedGeneration(1)
        userPreferencesRepository.clearSoundAcceptedGeneration(2)
        userPreferencesRepository.clearAutoplaySuppressed(1)
        userPreferencesRepository.clearAutoplaySuppressed(2)
        userPreferencesRepository.clearStoredSoundOwnerUserId()
        userPreferencesRepository.clearGoodbyeServerAvailability()
    }

    private suspend fun awaitPlaybackIdleAfterCleanupStop() {
        context.stopService(Intent(context, SoundPlayerService::class.java))
        repeat(10) {
            if (
                !SoundPlayerState.isPlaying.value &&
                SoundPlayerState.activeRequest.value == null &&
                SoundPlayerState.playingRequest.value == null
            ) {
                return
            }
            delay(100)
        }
        SoundPlayerState.onPlaybackStopped()
    }
}

sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    object Success : LoginState()
    data class Error(val message: String) : LoginState()
}
