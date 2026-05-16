package com.example.carchatbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.carchatbot.R
import com.example.carchatbot.boot.BootPlaybackService
import com.example.carchatbot.boot.BootSoundArbiterAction
import com.example.carchatbot.boot.StartupCompatCoordinator
import com.example.carchatbot.boot.StartupTriggerRouter
import com.example.carchatbot.data.remote.model.DeviceInfoRequest
import com.example.carchatbot.data.repository.SoundAssetManager
import com.example.carchatbot.runtime.AppRuntimePolicies
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service nền trung tâm giữ các vòng lặp runtime của app.
 *
 * CoreService chịu trách nhiệm heartbeat, đồng bộ device info, tải sound, giữ
 * FloatingButtonService đúng trạng thái và chạy runtime reconcile cho BOOT
 * recovery. Service này không trực tiếp phát audio BOOT.
 */
@AndroidEntryPoint
class CoreService : Service() {

    @Inject
    lateinit var userPreferencesRepository: com.example.carchatbot.data.local.UserPreferencesRepository

    @Inject
    lateinit var iotStatusRepository: com.example.carchatbot.data.repository.IotStatusRepository

    @Inject
    lateinit var appLogger: com.example.carchatbot.utils.AppLogger

    @Inject
    lateinit var soundAssetManager: SoundAssetManager

    @Inject
    lateinit var startupCompatCoordinator: StartupCompatCoordinator

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cachedDeviceInfo: DeviceInfoRequest? = null
    private var backgroundLoopsStarted = false
    private val reconcileInProgress = AtomicBoolean(false)

    /**
     * Khởi tạo foreground service, wake lock holder và cache thông tin thiết bị.
     */
    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "CarChatbot:CoreServiceWakeLock"
        )
        wakeLock?.setReferenceCounted(false)

        startForeground(NOTIFICATION_ID, buildNotification())

        appLogger.logService(TAG, "Service created")

        serviceScope.launch(Dispatchers.IO) {
            cacheDeviceInfo()
        }
    }

    /**
     * Điều phối action gửi tới CoreService.
     *
     * Download sound được chạy trong wake lock riêng; reconcile runtime được
     * coalesce để tránh nhiều lần kiểm tra/service start chồng nhau.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        android.util.Log.d(TAG, "onStartCommand: action=$action")

        when {
            action == ACTION_RECONCILE_RUNTIME_SERVICES -> {
                launchReconcileIfIdle(action)
            }

            action == ACTION_START_SOUND_DOWNLOAD -> {
                android.util.Log.d(TAG, "Received request to download sounds.")
                acquireWakeLock()
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        if (!ensureUsageAllowed("ACTION_START_SOUND_DOWNLOAD")) {
                            sendSoundFailureBroadcast()
                            return@launch
                        }
                        performSoundDownload()
                    } finally {
                        releaseWakeLock()
                    }
                }
            }

            shouldIgnoreExternalPowerAction(action) -> {
                appLogger.log(TAG, "Ignoring external power event action: $action")
            }

            else -> {
                launchReconcileIfIdle(action ?: "ACTION_DEFAULT")
            }
        }

        return START_STICKY
    }

    /**
     * Start các vòng lặp nền một lần duy nhất trong đời service.
     */
    private fun ensureBackgroundLoopsStarted() {
        if (backgroundLoopsStarted) {
            return
        }

        backgroundLoopsStarted = true
        android.util.Log.d(TAG, "Starting CoreService background loops...")
        appLogger.logService(TAG, "Background loops starting")
        startHeartbeatLoop()
    }

    /**
     * Kiểm tra user/session còn được phép dùng app trước khi chạy tác vụ nền.
     */
    private suspend fun ensureUsageAllowed(reason: String): Boolean {
        val isLoggedIn = userPreferencesRepository.isLoggedIn.first()
        val demoTime = userPreferencesRepository.demoExpirationTime.first()
        val isAllowed = AppRuntimePolicies.isUsageAllowed(isLoggedIn, demoTime)

        if (!isAllowed) {
            appLogger.log(TAG, "Usage not allowed - stopping CoreService ($reason)")
            shutdownSelf("usage_not_allowed:$reason")
            return false
        }

        ensureBackgroundLoopsStarted()
        return true
    }

    /**
     * Đảm bảo chỉ có một reconcile chạy tại một thời điểm.
     *
     * Nếu reconcile đang chạy, request mới được coalesce: log lại và bỏ qua để
     * tránh start/stop service liên tục trong lúc boot.
     */
    private fun launchReconcileIfIdle(reason: String) {
        if (!reconcileInProgress.compareAndSet(false, true)) {
            appLogger.log(TAG, "Reconcile already in progress, coalescing request", "reason=$reason")
            return
        }
        serviceScope.launch {
            try {
                ensureRuntimeServicesHealthy(reason)
            } finally {
                reconcileInProgress.set(false)
            }
        }
    }

    /**
     * Reconcile các service nền cần sống lâu như Core/FloatingButton và BOOT recovery.
     *
     * Với nhánh BOOT, hàm này đóng vai trò "second chance" sau receiver: nếu
     * receiver đã arm một BOOT window nhưng chưa phát được, runtime reconcile có
     * thể consume window đó qua [recoverPendingBootStartupFromRuntimeReconcile].
     */
    private suspend fun ensureRuntimeServicesHealthy(reason: String) {
        try {
            if (!ensureUsageAllowed("runtime:$reason")) {
                return
            }

            appLogger.log(TAG, "Checking runtime service state (reason: $reason)")

            val isLoggedIn = userPreferencesRepository.isLoggedIn.first()
            val floatingButtonEnabled = userPreferencesRepository.floatingButtonEnabled.first()
            val canDrawOverlays = AppRuntimePolicies.canDrawOverlaysCompat(this)
            val rawOverlayPermission = readRawOverlayPermission()
            val shouldStartFloatingButton = AppRuntimePolicies.shouldStartFloatingButton(
                isLoggedIn = isLoggedIn,
                floatingButtonEnabled = floatingButtonEnabled,
                canDrawOverlays = canDrawOverlays
            )

            appLogger.log(
                TAG,
                "Runtime state - LoggedIn: $isLoggedIn, Overlay: $canDrawOverlays, FloatingEnabled: $floatingButtonEnabled"
            )
            android.util.Log.d(
                TAG,
                "Runtime state - LoggedIn: $isLoggedIn, Overlay: $canDrawOverlays, FloatingEnabled: $floatingButtonEnabled"
            )

            val floatingButtonRunning = FloatingButtonService.isServiceMarkedRunning()
            val reconcileDetails =
                "reason=$reason loggedIn=$isLoggedIn floatingEnabled=$floatingButtonEnabled overlayCompat=$canDrawOverlays overlayRaw=$rawOverlayPermission shouldStartFloatingButton=$shouldStartFloatingButton floatingButtonRunning=$floatingButtonRunning"
            appLogger.log(TAG, "FloatingButtonService reconcile evaluated", reconcileDetails)

            if (
                shouldStopFloatingButtonService(
                    shouldStartFloatingButton = shouldStartFloatingButton,
                    floatingButtonRunning = floatingButtonRunning
                )
            ) {
                stopService(Intent(this, FloatingButtonService::class.java))
                appLogger.log(TAG, "FloatingButtonService runtime stop requested", reconcileDetails)
            }

            if (
                shouldRestartFloatingButtonService(
                    shouldStartFloatingButton = shouldStartFloatingButton,
                    floatingButtonRunning = floatingButtonRunning
                )
            ) {
                val floatIntent = FloatingButtonService.createStartIntent(
                    context = this,
                    launchReason = FloatingButtonService.LAUNCH_REASON_RUNTIME_RECONCILE
                )
                ContextCompat.startForegroundService(this, floatIntent)
                appLogger.log(
                    TAG,
                    "FloatingButtonService runtime start requested",
                    "$reconcileDetails launchReason=${FloatingButtonService.LAUNCH_REASON_RUNTIME_RECONCILE}"
                )
            }

            recoverPendingBootStartupFromRuntimeReconcile(
                reason = reason,
                isLoggedIn = isLoggedIn
            )

            appLogger.log(TAG, "Runtime service check complete")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error in ensureRuntimeServicesHealthy", e)
            appLogger.logException(TAG, e, "Failed to verify runtime services")
        }
    }

    /**
     * Recovery BOOT playback từ runtime reconcile, không phụ thuộc UI.
     *
     * Đường này chỉ chạy khi user đang đăng nhập và startup mode là
     * `BOOT_COMPLETED`. Nó không tự phát audio mà gọi [StartupCompatCoordinator]
     * và [BootPlaybackService] để giữ đúng ownership/session invariant.
     */
    private suspend fun recoverPendingBootStartupFromRuntimeReconcile(
        reason: String,
        isLoggedIn: Boolean
    ) {
        if (!isLoggedIn) {
            return
        }

        val startupMode = userPreferencesRepository.startupMode.first()
        if (!StartupTriggerRouter.allowsBootCompleted(startupMode)) {
            return
        }

        val compatResult = startupCompatCoordinator.handleRuntimeReconcileSignal(
            startupMode = startupMode
        ) ?: return
        val details =
            "reason=$reason startupMode=$startupMode profile=${compatResult.armState.compatibilityProfile} strategy=${compatResult.executionPlan.strategy} executionReason=${compatResult.executionPlan.reason} startupWindowId=${compatResult.armState.startupWindowId}"
        val decision = compatResult.playbackDecision ?: run {
            appLogger.log(TAG, "Runtime BOOT reconcile deferred", details)
            return
        }

        when (decision.action) {
            BootSoundArbiterAction.START_NOW,
            BootSoundArbiterAction.TAKEOVER_STALE -> {
                val started = BootPlaybackService.startForExecution(
                    context = this,
                    decision = decision,
                    executionPlan = compatResult.executionPlan
                )
                appLogger.log(
                    TAG,
                    if (started) {
                        "Runtime BOOT reconcile playback start requested"
                    } else {
                        "Runtime BOOT reconcile playback start rejected"
                    },
                    "$details decision=${decision.action} sessionId=${decision.state.sessionId}"
                )
            }

            BootSoundArbiterAction.WAIT_EXISTING,
            BootSoundArbiterAction.REJECT_SECOND_TAKEOVER,
            BootSoundArbiterAction.SKIP_ALREADY_HANDLED -> {
                appLogger.log(
                    TAG,
                    "Runtime BOOT reconcile decision=${decision.action}",
                    "$details decision=${decision.action} sessionId=${decision.state.sessionId}"
                )
            }
        }
    }

    /**
     * Dừng các child service mà CoreService quản lý trực tiếp.
     */
    private fun shutdownChildServices() {
        appLogger.log(TAG, "Shutting down child services")
        stopService(Intent(this, SoundPlayerService::class.java))
        stopService(Intent(this, FloatingButtonService::class.java))
        android.util.Log.d(TAG, "Child services stopped")
    }

    /**
     * Tự dừng CoreService khi user/session không còn hợp lệ hoặc app cần shutdown.
     */
    private fun shutdownSelf(reason: String) {
        appLogger.log(TAG, "Stopping CoreService ($reason)")
        backgroundLoopsStarted = false
        serviceScope.cancel("shutdown:$reason")
        shutdownChildServices()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Dọn service scope, wake lock và foreground notification khi service bị hủy.
     */
    override fun onDestroy() {
        super.onDestroy()

        appLogger.logService(TAG, "Service stopping")

        releaseWakeLock()
        serviceScope.cancel()
        android.util.Log.d(TAG, "CoreService Destroyed")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * Chạy heartbeat loop định kỳ và monitor child service sau mỗi nhịp.
     */
    private fun startHeartbeatLoop() {
        serviceScope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            while (isActive) {
                try {
                    val remoteSyncBlocked = userPreferencesRepository.isRemoteSyncBlocked.first()
                    val isAlive = if (remoteSyncBlocked) {
                        appLogger.logService(TAG, "Remote sync blocked; skipping heartbeat")
                        false
                    } else {
                        iotStatusRepository.performHeartbeat()
                    }
                    android.util.Log.d(TAG, "Heartbeat result: $isAlive")
                    appLogger.logService(TAG, "Heartbeat result: $isAlive")

                    if (isAlive) {
                        consecutiveFailures = 0
                        pushDeviceInfo()
                    } else {
                        consecutiveFailures =
                            if (remoteSyncBlocked) 3 else minOf(consecutiveFailures + 1, 3)
                    }

                    monitorChildServices()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error in heartbeat loop", e)
                    appLogger.logException(TAG, e, "Heartbeat loop error")
                    consecutiveFailures = minOf(consecutiveFailures + 1, 3)
                }

                delay(AppRuntimePolicies.nextHeartbeatDelayMillis(consecutiveFailures))
            }
        }
    }

    /**
     * Kiểm tra FloatingButtonService có nên start/stop theo login, overlay và setting.
     */
    private suspend fun monitorChildServices() {
        try {
            val isLoggedIn = userPreferencesRepository.isLoggedIn.first()
            val demoTime = userPreferencesRepository.demoExpirationTime.first()
            val floatingButtonEnabled = userPreferencesRepository.floatingButtonEnabled.first()
            val isAllowed = AppRuntimePolicies.isUsageAllowed(isLoggedIn, demoTime)

            if (!isAllowed) {
                appLogger.log(TAG, "User no longer allowed - stopping CoreService")
                shutdownSelf("monitor_not_allowed")
                return
            }

            val canDrawOverlays = AppRuntimePolicies.canDrawOverlaysCompat(this)
            val floatRunning = FloatingButtonService.isServiceMarkedRunning()

            if (shouldRestartFloatingButtonService(
                    shouldStartFloatingButton = AppRuntimePolicies.shouldStartFloatingButton(
                        isLoggedIn,
                        floatingButtonEnabled,
                        canDrawOverlays
                    ),
                    floatingButtonRunning = floatRunning
                )
            ) {
                appLogger.log(TAG, "Health check: FloatingButtonService dead, restarting...")
                val floatIntent = FloatingButtonService.createStartIntent(
                    context = this,
                    launchReason = FloatingButtonService.LAUNCH_REASON_RUNTIME_RECONCILE
                )
                ContextCompat.startForegroundService(this, floatIntent)
            }

            if (shouldStopFloatingButtonService(
                    shouldStartFloatingButton = AppRuntimePolicies.shouldStartFloatingButton(
                        isLoggedIn,
                        floatingButtonEnabled,
                        canDrawOverlays
                    ),
                    floatingButtonRunning = floatRunning
                )
            ) {
                appLogger.log(TAG, "Health check: FloatingButtonService should stop because eligibility is gone")
                stopService(Intent(this, FloatingButtonService::class.java))
            }
        } catch (e: Exception) {
            appLogger.logException(TAG, e, "Error in service health monitor")
        }
    }

    /**
     * Refresh các sound server-managed và publish progress broadcast cho UI.
     *
     * Sau khi sound 1 được refresh, [SoundAssetManager] chịu trách nhiệm chuẩn
     * bị boot cache local để nhánh BOOT không phải chờ server ở lần khởi động sau.
     */
    private suspend fun performSoundDownload() {
        try {
            if (userPreferencesRepository.isRemoteSyncBlocked.first()) {
                android.util.Log.w(TAG, "Manual sound download blocked: session expired")
                sendSoundFailureBroadcast(SOUND_FAILURE_REASON_SESSION_EXPIRED)
                return
            }

            val downloadingIntent = Intent(ACTION_SOUND_DOWNLOADING).setPackage(packageName)
            sendBroadcast(downloadingIntent)

            val userId = userPreferencesRepository.userId.first()
            val token = userPreferencesRepository.accessToken.first()

            if (userId.isNullOrEmpty() || token.isNullOrEmpty()) {
                if (prepareBundledStartupSoundCacheForDemo()) {
                    return
                }

                android.util.Log.e(TAG, "Missing user credentials for sound download")
                sendSoundFailureBroadcast(SOUND_FAILURE_REASON_SESSION_EXPIRED)
                return
            }

            appLogger.log(TAG, "Stopping manual playback before refreshing server-managed sounds")
            SoundPlayerService.requestStopForServerRefresh(this)

            val progressIntent = Intent(ACTION_SOUND_PROGRESS).setPackage(packageName)
            progressIntent.putExtra(EXTRA_PROGRESS, 10)
            sendBroadcast(progressIntent)

            soundAssetManager.refreshManagedSound(1)
            progressIntent.putExtra(EXTRA_PROGRESS, 50)
            sendBroadcast(progressIntent)
            if (soundAssetManager.ensureSoundAvailable(1) == null) {
                if (userPreferencesRepository.isRemoteSyncBlocked.first()) {
                    sendSoundFailureBroadcast(SOUND_FAILURE_REASON_SESSION_EXPIRED)
                    return
                }
                throw Exception("Failed to refresh hello sound")
            }

            soundAssetManager.refreshManagedSound(2)
            progressIntent.putExtra(EXTRA_PROGRESS, 100)
            sendBroadcast(progressIntent)
            if (soundAssetManager.ensureSoundAvailable(2) == null) {
                if (userPreferencesRepository.isRemoteSyncBlocked.first()) {
                    sendSoundFailureBroadcast(SOUND_FAILURE_REASON_SESSION_EXPIRED)
                    return
                }
                throw Exception("Failed to refresh goodbye sound")
            }

            sendBroadcast(Intent(ACTION_SOUND_DOWNLOADED).setPackage(packageName))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Sound download failed", e)
            sendSoundFailureBroadcast()
        }
    }

    /**
     * Chuẩn bị sound bundled cho tài khoản demo khi chưa có credential server.
     */
    private suspend fun prepareBundledStartupSoundCacheForDemo(): Boolean {
        val demoExpirationTime = userPreferencesRepository.demoExpirationTime.first()
        if (demoExpirationTime == null || System.currentTimeMillis() >= demoExpirationTime) {
            return false
        }

        appLogger.log(TAG, "Preparing bundled demo startup sound cache")
        val ready = soundAssetManager.prepareBundledStartupSoundCache()
        if (ready) {
            sendBroadcast(Intent(ACTION_SOUND_DOWNLOADED).setPackage(packageName))
        } else {
            sendSoundFailureBroadcast(SOUND_FAILURE_REASON_PLAYBACK_UNAVAILABLE)
        }
        return true
    }

    /**
     * Gửi broadcast báo lỗi tải sound cho UI.
     */
    private fun sendSoundFailureBroadcast(reason: String? = null) {
        val intent = Intent(ACTION_SOUND_FAILED).setPackage(packageName)
        if (reason != null) {
            intent.putExtra(EXTRA_SOUND_FAILURE_REASON, reason)
        }
        sendBroadcast(intent)
    }

    /**
     * Đọc thông tin thiết bị một lần để heartbeat có thể push lại khi server online.
     */
    private suspend fun cacheDeviceInfo() {
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }

        cachedDeviceInfo =
            com.example.carchatbot.utils.DeviceUtils.getDeviceInfo(this, appVersion, null)
    }

    /**
     * Push thông tin thiết bị đã cache lên server nếu có.
     */
    private suspend fun pushDeviceInfo() {
        val deviceInfo = cachedDeviceInfo ?: run {
            cacheDeviceInfo()
            cachedDeviceInfo
        }

        deviceInfo?.let {
            iotStatusRepository.pushDeviceInfo(it)
        }
    }

    /**
     * Giữ CPU thức trong lúc download sound thủ công.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L)
            android.util.Log.d(TAG, "WakeLock acquired")
        }
    }

    /**
     * Release wake lock của CoreService nếu đang held.
     */
    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
                android.util.Log.d(TAG, "WakeLock released")
            } catch (error: RuntimeException) {
                android.util.Log.w(TAG, "WakeLock release ignored because lock was not held", error)
            }
        }
    }

    /**
     * Đọc overlay permission thô từ Android framework để so sánh với policy compat.
     */
    private fun readRawOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        return Settings.canDrawOverlays(this)
    }

    /**
     * CoreService không expose bound API; mọi điều phối đi qua start command và state.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Tạo notification bắt buộc cho foreground CoreService.
     */
    private fun buildNotification(): Notification {
        val channelId = "CoreServiceChannel"
        val channelName = "Core Service Channel"
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Car Chatbot Core")
            .setContentText("Keeping service alive...")
            .setSmallIcon(R.drawable.ic_notifications)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "CoreService"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START_SOUND_DOWNLOAD = "com.example.carchatbot.ACTION_START_SOUND_DOWNLOAD"
        const val ACTION_RECONCILE_RUNTIME_SERVICES = "com.example.carchatbot.ACTION_RECONCILE_RUNTIME_SERVICES"
        const val ACTION_SOUND_DOWNLOADING = "com.example.carchatbot.ACTION_SOUND_DOWNLOADING"
        const val ACTION_SOUND_PROGRESS = "com.example.carchatbot.ACTION_SOUND_PROGRESS"
        const val ACTION_SOUND_DOWNLOADED = "com.example.carchatbot.ACTION_SOUND_DOWNLOADED"
        const val ACTION_SOUND_FAILED = "com.example.carchatbot.ACTION_SOUND_FAILED"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SOUND_FAILURE_REASON = "extra_sound_failure_reason"
        const val SOUND_FAILURE_REASON_SESSION_EXPIRED = "session_expired"
        const val SOUND_FAILURE_REASON_PLAYBACK_UNAVAILABLE = "playback_unavailable"
        private const val POWER_CONNECTED_ACTION = "android.intent.action.ACTION_POWER_CONNECTED"
        private const val POWER_DISCONNECTED_ACTION = "android.intent.action.ACTION_POWER_DISCONNECTED"

        /**
         * Trả về true khi nút nổi nên chạy nhưng service marker cho biết nó chưa chạy.
         */
        fun shouldRestartFloatingButtonService(
            shouldStartFloatingButton: Boolean,
            floatingButtonRunning: Boolean
        ): Boolean = shouldStartFloatingButton && !floatingButtonRunning

        /**
         * Trả về true khi nút nổi đang chạy nhưng điều kiện chạy đã mất.
         */
        fun shouldStopFloatingButtonService(
            shouldStartFloatingButton: Boolean,
            floatingButtonRunning: Boolean
        ): Boolean = floatingButtonRunning && !shouldStartFloatingButton

        /**
         * Lọc các power event không được xem là BOOT signal.
         */
        fun shouldIgnoreExternalPowerAction(action: String?): Boolean =
            action == POWER_CONNECTED_ACTION || action == POWER_DISCONNECTED_ACTION
    }
}
