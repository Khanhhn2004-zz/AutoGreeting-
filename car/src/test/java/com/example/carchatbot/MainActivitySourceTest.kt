package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class MainActivitySourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `playback finished returns app to launcher instead of only finishing activity`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")

        assertTrue(source.contains("private fun returnToLauncher()"))
        assertTrue(source.contains("if (intent?.action == SoundPlayerService.ACTION_PLAYBACK_FINISHED && pendingAutoReturnHome)"))
        assertTrue(source.contains("pendingAutoReturnHome"))
        assertTrue(source.contains("returnToLauncher()"))
        assertTrue(source.contains("moveTaskToBack(true)"))
        assertTrue(source.contains("Intent.ACTION_MAIN"))
        assertTrue(source.contains("Intent.CATEGORY_HOME"))
        assertFalse(source.contains("if (intent?.action == SoundPlayerService.ACTION_PLAYBACK_FINISHED) {\n                finish()"))
    }

    @Test
    fun `main activity can cancel auto return home when playback stop is user initiated`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")

        assertTrue(source.contains("ACTION_CANCEL_AUTO_RETURN_HOME"))
        assertTrue(source.contains("cancelAutoReturnHomeReceiver"))
        assertTrue(source.contains("pendingAutoReturnHome = false"))
    }

    @Test
    fun `app open autoplay waits on a pending boot autoplay instead of immediately sending a duplicate request`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")

        assertTrue(source.contains("StartupCompatCoordinator"))
        assertTrue(source.contains("StartupTriggerRouter"))
        assertTrue(source.contains("val startupMode by mainViewModel.startupMode.collectAsState"))
        assertTrue(source.contains("StartupTriggerRouter.allowsBootCompleted(startupMode)"))
        assertTrue(source.contains("StartupTriggerRouter.allowsAppAutoOpen(startupMode)"))
        assertTrue(source.contains("BootPlaybackService.startForExecution"))
        assertTrue(source.contains("startupCompatCoordinator.handleProcessVisibleSignal("))
        assertTrue(source.contains("usedRecoveryPath"))
        assertTrue(source.contains("executionPlan"))
        assertTrue(source.contains("BootSoundArbiterAction.SKIP_ALREADY_HANDLED -> false"))
        assertFalse(source.contains("val playOnOpenEnabled by mainViewModel.playOnOpenEnabled.collectAsState"))
    }

    @Test
    fun `pending receiver recovery is attempted before app open autoplay gate`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")
        val recoveryIndex = source.indexOf("requestPendingBootRecoveryFromActivity(")
        val appOpenGateIndex = source.indexOf("AppRuntimePolicies.shouldAutoPlayOnActivityOpen(")

        assertTrue(source.contains("StartupTriggerRouter.allowsBootCompleted(startupMode)"))
        assertTrue(recoveryIndex >= 0)
        assertTrue(appOpenGateIndex > recoveryIndex)
        assertTrue(source.contains("return@LaunchedEffect"))
    }

    @Test
    fun `startup recovery claim and foreground service start cannot be split by compose cancellation`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")
        val recoveryIndex = source.indexOf("requestPendingBootRecoveryFromActivity(")
        val startIndex = source.indexOf("BootPlaybackService.startForExecution(")

        assertTrue(source.contains("NonCancellable"))
        assertTrue(recoveryIndex >= 0)
        assertTrue(startIndex > recoveryIndex)
        assertTrue(source.contains("Process-visible pending boot recovery decision"))
        assertTrue(source.contains("Boot playback foreground start requested"))
    }

    @Test
    fun `activity startup autoplay is reported to arbiter instead of starting sound player directly`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")

        assertTrue(source.contains("StartupCompatCoordinator"))
        assertTrue(source.contains("handleProcessVisibleSignal"))
        assertFalse(source.contains("Intent(this@MainActivity, SoundPlayerService::class.java)"))
    }

    @Test
    fun `app open startup fallback is bounded to recent device boot and reads ownership off main thread`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")

        assertTrue(source.contains("SystemClock.elapsedRealtime()"))
        assertTrue(source.contains("shouldAutoPlayOnActivityOpen("))
        assertTrue(source.contains("deviceBootAgeMillis"))
        assertTrue(source.contains("withContext(Dispatchers.IO)"))
    }

    @Test
    fun `boot completed mode does not reuse the explicit app open autoplay gate`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")

        assertTrue(source.contains("playOnOpenEnabled = StartupTriggerRouter.allowsAppAutoOpen(startupMode)"))
        assertFalse(source.contains("playOnOpenEnabled = StartupTriggerRouter.allowsAppAutoOpen(startupMode) ||"))
    }

    @Test
    fun `boot completed mode can recover from a fresh process visible launch without explicit app auto open`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")
        val bootVisibleGateIndex = source.indexOf("shouldAttemptBootModeVisibleStartupRecovery(")
        val appOpenGateIndex = source.indexOf("shouldAutoPlayOnActivityOpen(")
        val startupRequestIndex = source.indexOf("shouldAttemptStartupPlayback")

        assertTrue(bootVisibleGateIndex >= 0)
        assertTrue(appOpenGateIndex >= 0)
        assertTrue(startupRequestIndex >= 0)
        assertTrue(source.contains("bootModeEnabled = StartupTriggerRouter.allowsBootCompleted(startupMode)"))
        assertTrue(source.contains("shouldAttemptStartupPlayback = shouldAttemptAutoplay || shouldAttemptBootVisibleStartupRecovery"))
        assertTrue(source.contains("Boot-visible startup gate:"))
    }

    @Test
    fun `main activity marks startup dispatch as in flight before awaiting startup playback work`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")
        val inFlightFlagIndex = source.indexOf("private var startupPlaybackDispatchInFlight = false")
        val consumedFlagIndex = source.indexOf("private var startupPlaybackDispatchedModeForThisLaunch: StartupMode? = null")
        val startInFlightIndex = source.indexOf("startupPlaybackDispatchInFlight = true")
        val recoveryRequestIndex = source.indexOf("requestPendingBootRecoveryFromActivity(")
        val appOpenRequestIndex = source.indexOf("requestStartupPlaybackFromActivity(")
        val clearInFlightIndex = source.lastIndexOf("startupPlaybackDispatchInFlight = false")

        assertTrue(inFlightFlagIndex >= 0)
        assertTrue(consumedFlagIndex >= 0)
        assertTrue(source.contains("val startupDispatchAlreadyConsumed ="))
        assertTrue(source.contains("soundPlayedMode == startupMode ||"))
        assertTrue(source.contains("startupPlaybackDispatchedModeForThisLaunch == startupMode"))
        assertTrue(source.contains("alreadyPlayedInProcess = startupDispatchAlreadyConsumed"))
        assertTrue(startInFlightIndex >= 0)
        assertTrue(source.contains("try {"))
        assertTrue(source.contains("finally {"))
        assertTrue(recoveryRequestIndex > startInFlightIndex)
        assertTrue(appOpenRequestIndex > startInFlightIndex)
        assertTrue(source.contains("startupPlaybackDispatchedModeForThisLaunch = startupMode"))
        assertTrue(clearInFlightIndex > startInFlightIndex)
    }

    @Test
    fun `app open autoplay latch is mode scoped so switching modes can re-enable playback in same process`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")

        assertFalse(source.contains("private var startupPlaybackDispatchedForThisLaunch = false"))
        assertFalse(source.contains("soundPlayed || startupPlaybackDispatchInFlight || startupPlaybackDispatchedForThisLaunch"))
        assertTrue(source.contains("var soundPlayedMode by remember { mutableStateOf<StartupMode?>(null) }"))
        assertTrue(source.contains("startupPlaybackDispatchedModeForThisLaunch = null"))
        assertTrue(source.contains("soundPlayedMode = null"))
        assertTrue(source.contains("soundPlayedMode == startupMode ||"))
        assertTrue(source.contains("startupPlaybackDispatchedModeForThisLaunch == startupMode"))
    }

    @Test
    fun `main activity evaluates boot-like compat fallback only after normal startup paths fail`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")

        assertTrue(source.contains("evaluateBootLikeVisibleCompatStartup("))
        assertTrue(source.contains("startupCompatCoordinator.handleBootLikeProcessVisibleCompatSignal("))
        assertTrue(source.contains("Compat startup candidate detected from fresh visible launch"))
        assertTrue(source.contains("Compat startup skipped because signature incomplete"))
        assertTrue(source.contains("appLogger.logBoot("))
    }

    @Test
    fun `main activity no longer reports ui foreground or background just to manage floating overlay visibility`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")

        assertFalse(source.contains("notifyCoreServiceUiVisibility("))
        assertFalse(source.contains("CoreService.ACTION_APP_UI_FOREGROUND"))
        assertFalse(source.contains("CoreService.ACTION_APP_UI_BACKGROUND"))
        assertFalse(source.contains("override fun onStart()"))
        assertFalse(source.contains("override fun onStop()"))
        assertTrue(source.contains("Intent(this@MainActivity, CoreService::class.java)"))
    }

    @Test
    fun `main activity suppresses problematic compose hover input before dispatching to compose`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")

        assertTrue(source.contains("override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean"))
        assertTrue(source.contains("private fun installProblematicHoverWorkaround()"))
        assertTrue(source.contains("window.decorView.setOnHoverListener"))
        assertTrue(source.contains("window.decorView.setOnGenericMotionListener"))
        assertTrue(source.contains("shouldSuppressProblematicComposeHoverEvent("))
        assertTrue(source.contains("event.isFromSource(InputDevice.SOURCE_MOUSE)"))
        assertTrue(source.contains("event.isFromSource(InputDevice.SOURCE_STYLUS)"))
        assertTrue(source.contains("Suppressed problematic hover event before Compose dispatch"))
        assertTrue(source.contains("Suppressed problematic hover dispatch before Compose"))
        assertTrue(source.contains("Recovered from Compose hover crash"))
    }

    @Test
    fun `hover suppression diagnostics are throttled instead of persisted for every event`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")

        assertTrue(source.contains("appLogger.logBootNoisy("))
        assertFalse(
            source.contains(
                "appLogger.logBoot(\n                TAG,\n                \"Suppressed problematic hover event before Compose dispatch\""
            )
        )
        assertFalse(
            source.contains(
                "appLogger.logBoot(\n            TAG,\n            appLogMessage,"
            )
        )
    }
}
