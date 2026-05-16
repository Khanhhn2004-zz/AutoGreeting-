package com.example.carchatbot.data.repository

import android.util.Log
import com.example.carchatbot.boot.BootSoundCacheRepository
import com.example.carchatbot.data.local.UserPreferencesRepository
import com.example.carchatbot.data.model.GoodbyeServerAvailability
import com.example.carchatbot.data.remote.IotApiService
import com.example.carchatbot.data.remote.model.*
import com.example.carchatbot.runtime.AppRuntimePolicies
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import java.io.IOException
import javax.inject.Inject

class IotStatusRepository @Inject constructor(
    private val iotApiService: IotApiService,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val bootSoundCacheRepository: BootSoundCacheRepository,
    private val appLogger: com.example.carchatbot.utils.AppLogger
) {
    sealed interface LoginResult {
        data object Success : LoginResult
        data object InvalidCredentials : LoginResult
        data object NetworkError : LoginResult
        data class UnexpectedError(val message: String?) : LoginResult
    }

    sealed interface DownloadSoundResult {
        data class Success(val body: okhttp3.ResponseBody, val filename: String?) : DownloadSoundResult
        data object MissingOnServer : DownloadSoundResult
        data object UnauthorizedOrBlocked : DownloadSoundResult
        data object Failure : DownloadSoundResult
    }

    suspend fun getIotStatus(): Boolean {
        return try {
            val response = iotApiService.getStatus(IOT_STATUS_URL)
            val newStatus = response.firstOrNull()?.status ?: false
            userPreferencesRepository.saveIotStatus(newStatus)
            newStatus
        } catch (e: Exception) {
            userPreferencesRepository.iotStatus.firstOrNull() ?: true
        }
    }
    
    suspend fun login(
        phoneNumber: String,
        password: String,
        persistLoginState: Boolean = true
    ): LoginResult {
        val request = com.example.carchatbot.data.remote.model.LoginRequest(phoneNumber, password)

        repeat(2) { attempt ->
            try {
                val response = iotApiService.login(request)
                if (response.success && response.accessToken != null) {
                    val previousUserId = userPreferencesRepository.userId.first()
                    val previousPhoneNumber = userPreferencesRepository.phoneNumber.first()
                    if (
                        AppRuntimePolicies.shouldClearStoredSoundsOnAccountChange(
                            previousUserId = previousUserId,
                            nextUserId = response.userId,
                            previousPhoneNumber = previousPhoneNumber,
                            nextPhoneNumber = phoneNumber
                        )
                    ) {
                        appLogger.log(
                            "IotStatusRepository",
                            "Clearing stored sounds because login switched account from $previousUserId/$previousPhoneNumber to ${response.userId}/$phoneNumber"
                        )
                        userPreferencesRepository.clearSoundUri1()
                        userPreferencesRepository.clearSoundUri2()
                        userPreferencesRepository.clearStoredSoundOwnerUserId()
                        userPreferencesRepository.clearGoodbyeServerAvailability()
                        bootSoundCacheRepository.clearStartupSoundCache()
                    }

                    userPreferencesRepository.saveAccessToken(response.accessToken)
                    userPreferencesRepository.saveRemoteSyncBlocked(false)
                    userPreferencesRepository.clearDemoExpirationTime()
                    userPreferencesRepository.savePhoneNumber(phoneNumber)
                    if (response.userId != null) {
                        userPreferencesRepository.saveUserId(response.userId)
                    }
                    if (persistLoginState) {
                        userPreferencesRepository.saveIsLoggedIn(true)
                    }
                    return LoginResult.Success
                }

                appLogger.log("IotStatusRepository", "Login rejected by backend without token")
                return LoginResult.InvalidCredentials
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401 || e.code() == 403) {
                    appLogger.log("IotStatusRepository", "Login unauthorized: ${e.code()}")
                    return LoginResult.InvalidCredentials
                }

                appLogger.logException("IotStatusRepository", e, "Login HTTP error")
                return LoginResult.UnexpectedError(e.message())
            } catch (e: IOException) {
                appLogger.logException("IotStatusRepository", e, "Login network error on attempt ${attempt + 1}")
                if (attempt == 0) {
                    delay(1_000)
                    return@repeat
                }
                return LoginResult.NetworkError
            } catch (e: Exception) {
                appLogger.logException("IotStatusRepository", e, "Login unexpected error")
                return LoginResult.UnexpectedError(e.message)
            }
        }

        return LoginResult.NetworkError
    }

    suspend fun finalizeLoginSession() {
        userPreferencesRepository.saveIsLoggedIn(true)
        userPreferencesRepository.saveRemoteSyncBlocked(false)
    }

    suspend fun clearPendingLoginSession() {
        userPreferencesRepository.clearUserSession()
    }

    suspend fun performHeartbeat(): Boolean {
        if (isRemoteSyncBlocked()) {
            appLogger.log("IotStatusRepository", "Heartbeat skipped: remote sync is blocked pending re-login")
            return false
        }

        val token = userPreferencesRepository.accessToken.firstOrNull()
        if (token == null) {
            android.util.Log.w("IotStatusRepository", "Heartbeat skipped: Token is missing")
            return false
        }
        
        return try {
            val pendingLogs = appLogger.peekPendingLogs()
            val logBatch = if (pendingLogs.isNotEmpty()) {
                com.example.carchatbot.data.remote.model.AppLogBatchRequest(pendingLogs)
            } else {
                null
            }
            val response = iotApiService.ping("Bearer $token", logBatch)
            
            if (response.success) {
                appLogger.acknowledgePendingLogs(pendingLogs)
                true
            } else {
                false
            }
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                android.util.Log.e("IotStatusRepository", "Heartbeat Unauthorized: ${e.code()}")
                appLogger.log("IotStatusRepository", "Unauthorized (${e.code()}) during heartbeat")
                handleUnauthorized()
            }
            false
        } catch (e: Exception) {
            Log.e("IotStatusRepository", "Heartbeat failed", e)
            appLogger.logException("IotStatusRepository", e, "Heartbeat failed")
            false
        }
    }

    suspend fun downloadSound(token: String, request: com.example.carchatbot.data.remote.model.DownloadRequest): DownloadSoundResult {
        if (isRemoteSyncBlocked()) {
            if (request.type == "goodbye") {
                userPreferencesRepository.saveGoodbyeServerAvailability(GoodbyeServerAvailability.UNKNOWN)
            }
            return DownloadSoundResult.UnauthorizedOrBlocked
        }

        return try {
            val response = iotApiService.downloadSound("Bearer $token", request)
            if (response.isSuccessful) {
                val body = response.body()
                var filename: String? = null
                val contentDisposition = response.headers()["Content-Disposition"]
                if (contentDisposition != null) {
                    val matcher = java.util.regex.Pattern.compile("filename=\"?([^\";]+)\"?").matcher(contentDisposition)
                    if (matcher.find()) {
                        filename = matcher.group(1)
                    }
                }
                if (body != null) {
                    if (request.type == "goodbye") {
                        userPreferencesRepository.saveGoodbyeServerAvailability(GoodbyeServerAvailability.PRESENT)
                    }
                    DownloadSoundResult.Success(body, filename)
                } else {
                    if (request.type == "goodbye") {
                        userPreferencesRepository.saveGoodbyeServerAvailability(GoodbyeServerAvailability.MISSING)
                    }
                    DownloadSoundResult.MissingOnServer
                }
            } else {
                when (response.code()) {
                    401, 403 -> {
                        handleUnauthorized()
                        if (request.type == "goodbye") {
                            userPreferencesRepository.saveGoodbyeServerAvailability(GoodbyeServerAvailability.UNKNOWN)
                        }
                        DownloadSoundResult.UnauthorizedOrBlocked
                    }
                    404 -> {
                        if (request.type == "goodbye") {
                            userPreferencesRepository.saveGoodbyeServerAvailability(GoodbyeServerAvailability.MISSING)
                        }
                        DownloadSoundResult.MissingOnServer
                    }
                    else -> DownloadSoundResult.Failure
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("IotStatusRepo", "Download Error", e)
            if (request.type == "goodbye") {
                userPreferencesRepository.saveGoodbyeServerAvailability(GoodbyeServerAvailability.UNKNOWN)
            }
            DownloadSoundResult.Failure
        }
    }

    suspend fun pushDeviceInfo(deviceInfo: com.example.carchatbot.data.remote.model.DeviceInfoRequest) {
        if (isRemoteSyncBlocked()) return

        val token = userPreferencesRepository.accessToken.firstOrNull() ?: return
        try {
            android.util.Log.d("IotStatusRepository", "Pushing device info: ${deviceInfo.model}")
            iotApiService.pushDeviceInfo("Bearer $token", deviceInfo)
            android.util.Log.d("IotStatusRepository", "Push device info success")
        } catch (e: retrofit2.HttpException) {
            android.util.Log.e("IotStatusRepository", "Push device info failed: ${e.code()}")
            if (e.code() == 401 || e.code() == 403) handleUnauthorized()
        } catch (e: Exception) {
            android.util.Log.e("IotStatusRepository", "Push device info error", e)
        }
    }

    private suspend fun handleUnauthorized() {
        Log.d("IotStatusRepository", "Handling unauthorized - blocking remote sync but preserving local session")
        appLogger.log("IotStatusRepository", "Remote sync blocked due to unauthorized response; keeping local session active")
        userPreferencesRepository.saveRemoteSyncBlocked(true)
    }

    private suspend fun isRemoteSyncBlocked(): Boolean {
        return userPreferencesRepository.isRemoteSyncBlocked.firstOrNull() ?: false
    }

    companion object {
        private const val IOT_STATUS_URL = "https://696c6705f4a79b31517ef44a.mockapi.io/api/v1/iot"
    }
}
