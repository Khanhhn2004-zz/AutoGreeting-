package com.example.carchatbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.carchatbot.R
import com.example.carchatbot.boot.BootPlaybackPhase
import com.example.carchatbot.boot.BootPlaybackService
import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.boot.BootSoundCacheRepository
import com.example.carchatbot.runtime.AppRuntimePolicies
import com.example.carchatbot.runtime.FloatingButtonPlaybackAction
import com.example.carchatbot.ui.main.MainActivity
import com.example.carchatbot.utils.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButtonView: View
    private lateinit var helloButton: ImageView
    private lateinit var goodbyeButton: ImageView
    private lateinit var removeView: ImageView
    private lateinit var removeParams: WindowManager.LayoutParams
    @Inject
    lateinit var bootPlaybackStateStore: BootPlaybackStateStore
    @Inject
    lateinit var appLogger: AppLogger
    private var isRemoveVisible = false
    private val serviceJob: Job = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isMoving = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val launchReason = intent?.getStringExtra(EXTRA_LAUNCH_REASON) ?: LAUNCH_REASON_UNSPECIFIED
        android.util.Log.d(TAG, "onStartCommand launchReason=$launchReason")
        appLogger.log(TAG, "Floating overlay start command received", "launchReason=$launchReason")
        startForeground(1001, createNotification())
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = "floating_button_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Floating Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Trợ lý đang chạy")
            .setContentText("Nút điều khiển đang hoạt động")
            .setSmallIcon(R.drawable.ic_play)
            .setOngoing(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        try {
            floatingButtonView = LayoutInflater.from(this).inflate(R.layout.floating_button, null)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 100

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.addView(floatingButtonView, params)
            setupRemoveView()
            serviceMarkedRunning = true
            appLogger.logService(TAG, "Service created")
            appLogger.log(TAG, "Floating overlay attached")

            helloButton = floatingButtonView.findViewById(R.id.floating_button_1)
            goodbyeButton = floatingButtonView.findViewById(R.id.floating_button_2)

            helloButton.setOnClickListener { handleSlotTap(helloButton, HELLO_SOUND_INDEX) }
            goodbyeButton.setOnClickListener { handleSlotTap(goodbyeButton, GOODBYE_SOUND_INDEX) }

            expireStaleStartupPlaybackOwnershipForOverlay()

            val touchListener = object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        
                        if (!isRemoveVisible) {
                            removeView.visibility = View.VISIBLE
                            isRemoveVisible = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isRemoveVisible) {
                             removeView.visibility = View.GONE
                             isRemoveVisible = false
                        }

                        if (isViewOverlapping(floatingButtonView, removeView)) {
                            stopSelf()
                            return true
                        }

                        if (!isMoving) {
                            v?.performClick()
                        }
                        isMoving = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY

                        if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                            isMoving = true
                        }

                        params.x = initialX + deltaX.toInt()
                        params.y = initialY + deltaY.toInt()
                        
                        try {
                            windowManager.updateViewLayout(floatingButtonView, params)
                        } catch (e: IllegalArgumentException) {
                        }
                        
                        if (isViewOverlapping(floatingButtonView, removeView)) {
                            removeView.alpha = 1.0f
                            removeView.scaleX = 1.2f
                            removeView.scaleY = 1.2f
                        } else {
                            removeView.alpha = 0.7f
                            removeView.scaleX = 1.0f
                            removeView.scaleY = 1.0f
                        }
                        
                        return true
                    }
                }
                return false
            }
            }

            floatingButtonView.setOnTouchListener(touchListener)
            helloButton.setOnTouchListener(touchListener)
            goodbyeButton.setOnTouchListener(touchListener)

            updateFloatingButtonStates()
            observeFloatingButtonState()
        } catch (e: Exception) {
            serviceMarkedRunning = false
            appLogger.logException(TAG, e, "Failed to attach floating overlay")
            stopSelf()
        }
    }

    private fun observeFloatingButtonState() {
        serviceScope.launch {
            combine(
                SoundPlayerState.snapshot,
                bootPlaybackStateStore.stateFlow
            ) { _, _ -> Unit }
                .collect {
                    updateFloatingButtonStates()
                }
        }
    }

    private fun updateFloatingButtonStates() {
        updateSlotButton(
            button = helloButton,
            soundIndex = HELLO_SOUND_INDEX,
            idleIconRes = R.drawable.ic_notifications,
            idleDescription = "Play hello sound"
        )
        updateSlotButton(
            button = goodbyeButton,
            soundIndex = GOODBYE_SOUND_INDEX,
            idleIconRes = R.drawable.ic_phone,
            idleDescription = "Play goodbye sound"
        )
    }

    private fun updateSlotButton(
        button: ImageView,
        soundIndex: Int,
        idleIconRes: Int,
        idleDescription: String
    ) {
        val slotState = resolveSlotControlState(soundIndex)
        val iconRes = when (slotState) {
            FloatingButtonSlotControlState.IDLE -> idleIconRes
            FloatingButtonSlotControlState.PREPARING,
            FloatingButtonSlotControlState.ACTIVE -> R.drawable.ic_stop_active
        }

        button.setImageResource(iconRes)
        button.contentDescription = when (slotState) {
            FloatingButtonSlotControlState.IDLE -> idleDescription
            FloatingButtonSlotControlState.PREPARING -> "${idleDescription.removePrefix("Play ")} is starting"
            FloatingButtonSlotControlState.ACTIVE -> "${idleDescription.removePrefix("Play ")} is playing"
        }
    }

    private fun resolveSlotControlState(soundIndex: Int): FloatingButtonSlotControlState {
        val soundPlayerSnapshot = SoundPlayerState.snapshot.value
        val activeRequest = soundPlayerSnapshot.activeRequest
        val currentSoundIndex = soundPlayerSnapshot.currentSoundIndex
        val playingRequest = soundPlayerSnapshot.playingRequest
        val isPlaying = soundPlayerSnapshot.isPlaying
        val bootState = bootPlaybackStateStore.readState()
        val startupPlaybackServiceRunning = BootPlaybackService.isServiceMarkedRunning()

        return when {
            soundIndex == BootSoundCacheRepository.STARTUP_SOUND_INDEX &&
                startupPlaybackServiceRunning &&
                bootState.phase == BootPlaybackPhase.PLAYING -> FloatingButtonSlotControlState.ACTIVE
            soundIndex == BootSoundCacheRepository.STARTUP_SOUND_INDEX &&
                startupPlaybackServiceRunning &&
                bootState.isActive() -> FloatingButtonSlotControlState.PREPARING
            isPlaying &&
                currentSoundIndex == soundIndex &&
                playingRequest?.soundIndex == soundIndex -> FloatingButtonSlotControlState.ACTIVE
            activeRequest?.soundIndex == soundIndex -> FloatingButtonSlotControlState.PREPARING
            else -> FloatingButtonSlotControlState.IDLE
        }
    }

    private fun resolveFloatingButtonPlaybackAction(tappedSoundIndex: Int): FloatingButtonPlaybackAction {
        return resolveFloatingButtonPlaybackPlan(tappedSoundIndex).action
    }

    private fun resolveFloatingButtonPlaybackPlan(tappedSoundIndex: Int): FloatingButtonPlaybackPlan {
        val soundPlayerSnapshot = SoundPlayerState.snapshot.value
        return FloatingButtonPlaybackResolver.resolvePlan(
            tappedSoundIndex = tappedSoundIndex,
            bootState = bootPlaybackStateStore.readState(),
            soundPlayerSnapshot = FloatingButtonSoundPlayerSnapshot(
                activeSoundIndex = soundPlayerSnapshot.activeRequest?.soundIndex,
                playingSoundIndex = soundPlayerSnapshot.playingRequest?.soundIndex,
                currentSoundIndex = soundPlayerSnapshot.currentSoundIndex,
                startupPlaybackSoundIndex = soundPlayerSnapshot.startupPlaybackSoundIndex,
                isPlaying = soundPlayerSnapshot.isPlaying
            ),
            startupPlaybackServiceRunning = BootPlaybackService.isServiceMarkedRunning()
        )
    }

    private fun expireStaleStartupPlaybackOwnershipForOverlay() {
        val bootState = bootPlaybackStateStore.readState()
        val sessionId = bootState.sessionId
        if (!bootState.isActive() || sessionId.isNullOrBlank()) {
            return
        }
        if (BootPlaybackService.isServiceMarkedRunning()) {
            return
        }

        val nowMillis = System.currentTimeMillis()
        val expiredState = bootPlaybackStateStore.expireStaleActiveSession(
            nowMillis = nowMillis,
            staleTimeoutMillis = AppRuntimePolicies.bootSessionStaleTimeoutMillis(),
            failureReason = "stale_startup_ownership"
        )
        if (
            expiredState.sessionId != sessionId ||
            expiredState.phase != BootPlaybackPhase.FAILED ||
            expiredState.lastFailureReason != "stale_startup_ownership"
        ) {
            return
        }

        SoundPlayerState.onStartupPlaybackStopped()
        appLogger.logBoot(
            TAG,
            "Expired stale startup playback ownership before rendering floating state",
            "sessionId=$sessionId"
        )
    }

    private fun handleSlotTap(button: ImageView, soundIndex: Int) {
        if (isMoving) {
            return
        }

        playSound(soundIndex)
        animateButton(button)
    }

    private fun setupRemoveView() {
        removeView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundResource(android.R.drawable.ic_delete)
            setBackgroundColor(android.graphics.Color.RED)
            setPadding(20, 20, 20, 20)
            visibility = View.GONE
        }
        
        val size = 150
        
        removeParams = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        
        removeParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        removeParams.y = 100
        
        val shape = android.graphics.drawable.GradientDrawable()
        shape.shape = android.graphics.drawable.GradientDrawable.OVAL
        shape.setColor(android.graphics.Color.RED)
        removeView.background = shape
        removeView.setColorFilter(android.graphics.Color.WHITE)
        
        windowManager.addView(removeView, removeParams)
    }

    private fun isViewOverlapping(firstView: View, secondView: View): Boolean {
        val firstPosition = IntArray(2)
        val secondPosition = IntArray(2)

        firstView.getLocationOnScreen(firstPosition)
        secondView.getLocationOnScreen(secondPosition)

        val rect1 = android.graphics.Rect(
            firstPosition[0],
            firstPosition[1],
            firstPosition[0] + firstView.width,
            firstPosition[1] + firstView.height
        )

        val rect2 = android.graphics.Rect(
            secondPosition[0],
            secondPosition[1],
            secondPosition[0] + secondView.width,
            secondPosition[1] + secondView.height
        )

        return android.graphics.Rect.intersects(rect1, rect2)
    }

    private fun playSound(soundIndex: Int) {
        val playbackPlan = resolveFloatingButtonPlaybackPlan(soundIndex)
        val action = playbackPlan.action
        if (shouldStopStartupPlaybackDirectly(soundIndex, playbackPlan)) {
            stopStartupPlaybackFromFloatingButton(soundIndex, playbackPlan)
            return
        }

        val intent = Intent(this, SoundPlayerService::class.java).apply {
            if (action == FloatingButtonPlaybackAction.STOP) {
                sendBroadcast(Intent(MainActivity.ACTION_CANCEL_AUTO_RETURN_HOME).setPackage(packageName))
                this.action = SoundPlayerService.ACTION_STOP_SOUND
            } else {
                putExtra(SoundPlayerService.EXTRA_SOUND_URI_INDEX, soundIndex)
                putExtra(SoundPlayerService.EXTRA_FORCE_REPLAY, true)
            }
        }
        if (action == FloatingButtonPlaybackAction.STOP) {
            startService(intent)
        } else {
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun shouldStopStartupPlaybackDirectly(
        soundIndex: Int,
        playbackPlan: FloatingButtonPlaybackPlan
    ): Boolean {
        return playbackPlan.action == FloatingButtonPlaybackAction.STOP &&
            soundIndex == BootSoundCacheRepository.STARTUP_SOUND_INDEX &&
            playbackPlan.stopBootPlayback
    }

    private fun stopStartupPlaybackFromFloatingButton(
        soundIndex: Int,
        playbackPlan: FloatingButtonPlaybackPlan
    ) {
        sendBroadcast(Intent(MainActivity.ACTION_CANCEL_AUTO_RETURN_HOME).setPackage(packageName))
        if (playbackPlan.stopBootPlayback) {
            BootPlaybackService.requestStopForManualPreemption(
                context = this,
                reason = "floating_button_stop",
                stateStore = bootPlaybackStateStore
            )
        }
        if (playbackPlan.stopSoundPlayer) {
            startService(
                Intent(this, SoundPlayerService::class.java).apply {
                    action = SoundPlayerService.ACTION_STOP_SOUND
                    putExtra(SoundPlayerService.EXTRA_SOUND_URI_INDEX, soundIndex)
                }
            )
        }
    }

    private fun animateButton(button: ImageView) {
        button.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(100)
            .withEndAction {
                button.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val reconcileIntent = Intent(this, CoreService::class.java).apply {
            action = CoreService.ACTION_RECONCILE_RUNTIME_SERVICES
        }
        appLogger.log(TAG, "Task removed; delegating overlay recovery to CoreService reconcile")
        ContextCompat.startForegroundService(this, reconcileIntent)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceMarkedRunning = false
        appLogger.logService(TAG, "Service destroyed")
        try {
            if (::floatingButtonView.isInitialized) windowManager.removeView(floatingButtonView)
            if (::removeView.isInitialized) windowManager.removeView(removeView)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "View cleanup failed in onDestroy", e)
            appLogger.logException(TAG, e, "View cleanup failed in onDestroy")
        }
        serviceJob.cancel()
    }

    private enum class FloatingButtonSlotControlState {
        IDLE,
        PREPARING,
        ACTIVE
    }

    companion object {
        private const val TAG = "FloatingButtonService"
        private const val HELLO_SOUND_INDEX = 1
        private const val GOODBYE_SOUND_INDEX = 2
        const val EXTRA_LAUNCH_REASON = "com.example.carchatbot.service.EXTRA_LAUNCH_REASON"
        const val LAUNCH_REASON_UNSPECIFIED = "unspecified"
        const val LAUNCH_REASON_BOOT_RESTORE = "boot_restore"
        const val LAUNCH_REASON_RUNTIME_RECONCILE = "runtime_reconcile"
        const val LAUNCH_REASON_TASK_REMOVED_RESTART = "task_removed_restart"

        @Volatile
        private var serviceMarkedRunning = false

        fun isServiceMarkedRunning(): Boolean = serviceMarkedRunning

        fun createStartIntent(
            context: android.content.Context,
            launchReason: String
        ): Intent {
            return Intent(context, FloatingButtonService::class.java).apply {
                putExtra(EXTRA_LAUNCH_REASON, launchReason)
            }
        }
    }
}
