package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class BootCompletedReceiverSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `boot receiver routes unlocked startup signal through arbiter before launching startup playback service`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/BootCompletedReceiver.kt")

        assertTrue(source.contains("StartupCompatCoordinator"))
        assertTrue(source.contains("StartupTriggerRouter"))
        assertTrue(source.contains("userPreferencesRepository.startupMode.first()"))
        assertTrue(source.contains("StartupTriggerRouter.allowsBootCompleted(startupMode)"))
        assertTrue(source.contains("shouldDeferPlaybackUntilUnlockedBoot(action)"))
        assertTrue(source.contains("handleLockedBootReceiverSignal("))
        assertTrue(source.contains("\"Boot playback armed until BOOT_COMPLETED\""))
        assertTrue(source.contains("handleReceiverSignal("))
        assertTrue(source.contains("BootPlaybackService.startForExecution"))
        assertTrue(source.contains("executionPlan"))
        assertTrue(source.contains("BootSoundArbiterAction.SKIP_ALREADY_HANDLED"))
    }

    @Test
    fun `locked boot receiver arms startup window instead of only logging defer`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/BootCompletedReceiver.kt")

        val deferIndex = source.indexOf("shouldDeferPlaybackUntilUnlockedBoot(action)")
        val armIndex = source.indexOf("handleLockedBootReceiverSignal(")
        val directStartIndex = source.indexOf("handleStartupSignal(appContext)")

        assertTrue(deferIndex >= 0)
        assertTrue(armIndex > deferIndex)
        assertTrue(directStartIndex > armIndex)
        assertTrue(source.contains("startupWindowId=\${compatResult.armState.startupWindowId}"))
        assertFalse(source.contains("\"Boot playback deferred until BOOT_COMPLETED\""))
    }

    @Test
    fun `boot receiver keeps macro-like boot actions and removes debug trigger paths`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/BootCompletedReceiver.kt")

        assertFalse(source.contains("ACTION_FAKE_ACC_ON"))
        assertFalse(source.contains("ACTION_FAKE_ACC_OFF"))
        assertFalse(source.contains("CoreService.ACTION_BOOT_INIT"))
        assertFalse(source.contains("CoreService.ACTION_SHUTDOWN"))
        assertFalse(source.contains("ACTION_POWER_CONNECTED"))
        assertFalse(source.contains("ACTION_POWER_DISCONNECTED"))
        assertFalse(source.contains("QUICKBOOT_POWEROFF"))
        assertFalse(source.contains("ACC_ON"))
        assertFalse(source.contains("ACC_OFF"))
        assertTrue(source.contains("ACTION_QUICKBOOT_POWERON"))
        assertTrue(source.contains("ACTION_HTC_QUICKBOOT_POWERON"))
        assertTrue(source.contains("approvedStartupActions"))
        assertTrue(source.contains("isApprovedStartupAction"))
    }

    @Test
    fun `boot receiver restores floating button independently from startup playback`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/BootCompletedReceiver.kt")

        // Both tasks run inside the unified handleBootEventAsync method
        assertTrue(source.contains("handleBootEventAsync(context, action)"))
        assertTrue(source.contains("restoreFloatingButtonAfterBoot(appContext)"))
        assertTrue(source.contains("FloatingButtonService"))
        assertTrue(source.contains("FloatingButtonService.createStartIntent("))
        assertTrue(source.contains("FloatingButtonService.LAUNCH_REASON_BOOT_RESTORE"))
        assertTrue(source.contains("shouldStartFloatingButton"))

        // Task 1 (floating button restore) appears before Task 2 (startup signal)
        val restoreIndex = source.indexOf("// Task 1: Restore floating button")
        val startupIndex = source.indexOf("// Task 2: Handle startup signal")
        assertTrue(restoreIndex >= 0)
        assertTrue(startupIndex >= 0)
        assertTrue(restoreIndex < startupIndex)

        assertTrue(source.contains("\"Floating button restore skipped\""))
        assertTrue(source.contains("\"loggedIn=\$isLoggedIn enabled=\$floatingButtonEnabled overlay=\$canDrawOverlays\""))
    }

    @Test
    fun `boot receiver requests runtime reconcile after boot so overlay restore is retried by core service`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/BootCompletedReceiver.kt")

        assertTrue(source.contains("requestRuntimeReconcileAfterBoot(appContext, action)"))
        assertTrue(source.contains("Intent(context, CoreService::class.java).apply"))
        assertTrue(source.contains("this.action = CoreService.ACTION_RECONCILE_RUNTIME_SERVICES"))
        assertTrue(source.contains("ContextCompat.startForegroundService(context, reconcileIntent)"))
        assertTrue(source.contains("\"Runtime reconcile requested after boot\""))
        assertTrue(source.contains("\"Runtime reconcile request failed after boot\""))
    }

    @Test
    fun `locked boot receiver arms startup window before requesting runtime reconcile`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/BootCompletedReceiver.kt")

        val armIndex = source.indexOf("handleLockedBootReceiverSignal(")
        val reconcileIndex = source.indexOf("requestRuntimeReconcileAfterBoot(appContext, action)")

        assertTrue(armIndex >= 0)
        assertTrue(reconcileIndex >= 0)
        assertTrue(armIndex < reconcileIndex)
    }

    @Test
    fun `boot receiver reattempts floating restore when unlocked boot follows locked boot duplicate`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/BootCompletedReceiver.kt")

        assertTrue(source.contains("shouldRetryFloatingRestoreOnUnlockedBootCompleted"))
        assertTrue(source.contains("retryFloatingRestore"))
        // Retry path uses its own safe goAsync() wrapper
        assertTrue(source.contains("restoreFloatingButtonAfterBootSafe(context)"))
        assertTrue(source.contains("\"Reattempting floating button restore on BOOT_COMPLETED after locked boot\""))
    }

    @Test
    fun `boot receiver logs richer boot action mode and decision breadcrumbs for diagnostics export`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/BootCompletedReceiver.kt")

        assertTrue(source.contains("action=\$action"))
        assertTrue(source.contains("mode=\$startupMode"))
        assertTrue(source.contains("profile=\${compatResult.armState.compatibilityProfile}"))
        assertTrue(source.contains("strategy=\${compatResult.executionPlan.strategy}"))
        assertTrue(source.contains("decision=\${decision.action}"))
        assertTrue(source.contains("sessionId=\${decision.state.sessionId}"))
        assertTrue(source.contains("startupWindowId=\${decision.state.startupWindowId}"))
        assertTrue(source.contains("\"Boot playback foreground start requested\""))
        assertTrue(source.contains("\"Boot playback foreground start rejected\""))
    }

    @Test
    fun `boot receiver does not block on startup mode from onReceive thread`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/BootCompletedReceiver.kt")

        assertFalse(source.contains("import kotlinx.coroutines.runBlocking"))
        assertFalse(source.contains("runBlocking"))
        // Unified async entry point, not separate goAsync() per task
        assertTrue(source.contains("handleBootEventAsync(context, action)"))
        assertTrue(source.contains("goAsync()"))
        assertTrue(source.contains("userPreferencesRepository.startupMode.first()"))
        assertTrue(source.contains("pendingResult?.finish()"))
    }

    @Test
    fun `boot receiver treats pending result as nullable framework state and finishes safely`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/BootCompletedReceiver.kt")

        assertTrue(source.contains("val pendingResult: BroadcastReceiver.PendingResult? = goAsync()"))
        assertTrue(source.contains("pendingResult?.finish()"))
        assertFalse(source.contains("pendingResult.finish()"))
    }

    @Test
    fun `boot receiver records raw probe before injected logging or datastore reads`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/BootCompletedReceiver.kt")

        val rawProbeIndex = source.indexOf("BootReceiverProbeStore.recordRawReceive(")
        val appLoggerIndex = source.indexOf("appLogger.logBoot(\"BootReceiver\", \"Event received")
        val startupModeIndex = source.indexOf("userPreferencesRepository.startupMode.first()")

        assertTrue(rawProbeIndex >= 0)
        assertTrue(appLoggerIndex > rawProbeIndex)
        assertTrue(startupModeIndex > rawProbeIndex)
    }
}
