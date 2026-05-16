package com.example.carchatbot

import com.example.carchatbot.service.PlaybackRequest
import com.example.carchatbot.service.SoundPlayerState
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class FloatingButtonServiceSourceTest {

    private fun readSource(relativePath: String): String {
        val path = Paths.get(relativePath)
        return if (Files.exists(path)) {
            String(Files.readAllBytes(path), UTF_8)
        } else {
            ""
        }
    }

    @Test
    fun `idle hello slot uses the legacy white bell asset`() {
        val layout = readSource("src/main/res/layout/floating_button.xml")
        val bell = readSource("src/main/res/drawable/ic_notifications.xml")

        assertTrue(layout.contains("@drawable/ic_notifications"))
        assertTrue(bell.contains("#FFFFFF"))
    }

    @Test
    fun `preparing state is rendered through the slot aware resolver`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonService.kt")

        assertTrue(source.contains("@AndroidEntryPoint"))
        assertTrue(source.contains("SoundPlayerState.snapshot"))
        assertTrue(source.contains("soundPlayerSnapshot.activeRequest"))
        assertTrue(source.contains("soundPlayerSnapshot.startupPlaybackSoundIndex"))
        assertTrue(source.contains("soundPlayerSnapshot.currentSoundIndex"))
        assertTrue(source.contains("soundPlayerSnapshot.isPlaying"))
        assertTrue(source.contains("BootPlaybackStateStore"))
        assertTrue(source.contains("bootPlaybackStateStore.stateFlow"))
        assertTrue(source.contains("BootPlaybackPhase.PLAYING"))
        assertTrue(source.contains("BootSoundCacheRepository.STARTUP_SOUND_INDEX"))
        assertTrue(source.contains("resolveSlotControlState"))
        assertFalse(source.contains("BootPlaybackStateStore(BootStorageContextProvider(applicationContext))"))
        assertFalse(source.contains("while (true)"))
        assertFalse(source.contains("delay(250)"))
        assertFalse(source.contains("AppRuntimePolicies.resolveFloatingButtonPlaybackAction("))
    }

    @Test
    fun `active slot uses white stop square without red badge`() {
        val activeIcon = readSource("src/main/res/drawable/ic_stop_active.xml")

        assertTrue(activeIcon.contains("M6.5,6.5h11"))
        assertTrue(activeIcon.contains("#FFFFFF"))
        assertFalse(activeIcon.contains("#EF5350"))
    }

    @Test
    fun `cross slot handoff keeps only one slot non idle`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonService.kt")

        SoundPlayerState.resetForTest()
        SoundPlayerState.onPlaybackStarted(PlaybackRequest.userStart(soundIndex = 1))
        SoundPlayerState.onPlaybackRequestQueued(PlaybackRequest.userStart(soundIndex = 2))

        assertEquals(PlaybackRequest.userStart(soundIndex = 2), SoundPlayerState.activeRequest.value)
        assertNull(SoundPlayerState.playingRequest.value)
        assertTrue(source.contains("updateFloatingButtonStates"))
        assertTrue(source.contains("resolveSlotControlState"))
    }

    @Test
    fun `same slot second tap during accepted handoff resolves to stop`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonService.kt")

        SoundPlayerState.resetForTest()
        SoundPlayerState.onPlaybackRequestQueued(PlaybackRequest.userStart(soundIndex = 1))

        assertTrue(source.contains("resolveFloatingButtonPlaybackPlan"))
        assertTrue(source.contains("FloatingButtonPlaybackResolver.resolvePlan("))
        assertTrue(source.contains("FloatingButtonPlaybackAction.STOP"))
        assertFalse(source.contains("AppRuntimePolicies.resolveFloatingButtonPlaybackAction("))
    }

    @Test
    fun `app auto open startup state preserves real autoplay playback and gives boot state priority on startup slot`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonService.kt")
        val bootServiceRunningIndex = source.indexOf("val startupPlaybackServiceRunning = BootPlaybackService.isServiceMarkedRunning()")
        val bootPlayingIndex = source.indexOf("bootState.phase == BootPlaybackPhase.PLAYING")
        val bootActiveIndex = source.indexOf("bootState.isActive()")
        val activeRequestIndex = source.indexOf("activeRequest?.soundIndex == soundIndex")

        assertFalse(source.contains("takeIf { it.isUserInitiated }"))
        assertFalse(source.contains("startupPlaybackSoundIndex == soundIndex -> FloatingButtonSlotControlState.ACTIVE"))
        assertTrue(bootServiceRunningIndex >= 0)
        assertTrue(bootPlayingIndex >= 0)
        assertTrue(bootPlayingIndex > bootServiceRunningIndex)
        assertTrue(bootActiveIndex > bootPlayingIndex)
        assertTrue(activeRequestIndex > bootActiveIndex)
    }

    @Test
    fun `persisted boot state only drives active floating UI while boot playback service is live`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonService.kt")
        val resolver = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonPlaybackResolver.kt")

        assertTrue(source.contains("val startupPlaybackServiceRunning = BootPlaybackService.isServiceMarkedRunning()"))
        assertTrue(source.contains("startupPlaybackServiceRunning &&"))
        assertTrue(source.contains("expireStaleStartupPlaybackOwnershipForOverlay()"))
        assertTrue(source.contains("AppRuntimePolicies.bootSessionStaleTimeoutMillis()"))
        assertTrue(resolver.contains("startupPlaybackServiceRunning: Boolean"))
        assertTrue(resolver.contains("startupPlaybackServiceRunning &&"))
    }

    @Test
    fun `overlay stale cleanup delegates to state store helper so playing boot sessions are preserved`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonService.kt")

        assertTrue(source.contains("bootPlaybackStateStore.expireStaleActiveSession("))
        assertFalse(source.contains("bootPlaybackStateStore.markFailed("))
        assertFalse(source.contains("phase=\${bootState.phase}"))
    }

    @Test
    fun `startup slot tap resolves to stop while boot playback is active`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonService.kt")
        val resolver = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonPlaybackResolver.kt")

        assertTrue(source.contains("BootSoundCacheRepository.STARTUP_SOUND_INDEX"))
        assertTrue(source.contains("shouldStopStartupPlaybackDirectly("))
        assertTrue(source.contains("stopStartupPlaybackFromFloatingButton(soundIndex, playbackPlan)"))
        assertTrue(source.contains("BootPlaybackService.requestStopForManualPreemption("))
        assertTrue(source.contains("playbackPlan.stopBootPlayback"))
        assertTrue(source.contains("reason = \"floating_button_stop\""))
        assertTrue(source.contains("stateStore = bootPlaybackStateStore"))
        assertTrue(resolver.contains("startupPlaybackSoundIndex"))
        assertTrue(resolver.contains("stopBootPlayback = shouldStopBootPlayback"))
    }

    @Test
    fun `stop action does not relaunch sound player as a new foreground service`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonService.kt")

        assertTrue(source.contains("this.action = SoundPlayerService.ACTION_STOP_SOUND"))
        assertTrue(source.contains("startService(intent)"))
        assertTrue(source.contains("if (shouldStopStartupPlaybackDirectly(soundIndex, playbackPlan))"))
        assertTrue(source.contains("return"))
        assertTrue(source.contains("putExtra(SoundPlayerService.EXTRA_SOUND_URI_INDEX, soundIndex)"))
    }

    @Test
    fun `floating button start reasons stay separate from startup execution ownership`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonService.kt")

        assertTrue(source.contains("const val EXTRA_LAUNCH_REASON"))
        assertTrue(source.contains("const val LAUNCH_REASON_BOOT_RESTORE"))
        assertTrue(source.contains("const val LAUNCH_REASON_RUNTIME_RECONCILE"))
        assertTrue(source.contains("const val LAUNCH_REASON_TASK_REMOVED_RESTART"))
        assertTrue(source.contains("fun createStartIntent("))
        assertFalse(source.contains("StartupExecutionGate"))
        assertFalse(source.contains("StartupCompatCoordinator"))
        assertFalse(source.contains("StartupArmStateStore"))
        assertFalse(source.contains("BootPlaybackService.startForExecution"))
    }

    @Test
    fun `floating button still attaches overlay independently of startup playback execution`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonService.kt")

        assertTrue(source.contains("override fun onCreate()"))
        assertTrue(source.contains("windowManager.addView(floatingButtonView, params)"))
        assertTrue(source.contains("override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int"))
        assertTrue(source.contains("startForeground(1001, createNotification())"))
    }

    @Test
    fun `floating button only marks itself running after overlay attachment and logs lifecycle diagnostics`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonService.kt")

        assertTrue(source.contains("lateinit var appLogger"))
        assertTrue(source.contains("appLogger.logService(TAG, \"Service created\")"))
        assertTrue(source.contains("appLogger.log(TAG, \"Floating overlay attached\")"))
        assertTrue(source.contains("appLogger.logException(TAG, e, \"Failed to attach floating overlay\")"))

        val overlayAttachIndex = source.indexOf("windowManager.addView(floatingButtonView, params)")
        val runningIndex = source.indexOf("serviceMarkedRunning = true")

        assertTrue(overlayAttachIndex >= 0)
        assertTrue(runningIndex > overlayAttachIndex)
    }

    @Test
    fun `task removal delegates overlay recovery to core service reconcile instead of self scheduling alarm restart`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/FloatingButtonService.kt")

        assertTrue(source.contains("override fun onTaskRemoved(rootIntent: Intent?)"))
        assertTrue(source.contains("Intent(this, CoreService::class.java).apply"))
        assertTrue(source.contains("action = CoreService.ACTION_RECONCILE_RUNTIME_SERVICES"))
        assertTrue(source.contains("ContextCompat.startForegroundService(this, reconcileIntent)"))
        assertFalse(source.contains("PendingIntent.getService("))
        assertFalse(source.contains("alarmManager.set("))
        assertFalse(source.contains("launchReason = LAUNCH_REASON_TASK_REMOVED_RESTART"))
    }
}
