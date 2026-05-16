package com.example.carchatbot.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.carchatbot.boot.BootPlaybackService
import com.example.carchatbot.boot.BootSoundArbiterAction
import com.example.carchatbot.boot.BootSoundCacheRepository
import com.example.carchatbot.boot.StartupCompatCoordinator
import com.example.carchatbot.boot.StartupMode
import com.example.carchatbot.boot.StartupTriggerRouter
import com.example.carchatbot.runtime.AppRuntimePolicies
import com.example.carchatbot.service.CoreService
import com.example.carchatbot.service.FloatingButtonService
import com.example.carchatbot.service.SoundPlayerService
import com.example.carchatbot.ui.common.theme.CarBootupSoundTheme
import com.example.carchatbot.ui.login.LoginScreen
import com.example.carchatbot.ui.login.LoginViewModel
import com.example.carchatbot.ui.nav.NavViewModel
import com.example.carchatbot.ui.permission.PermissionScreen
import com.example.carchatbot.utils.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Activity chính của app và điểm UI recovery cho startup playback.
 *
 * Activity này không trực tiếp phát audio. Nó chỉ điều phối permission/login UI,
 * khởi động CoreService, và khi app visible thì hỏi startup coordinator xem có
 * pending BOOT window hoặc `APP_AUTO_OPEN` decision nào cần thực thi không.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var startupCompatCoordinator: StartupCompatCoordinator

    @Inject
    lateinit var bootSoundCacheRepository: BootSoundCacheRepository

    @Inject
    lateinit var appLogger: AppLogger

    private lateinit var navViewModel: NavViewModel
    private val mainViewModel: MainViewModel by viewModels()
    private val hasPermissionsState = mutableStateOf(false)
    private var allowAutoplayForThisLaunch = false
    private var pendingAutoReturnHome = false
    private var startupPlaybackDispatchInFlight = false
    private var startupPlaybackDispatchedModeForThisLaunch: StartupMode? = null

    private val playbackFinishedReceiver = object : BroadcastReceiver() {
        /**
         * Nhận tín hiệu playback finished để đưa app về launcher nếu lần phát đó do startup tạo ra.
         */
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SoundPlayerService.ACTION_PLAYBACK_FINISHED && pendingAutoReturnHome) {
                pendingAutoReturnHome = false
                returnToLauncher()
            }
        }
    }

    private val cancelAutoReturnHomeReceiver = object : BroadcastReceiver() {
        /**
         * Hủy auto-return-home khi playback startup bị dừng hoặc bị thay thế.
         */
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CANCEL_AUTO_RETURN_HOME) {
                pendingAutoReturnHome = false
            }
        }
    }

    /**
     * Khởi tạo UI, receiver nội bộ và các gate startup playback theo lifecycle activity.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navViewModel = ViewModelProvider(this)[NavViewModel::class.java]
        hasPermissionsState.value = checkPermissions()
        allowAutoplayForThisLaunch = savedInstanceState == null
        startupPlaybackDispatchInFlight = false
        startupPlaybackDispatchedModeForThisLaunch = null

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            playbackFinishedReceiver,
            IntentFilter(SoundPlayerService.ACTION_PLAYBACK_FINISHED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            cancelAutoReturnHomeReceiver,
            IntentFilter(ACTION_CANCEL_AUTO_RETURN_HOME),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            CarBootupSoundTheme {
                val navController = rememberNavController()
                val isLoggedIn by navViewModel.isLoggedIn.collectAsState(initial = null)
                val hasPermissions by hasPermissionsState
                val startDestination = AppRuntimePolicies.resolveStartDestination(hasPermissions, isLoggedIn)

                LaunchedEffect(Unit) {
                    mainViewModel.navigateToLoginEvent.collect {
                        stopService(Intent(this@MainActivity, FloatingButtonService::class.java))
                        navController.navigate("login") {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }

                LaunchedEffect(isLoggedIn, hasPermissions) {
                    val sessionLoggedIn = isLoggedIn ?: return@LaunchedEffect
                    if (hasPermissions) {
                        val currentRoute = navController.currentBackStackEntry?.destination?.route
                        if (sessionLoggedIn) {
                            if (currentRoute == "login" || currentRoute == "permission") {
                                navController.navigate("main") {
                                    popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                            kotlinx.coroutines.delay(500)
                            val coreIntent = Intent(this@MainActivity, CoreService::class.java)
                            androidx.core.content.ContextCompat.startForegroundService(this@MainActivity, coreIntent)
                        } else if (currentRoute == "main" || currentRoute == "permission") {
                            navController.navigate("login") {
                                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                launchSingleTop = true
                            }
                            stopService(Intent(this@MainActivity, FloatingButtonService::class.java))
                        }
                    }
                }

                val startupMode by mainViewModel.startupMode.collectAsState(initial = StartupMode.DEFAULT)
                var soundPlayedMode by remember { mutableStateOf<StartupMode?>(null) }

                LaunchedEffect(startupMode, isLoggedIn, hasPermissions) {
                    val startupDispatchAlreadyConsumed =
                        soundPlayedMode == startupMode ||
                            startupPlaybackDispatchInFlight ||
                            startupPlaybackDispatchedModeForThisLaunch == startupMode
                    if (startupPlaybackDispatchInFlight) {
                        Log.d(TAG, "Skipping startup playback evaluation because a previous dispatch is still in flight")
                        return@LaunchedEffect
                    }
                    startupPlaybackDispatchInFlight = true
                    try {
                        val recoveredPendingBootPlayback = if (
                            StartupTriggerRouter.allowsBootCompleted(startupMode) &&
                            hasPermissions &&
                            isLoggedIn == true
                        ) {
                            requestPendingBootRecoveryFromActivity(startupMode)
                        } else {
                            false
                        }
                        if (recoveredPendingBootPlayback) {
                            pendingAutoReturnHome = true
                            soundPlayedMode = startupMode
                            startupPlaybackDispatchedModeForThisLaunch = startupMode
                            return@LaunchedEffect
                        }

                        val shouldAttemptAutoplay = AppRuntimePolicies.shouldAutoPlayOnActivityOpen(
                            hasPermissions = hasPermissions,
                            isLoggedIn = isLoggedIn,
                            playOnOpenEnabled = StartupTriggerRouter.allowsAppAutoOpen(startupMode),
                            alreadyPlayedInProcess = startupDispatchAlreadyConsumed,
                            launchIntentFlags = intent?.flags ?: 0,
                            hasSavedInstanceState = !allowAutoplayForThisLaunch,
                            deviceBootAgeMillis = SystemClock.elapsedRealtime()
                        )
                        Log.d(
                            TAG,
                            "App-open autoplay gate: startupMode=$startupMode isLoggedIn=$isLoggedIn hasPermissions=$hasPermissions allowAutoplayForThisLaunch=$allowAutoplayForThisLaunch soundPlayedMode=$soundPlayedMode dispatchedMode=$startupPlaybackDispatchedModeForThisLaunch flags=${intent?.flags ?: 0} shouldAttempt=$shouldAttemptAutoplay"
                        )
                        val shouldAttemptBootVisibleStartupRecovery =
                            AppRuntimePolicies.shouldAttemptBootModeVisibleStartupRecovery(
                                hasPermissions = hasPermissions,
                                isLoggedIn = isLoggedIn,
                                bootModeEnabled = StartupTriggerRouter.allowsBootCompleted(startupMode),
                                alreadyPlayedInProcess = startupDispatchAlreadyConsumed,
                                launchIntentFlags = intent?.flags ?: 0,
                                hasSavedInstanceState = !allowAutoplayForThisLaunch,
                                deviceBootAgeMillis = SystemClock.elapsedRealtime()
                            )
                        Log.d(
                            TAG,
                            "Boot-visible startup gate: startupMode=$startupMode isLoggedIn=$isLoggedIn hasPermissions=$hasPermissions allowAutoplayForThisLaunch=$allowAutoplayForThisLaunch soundPlayedMode=$soundPlayedMode dispatchedMode=$startupPlaybackDispatchedModeForThisLaunch flags=${intent?.flags ?: 0} shouldAttempt=$shouldAttemptBootVisibleStartupRecovery"
                        )
                        val shouldAttemptStartupPlayback = shouldAttemptAutoplay || shouldAttemptBootVisibleStartupRecovery
                        if (shouldAttemptStartupPlayback) {
                            startupPlaybackDispatchedModeForThisLaunch = startupMode
                            val startedPlayback = requestStartupPlaybackFromActivity(
                                startupMode = startupMode,
                                hasPermissions = hasPermissions,
                                isLoggedIn = isLoggedIn
                            )
                            pendingAutoReturnHome = startedPlayback
                            soundPlayedMode = if (startedPlayback) startupMode else null
                            if (!startedPlayback) {
                                if (shouldAttemptAutoplay) {
                                    Log.d(TAG, "Skipping app-open autoplay because no playable source became available")
                                } else {
                                    Log.d(TAG, "Skipping boot-visible startup recovery because no playable source became available")
                                }
                            }
                        } else if (!StartupTriggerRouter.allowsAppAutoOpen(startupMode) || isLoggedIn != true || !allowAutoplayForThisLaunch) {
                            pendingAutoReturnHome = false
                            if (!StartupTriggerRouter.allowsAppAutoOpen(startupMode)) {
                                soundPlayedMode = null
                                startupPlaybackDispatchedModeForThisLaunch = null
                            }
                        }
                    } finally {
                        startupPlaybackDispatchInFlight = false
                    }
                }

                if (startDestination == null) {
                    androidx.compose.foundation.layout.Box(
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                } else {
                    NavHost(navController = navController, startDestination = startDestination) {
                        composable("permission") {
                            PermissionScreen(
                                onPermissionsGranted = {
                                    hasPermissionsState.value = checkPermissions()
                                }
                            )
                        }
                        composable("login") {
                            val loginViewModel: LoginViewModel = hiltViewModel()
                            LoginScreen(
                                loginViewModel = loginViewModel,
                                onLoginSuccess = {
                                    navController.navigate("main") {
                                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }
                        composable("main") {
                            MainScreen(viewModel = mainViewModel)
                        }
                    }
                }
            }
        }

        installProblematicHoverWorkaround()
    }

    /**
     * Kiểm tra các permission runtime tối thiểu để app có thể chạy overlay/startup flow.
     */
    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Refresh permission state khi user quay lại app từ màn hình cấp quyền.
     */
    override fun onResume() {
        super.onResume()
        hasPermissionsState.value = checkPermissions()
        mainViewModel.fetchIotStatus()
    }

    /**
     * Lọc một số hover/mouse event gây lỗi trên head unit/Compose trước khi UI xử lý.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            AppRuntimePolicies.shouldSuppressProblematicComposeHoverEvent(
                sdkInt = Build.VERSION.SDK_INT,
                actionMasked = event.actionMasked,
                isMouseLikePointer = isMouseLikePointerInput(event)
            )
        ) {
            val toolType = if (event.pointerCount > 0) event.getToolType(0) else -1
            Log.w(
                TAG,
                "Suppressing problematic hover event before Compose dispatch: action=${event.actionMasked} source=${event.source} toolType=$toolType"
            )
            appLogger.logBootNoisy(
                TAG,
                "Suppressed problematic hover event before Compose dispatch",
                "action=${event.actionMasked} source=${event.source} toolType=$toolType sdk=${Build.VERSION.SDK_INT}",
                key = "main_activity_hover_dispatch_suppressed"
            )
            return true
        }

        return try {
            super.dispatchGenericMotionEvent(event)
        } catch (error: IllegalStateException) {
            if (!AppRuntimePolicies.isProblematicComposeHoverCrash(error)) {
                throw error
            }

            Log.w(TAG, "Recovered from Compose hover crash", error)
            appLogger.logBoot(
                TAG,
                "Recovered from Compose hover crash",
                "message=${error.message} action=${event.actionMasked} source=${event.source} sdk=${Build.VERSION.SDK_INT}"
            )
            true
        }
    }

    /**
     * Gỡ receiver nội bộ khi activity bị hủy.
     */
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(playbackFinishedReceiver)
        unregisterReceiver(cancelAutoReturnHomeReceiver)
    }

    /**
     * Đưa task về launcher sau khi startup playback hoàn tất.
     */
    private fun returnToLauncher() {
        if (!moveTaskToBack(true)) {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }
        finish()
    }

    /**
     * Thử consume BOOT window đang pending khi MainActivity đã visible.
     *
     * Đây là recovery path cho BOOT mode, không phải `APP_AUTO_OPEN`: activity chỉ
     * giúp hoàn tất window đã được receiver/runtime arm trước đó.
     */
    private suspend fun requestPendingBootRecoveryFromActivity(
        startupMode: StartupMode
    ): Boolean = withContext(NonCancellable) {
        val startupResult = withContext(Dispatchers.IO) {
            startupCompatCoordinator.handleProcessVisibleSignal(startupMode)
        } ?: return@withContext false
        if (!startupResult.usedRecoveryPath) {
            return@withContext false
        }
        val decision = startupResult.playbackDecision ?: return@withContext false
        Log.d(TAG, "Consuming pending startup window through process-visible recovery")
        appLogger.logBoot(
            TAG,
            "Process-visible pending boot recovery decision",
            "action=${decision.action} strategy=${startupResult.executionPlan.strategy} reason=${startupResult.executionPlan.reason} sessionId=${decision.state.sessionId} startupWindowId=${decision.state.startupWindowId}"
        )
        executeStartupPlaybackDecision(decision, startupResult.executionPlan)
    }

    /**
     * Điều phối startup playback khi activity visible.
     *
     * Hàm này giữ thứ tự ưu tiên: explicit `APP_AUTO_OPEN` nếu mode cho phép,
     * BOOT visible recovery nếu BOOT mode còn đủ bằng chứng, và compat fallback
     * khi thiết bị không gửi receiver đáng tin cậy.
     */
    private suspend fun requestStartupPlaybackFromActivity(
        startupMode: StartupMode,
        hasPermissions: Boolean,
        isLoggedIn: Boolean?
    ): Boolean = withContext(NonCancellable) {
        val startupResult = withContext(Dispatchers.IO) {
            startupCompatCoordinator.handleProcessVisibleSignal(startupMode)
        }
        if (startupResult != null) {
            val decision = startupResult.playbackDecision ?: return@withContext false
            if (startupResult.usedRecoveryPath) {
                Log.d(TAG, "Consuming pending startup window through process-visible recovery")
                appLogger.logBoot(
                    TAG,
                    "Process-visible pending boot recovery decision",
                    "action=${decision.action} strategy=${startupResult.executionPlan.strategy} reason=${startupResult.executionPlan.reason} sessionId=${decision.state.sessionId} startupWindowId=${decision.state.startupWindowId}"
                )
            }
            return@withContext executeStartupPlaybackDecision(decision, startupResult.executionPlan)
        }

        val bootLikeCompatEvaluation = withContext(Dispatchers.IO) {
            val compatSnapshot = startupCompatCoordinator.readBootLikeVisibleCompatSnapshot()
            val hasPlayableSource = bootSoundCacheRepository.getReadyBootSound() != null
            AppRuntimePolicies.evaluateBootLikeVisibleCompatStartup(
                startupMode = startupMode,
                hasPermissions = hasPermissions,
                isLoggedIn = isLoggedIn,
                launchIntentFlags = intent?.flags ?: 0,
                hasSavedInstanceState = !allowAutoplayForThisLaunch,
                deviceBootAgeMillis = SystemClock.elapsedRealtime(),
                startupWindowArmed = compatSnapshot.startupWindowArmed,
                bootReceiverSeenInCurrentCandidateWindow =
                    compatSnapshot.bootReceiverSeenInCurrentCandidateWindow,
                bootPlaybackOwnershipPresent = compatSnapshot.bootPlaybackOwnershipPresent,
                hasPlayableSource = hasPlayableSource
            )
        }
        if (!bootLikeCompatEvaluation.matches) {
            Log.d(
                TAG,
                "Compat startup skipped because signature incomplete: ${bootLikeCompatEvaluation.reason}"
            )
            appLogger.logBoot(
                TAG,
                "Compat startup skipped because signature incomplete",
                "reason=${bootLikeCompatEvaluation.reason} bootReceiverSeen=${bootLikeCompatEvaluation.bootReceiverSeenInCurrentCandidateWindow} elapsedRealtime=${bootLikeCompatEvaluation.visibleLaunchElapsedRealtimeMillis} fresh=${bootLikeCompatEvaluation.visibleLaunchFresh}"
            )
            return@withContext false
        }

        Log.d(TAG, "Compat startup candidate detected from fresh visible launch")
        appLogger.logBoot(
            TAG,
            "Compat startup candidate detected from fresh visible launch",
            "reason=${bootLikeCompatEvaluation.reason} bootReceiverSeen=${bootLikeCompatEvaluation.bootReceiverSeenInCurrentCandidateWindow} elapsedRealtime=${bootLikeCompatEvaluation.visibleLaunchElapsedRealtimeMillis} fresh=${bootLikeCompatEvaluation.visibleLaunchFresh}"
        )
        val compatResult = withContext(Dispatchers.IO) {
            startupCompatCoordinator.handleBootLikeProcessVisibleCompatSignal()
        }
        val decision = compatResult.playbackDecision ?: return@withContext false
        if (compatResult.usedRecoveryPath) {
            Log.d(TAG, "Consuming pending startup window through process-visible recovery")
        }
        if (compatResult.usedVisibleCompatFallback) {
            Log.d(TAG, "Compat startup started from process-visible compatibility path")
            appLogger.logBoot(
                TAG,
                "Compat startup started from process-visible compatibility path",
                "reason=${bootLikeCompatEvaluation.reason} bootReceiverSeen=${bootLikeCompatEvaluation.bootReceiverSeenInCurrentCandidateWindow} elapsedRealtime=${bootLikeCompatEvaluation.visibleLaunchElapsedRealtimeMillis} fresh=${bootLikeCompatEvaluation.visibleLaunchFresh}"
            )
        }
        executeStartupPlaybackDecision(decision, compatResult.executionPlan)
    }

    /**
     * Thực thi quyết định của arbiter từ activity context.
     *
     * Activity không tự phát audio; nó chỉ start [BootPlaybackService] khi
     * decision cho phép hoặc trả về kết quả chờ/bỏ qua để UI không phát trùng.
     */
    private fun executeStartupPlaybackDecision(
        decision: com.example.carchatbot.boot.BootSoundArbiterDecision,
        executionPlan: com.example.carchatbot.boot.StartupExecutionPlan
    ): Boolean {
        return when (decision.action) {
            BootSoundArbiterAction.START_NOW,
            BootSoundArbiterAction.TAKEOVER_STALE -> {
                val started = BootPlaybackService.startForExecution(
                    context = applicationContext,
                    decision = decision,
                    executionPlan = executionPlan
                )
                appLogger.logBoot(
                    TAG,
                    if (started) {
                        "Boot playback foreground start requested"
                    } else {
                        "Boot playback foreground start skipped"
                    },
                    "action=${decision.action} strategy=${executionPlan.strategy} reason=${executionPlan.reason} sessionId=${decision.state.sessionId} startupWindowId=${decision.state.startupWindowId}"
                )
                started
            }

            BootSoundArbiterAction.WAIT_EXISTING -> true
            BootSoundArbiterAction.SKIP_ALREADY_HANDLED -> false
            BootSoundArbiterAction.REJECT_SECOND_TAKEOVER -> false
        }
    }

    /**
     * Nhận diện input dạng mouse/touchpad/stylus để áp dụng hover workaround.
     */
    private fun isMouseLikePointerInput(event: MotionEvent): Boolean {
        return event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD) ||
            event.isFromSource(InputDevice.SOURCE_STYLUS) ||
            event.isFromSource(InputDevice.SOURCE_BLUETOOTH_STYLUS)
    }

    /**
     * Cài workaround cấp decor-view cho hover event gây crash/lag trên một số box.
     */
    private fun installProblematicHoverWorkaround() {
        window.decorView.setOnHoverListener { _, event ->
            suppressProblematicComposeHoverEvent(
                event = event,
                isMouseLikePointer = isMouseLikePointerInput(event),
                logcatMessage = "Suppressing problematic hover dispatch before Compose",
                appLogMessage = "Suppressed problematic hover dispatch before Compose"
            )
        }
        window.decorView.setOnGenericMotionListener { _, event ->
            suppressProblematicComposeHoverEvent(
                event = event,
                isMouseLikePointer = isMouseLikePointerInput(event),
                logcatMessage = "Suppressing problematic hover event before Compose dispatch",
                appLogMessage = "Suppressed problematic hover event before Compose dispatch"
            )
        }
    }

    /**
     * Trả về true khi event hover nên bị chặn trước Compose.
     */
    private fun suppressProblematicComposeHoverEvent(
        event: MotionEvent,
        isMouseLikePointer: Boolean,
        logcatMessage: String,
        appLogMessage: String
    ): Boolean {
        if (
            !AppRuntimePolicies.shouldSuppressProblematicComposeHoverEvent(
                sdkInt = Build.VERSION.SDK_INT,
                actionMasked = event.actionMasked,
                isMouseLikePointer = isMouseLikePointer
            )
        ) {
            return false
        }

        val toolType = if (event.pointerCount > 0) event.getToolType(0) else -1
        Log.w(
            TAG,
            "$logcatMessage: action=${event.actionMasked} source=${event.source} toolType=$toolType"
        )
        appLogger.logBootNoisy(
            TAG,
            appLogMessage,
            "action=${event.actionMasked} source=${event.source} toolType=$toolType sdk=${Build.VERSION.SDK_INT}",
            key = "main_activity_hover_workaround:$appLogMessage"
        )
        return true
    }

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_CANCEL_AUTO_RETURN_HOME = "com.example.carchatbot.action.CANCEL_AUTO_RETURN_HOME"
    }
}
