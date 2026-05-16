package com.example.carchatbot.boot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.carchatbot.R
import com.example.carchatbot.audio.OneShotCachedAudioPlayer
import com.example.carchatbot.service.SoundPlayerService
import com.example.carchatbot.service.SoundPlayerState
import com.example.carchatbot.ui.main.MainActivity
import com.example.carchatbot.utils.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service sở hữu một session phát nhạc startup của nhánh BOOT.
 *
 * Service này là neo lifecycle mà playback kiểu MacroDroid cần trên Android
 * box: xác thực session đã claim, chỉ đọc boot cache local, giữ one-shot player
 * cho tới khi hoàn tất hoặc lỗi, rồi ghi terminal state để các tín hiệu BOOT
 * trùng lặp về sau có thể kiểm tra an toàn.
 */
@AndroidEntryPoint
class BootPlaybackService : Service() {

    @Inject
    lateinit var stateStore: BootPlaybackStateStore

    @Inject
    lateinit var cacheRepository: BootSoundCacheRepository

    @Inject
    lateinit var audioPlayer: OneShotCachedAudioPlayer

    @Inject
    lateinit var soundNotifier: BootSoundNotifier

    @Inject
    lateinit var appLogger: AppLogger

    private val serviceJob: Job = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private var activeSessionId: String? = null

    /**
     * Entry point của foreground service khi Android start hoặc restart service.
     *
     * Luồng chính là xác thực session ownership, đưa service vào foreground,
     * đọc boot cache local rồi giữ player sống tới khi callback completed/failure.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceMarkedRunning = true
        if (intent?.action == ACTION_STOP_FOR_MANUAL_PREEMPTION) {
            handleManualPreemptionStop(intent)
            return START_NOT_STICKY
        }

        val state = stateStore.readState()
        val requestedSessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: state.sessionId
        val requestedWindowId = intent?.getLongExtra(EXTRA_STARTUP_WINDOW_ID, -1L)
            ?.takeIf { it >= 0L }

        if (!verifySessionOwnership(state, requestedSessionId, requestedWindowId)) {
            serviceMarkedRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        val sessionId = requestedSessionId ?: run {
            serviceMarkedRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startForegroundSafely(sessionId, state.startupWindowId)) {
            return START_NOT_STICKY
        }

        activeSessionId = sessionId

        serviceScope.launch {
            appLogger.logBoot(
                "BootPlaybackService",
                "Boot playback cache lookup started",
                "sessionId=$sessionId startupWindowId=${state.startupWindowId}"
            )
            val readyBootSound = cacheRepository.getReadyBootSound()
            if (readyBootSound == null) {
                val cacheReadiness = cacheRepository.inspectReadiness()
                SoundPlayerState.onStartupPlaybackStopped()
                stateStore.markNoCache(sessionId, System.currentTimeMillis())
                appLogger.logBoot(
                    "BootPlaybackService",
                    "Boot playback cache missing",
                    "sessionId=$sessionId startupWindowId=${state.startupWindowId} ${cacheReadiness.toLogDetails()}"
                )
                state.startupWindowId?.let {
                    soundNotifier.notifyNoCache(it)
                }
                sendBroadcast(Intent(MainActivity.ACTION_CANCEL_AUTO_RETURN_HOME).setPackage(packageName))
                stopSelf()
                return@launch
            }

            soundNotifier.clearNoCacheNotification()
            SoundPlayerService.requestStopForStartupTakeover(this@BootPlaybackService)
            SoundPlayerState.onStartupPlaybackStopped()
            stateStore.markStarting(sessionId, System.currentTimeMillis())
            audioPlayer.play(
                cachedFile = readyBootSound.cacheFile,
                onStarted = {
                    if (stateStore.markPlayingIfCurrent(sessionId, System.currentTimeMillis())) {
                        SoundPlayerState.onStartupPlaybackStarted(readyBootSound.soundIndex)
                        appLogger.logBoot(
                            "BootPlaybackService",
                            "Boot playback audio started",
                            "sessionId=$sessionId file=${readyBootSound.cacheFile.name}"
                        )
                    } else {
                        appLogger.logBoot(
                            "BootPlaybackService",
                            "Ignored stale boot playback audio start",
                            "sessionId=$sessionId file=${readyBootSound.cacheFile.name}"
                        )
                        audioPlayer.release()
                        stopSelf()
                    }
                },
                onCompleted = {
                    SoundPlayerState.onStartupPlaybackStopped()
                    if (stateStore.markCompletedIfCurrent(sessionId, System.currentTimeMillis())) {
                        appLogger.logBoot(
                            "BootPlaybackService",
                            "Boot playback audio completed",
                            "sessionId=$sessionId file=${readyBootSound.cacheFile.name}"
                        )
                        sendBroadcast(Intent(SoundPlayerService.ACTION_PLAYBACK_FINISHED).setPackage(packageName))
                    } else {
                        appLogger.logBoot(
                            "BootPlaybackService",
                            "Ignored stale boot playback audio completion",
                            "sessionId=$sessionId file=${readyBootSound.cacheFile.name}"
                        )
                    }
                    stopSelf()
                },
                onFailure = { throwable ->
                    SoundPlayerState.onStartupPlaybackStopped()
                    val updated = stateStore.markFailedIfCurrent(
                        sessionId = sessionId,
                        failureReason = throwable.message ?: throwable::class.java.simpleName,
                        nowMillis = System.currentTimeMillis()
                    )
                    if (updated) {
                        appLogger.logBoot(
                            "BootPlaybackService",
                            "Boot playback audio failed",
                            "sessionId=$sessionId file=${readyBootSound.cacheFile.name} error=${throwable.javaClass.simpleName}:${throwable.message}"
                        )
                        sendBroadcast(Intent(MainActivity.ACTION_CANCEL_AUTO_RETURN_HOME).setPackage(packageName))
                    } else {
                        appLogger.logBoot(
                            "BootPlaybackService",
                            "Ignored stale boot playback audio failure",
                            "sessionId=$sessionId file=${readyBootSound.cacheFile.name} error=${throwable.javaClass.simpleName}:${throwable.message}"
                        )
                    }
                    stopSelf()
                }
            )
        }

        return START_STICKY
    }

    /**
     * Dọn toàn bộ tài nguyên mà service sở hữu khi Android hoặc chính service stop.
     *
     * Việc release player, foreground notification và marker running ở đây giúp
     * các đường recovery sau đó phân biệt service thật sự còn sống hay chỉ còn
     * state cũ trên disk.
     */
    override fun onDestroy() {
        super.onDestroy()
        serviceMarkedRunning = false
        SoundPlayerState.onStartupPlaybackStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        audioPlayer.release()
        serviceJob.cancel()
    }

    /**
     * Service này không hỗ trợ bind vì BOOT playback được điều khiển bằng intent
     * và state bền vững, không phải bằng client binding từ UI.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Xác nhận lần start service này thuộc về BOOT session đang được claim.
     *
     * Guard này ngăn stale intent, duplicate broadcast hoặc manual preemption
     * stop vô tình start audio nằm ngoài quyết định của arbiter.
     */
    private fun verifySessionOwnership(
        state: BootPlaybackState,
        requestedSessionId: String?,
        requestedWindowId: Long?
    ): Boolean {
        if (!state.isActive()) {
            return false
        }

        if (requestedSessionId != null && state.sessionId != requestedSessionId) {
            return false
        }

        if (requestedWindowId != null && state.startupWindowId != requestedWindowId) {
            return false
        }

        return !state.sessionId.isNullOrBlank()
    }

    /**
     * Đưa service vào foreground trước khi lookup cache hoặc phát nhạc.
     *
     * Android có thể kill hoặc từ chối background work trong lúc boot. Lỗi tại
     * đây được ghi là lỗi session thay vì bị che thành "no cache" hoặc
     * "no startup signal".
     */
    private fun startForegroundSafely(
        sessionId: String,
        startupWindowId: Long?
    ): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification("Checking startup sound"))
            appLogger.logBoot("BootPlaybackService", "Boot playback foreground started")
            true
        } catch (error: RuntimeException) {
            serviceMarkedRunning = false
            SoundPlayerState.onStartupPlaybackStopped()
            val updated = stateStore.markFailedIfCurrent(
                sessionId = sessionId,
                failureReason = "foreground_service_start_failed:${error.javaClass.simpleName}",
                nowMillis = System.currentTimeMillis()
            )
            appLogger.logBoot(
                "BootPlaybackService",
                "Boot playback foreground start failed",
                "sessionId=$sessionId startupWindowId=$startupWindowId updated=$updated error=${error.javaClass.simpleName}:${error.message}"
            )
            sendBroadcast(Intent(MainActivity.ACTION_CANCEL_AUTO_RETURN_HOME).setPackage(packageName))
            stopSelf()
            false
        }
    }

    /**
     * Xử lý stop do manual playback hoặc user action cần preempt startup playback.
     *
     * Stop path này ghi terminal state trước khi release player để diagnostics
     * biết playback bị dừng chủ động, không phải lỗi cache hoặc lỗi service.
     */
    private fun handleManualPreemptionStop(intent: Intent) {
        serviceScope.launch {
            val stopReason = intent.getStringExtra(EXTRA_STOP_REASON) ?: "manual_preempted"
            applyManualPreemptionFailure(
                stateStore = stateStore,
                reason = stopReason
            )

            sendBroadcast(Intent(MainActivity.ACTION_CANCEL_AUTO_RETURN_HOME).setPackage(packageName))
            audioPlayer.release()
            stopSelf()
        }
    }

    /**
     * Tạo notification bắt buộc cho foreground service phát nhạc BOOT.
     *
     * Notification được đặt silent/ongoing để thỏa yêu cầu hệ thống mà không tạo
     * thêm tiếng hoặc tương tác gây nhiễu trong lúc xe vừa khởi động.
     */
    private fun buildNotification(contentText: String): Notification {
        val channelId = CHANNEL_ID
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(channelId) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Boot sound playback",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Car startup sound")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notifications)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "boot_sound_playback"
        const val ACTION_STOP_FOR_MANUAL_PREEMPTION =
            "com.example.carchatbot.boot.ACTION_STOP_FOR_MANUAL_PREEMPTION"
        const val EXTRA_SESSION_ID = "com.example.carchatbot.boot.EXTRA_SESSION_ID"
        const val EXTRA_STARTUP_WINDOW_ID = "com.example.carchatbot.boot.EXTRA_STARTUP_WINDOW_ID"
        const val EXTRA_STOP_REASON = "com.example.carchatbot.boot.EXTRA_STOP_REASON"
        private const val TAG = "BootPlaybackService"
        private const val NOTIFICATION_ID = 0x5B006

        @Volatile
        private var serviceMarkedRunning = false

        /**
         * Tạo intent start service kèm session id và startup window id đã được arbiter claim.
         *
         * Hai extra này là hàng rào để service reject stale intent hoặc duplicate
         * intent không còn khớp với state hiện tại.
         */
        fun createIntent(
            context: android.content.Context,
            state: BootPlaybackState
        ): Intent {
            return Intent(context, BootPlaybackService::class.java).apply {
                putExtra(EXTRA_SESSION_ID, state.sessionId)
                state.startupWindowId?.let { putExtra(EXTRA_STARTUP_WINDOW_ID, it) }
            }
        }

        /**
         * API tương thích cũ cho code vẫn truyền trực tiếp arbiter decision.
         *
         * Hàm này luôn dùng direct execution plan legacy và nên được thay bằng
         * [startForExecution] khi caller đã có [StartupExecutionPlan].
         */
        fun startForDecision(
            context: android.content.Context,
            decision: BootSoundArbiterDecision
        ): Boolean {
            return startForExecution(
                context = context,
                decision = decision,
                executionPlan = StartupExecutionPlan.direct("legacy_direct_start")
            )
        }

        /**
         * Start BOOT playback service chỉ khi execution plan cho phép launch
         * foreground service ngay lập tức.
         *
         * Method trả về `false` thay vì throw để receiver và các đường recovery
         * có thể log việc start bị từ chối mà không làm crash process.
         */
        fun startForExecution(
            context: android.content.Context,
            decision: BootSoundArbiterDecision,
            executionPlan: StartupExecutionPlan
        ): Boolean {
            if (!shouldStartForegroundService(executionPlan.strategy)) {
                return false
            }

            val appContext = context.applicationContext
            return try {
                ContextCompat.startForegroundService(appContext, createIntent(appContext, decision.state))
                true
            } catch (error: RuntimeException) {
                decision.state.sessionId?.let { sessionId ->
                    BootPlaybackStateStore(BootStorageContextProvider(appContext)).markFailedIfCurrent(
                        sessionId = sessionId,
                        failureReason = "foreground_service_start_rejected:${error.javaClass.simpleName}",
                        nowMillis = System.currentTimeMillis()
                    )
                }
                android.util.Log.w(TAG, "Boot playback foreground service start rejected", error)
                false
            }
        }

        /**
         * Yêu cầu dừng startup playback khi manual playback cần giành quyền.
         *
         * Hàm này không start một service mới; nó ghi terminal state rồi stop
         * service hiện có để tránh tạo thêm một foreground-service launch không cần thiết.
         */
        fun requestStopForManualPreemption(
            context: android.content.Context,
            reason: String,
            stateStore: BootPlaybackStateStore? = null
        ) {
            val appContext = context.applicationContext
            applyManualPreemptionFailure(
                stateStore = stateStore ?: BootPlaybackStateStore(BootStorageContextProvider(appContext)),
                reason = reason
            )
            appContext.sendBroadcast(
                Intent(MainActivity.ACTION_CANCEL_AUTO_RETURN_HOME).setPackage(appContext.packageName)
            )
            appContext.stopService(Intent(appContext, BootPlaybackService::class.java))
        }

        /**
         * Kiểm tra execution strategy có cho phép start foreground service ngay không.
         */
        fun shouldStartForegroundService(strategy: StartupExecutionStrategy): Boolean {
            return strategy == StartupExecutionStrategy.START_FOREGROUND_SERVICE_NOW
        }

        /**
         * Cho biết service hiện tại có đang được đánh dấu là chạy trong process này không.
         *
         * Đây là tín hiệu phụ cho recovery path; nguồn sự thật cuối cùng vẫn là
         * [BootPlaybackStateStore] vì process có thể bị kill.
         */
        fun isServiceMarkedRunning(): Boolean = serviceMarkedRunning

        /**
         * Hook dành cho test để mô phỏng service còn sống hoặc đã chết.
         */
        internal fun setServiceMarkedRunningForTest(running: Boolean) {
            serviceMarkedRunning = running
        }

        /**
         * Ghi nhận manual preemption lên session hiện tại nếu session đang active.
         *
         * Hàm trả lại state cũ nếu không có active session để caller có thể gọi
         * idempotent mà không làm hỏng diagnostics.
         */
        fun applyManualPreemptionFailure(
            stateStore: BootPlaybackStateStore,
            reason: String,
            nowMillis: Long = System.currentTimeMillis()
        ): BootPlaybackState {
            val state = stateStore.readState()
            val sessionId = state.sessionId
            if (!state.isActive() || sessionId.isNullOrBlank()) {
                return state
            }

            return stateStore.markManuallyStopped(
                sessionId = sessionId,
                reason = reason,
                nowMillis = nowMillis
            )
        }
    }
}
