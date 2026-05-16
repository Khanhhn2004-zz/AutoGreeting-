package com.example.carchatbot

import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.boot.BootPlaybackService
import com.example.carchatbot.boot.BootSoundArbiter
import com.example.carchatbot.boot.StartupArmStateStore
import com.example.carchatbot.boot.StartupCompatCoordinator
import com.example.carchatbot.boot.StartupCompatibilityProfile
import com.example.carchatbot.boot.StartupExecutionGate
import com.example.carchatbot.boot.StartupExecutionStrategy
import com.example.carchatbot.boot.StartupMode
import com.example.carchatbot.boot.BootPlaybackPhase
import com.example.carchatbot.boot.BootSoundArbiterAction
import com.example.carchatbot.boot.BootSignalOrigin
import com.example.carchatbot.runtime.AppRuntimePolicies
import com.example.carchatbot.runtime.BootLikeVisibleCompatReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StartupCompatCoordinatorTest {

    private fun createCoordinator(
        armStore: StartupArmStateStore,
        playbackStore: BootPlaybackStateStore
    ): StartupCompatCoordinator {
        return StartupCompatCoordinator(
            armStore,
            BootSoundArbiter(playbackStore),
            StartupExecutionGate(),
            playbackStore
        )
    }

    @Test
    fun `receiver signal arms startup window and starts immediately for direct execution profiles`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        val result = coordinator.handleReceiverSignal(
            nowMillis = 30_000L,
            profile = StartupCompatibilityProfile.HEAD_UNIT_SLEEP_WAKE,
            sdkInt = 34
        )

        assertEquals(setOf(BootSignalOrigin.RECEIVER), result.armState.observedOrigins)
        assertTrue(result.armState.isPending())
        assertNotNull(result.playbackDecision)
        assertEquals(BootSoundArbiterAction.START_NOW, result.playbackDecision?.action)
        assertEquals(StartupExecutionStrategy.START_FOREGROUND_SERVICE_NOW, result.executionPlan.strategy)
    }

    @Test
    fun `receiver signal only arms startup for profiles that require process visible execution`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        val result = coordinator.handleReceiverSignal(
            nowMillis = 30_000L,
            profile = StartupCompatibilityProfile.UNKNOWN_GENERIC,
            sdkInt = 36
        )

        assertTrue(result.armState.isPending())
        assertNull(result.playbackDecision)
        assertEquals(StartupExecutionStrategy.DEFER_UNTIL_PROCESS_VISIBLE, result.executionPlan.strategy)
    }

    @Test
    fun `locked boot receiver arms pending startup window without claiming playback`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        val lockedBoot = coordinator.handleLockedBootReceiverSignal(
            nowMillis = 30_000L,
            sdkInt = 34
        )

        assertTrue(lockedBoot.armState.isPending())
        assertEquals(setOf(BootSignalOrigin.RECEIVER), lockedBoot.armState.observedOrigins)
        assertNull(lockedBoot.playbackDecision)
        assertEquals(StartupExecutionStrategy.DEFER_UNTIL_PROCESS_VISIBLE, lockedBoot.executionPlan.strategy)
        assertFalse(playbackStore.readState().isActive())

        val recovered = coordinator.handleProcessVisibleSignal(
            startupMode = StartupMode.BOOT_COMPLETED,
            nowMillis = 35_000L,
            sdkInt = 34
        )

        assertNotNull(recovered)
        assertEquals(BootSoundArbiterAction.START_NOW, recovered!!.playbackDecision?.action)
        assertEquals(BootSignalOrigin.RECEIVER, playbackStore.readState().ownerOrigin)
    }

    @Test
    fun `direct receiver execution keeps arm pending so visible recovery can recover if service never runs`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        val direct = coordinator.handleReceiverSignal(
            nowMillis = 30_000L,
            profile = StartupCompatibilityProfile.HEAD_UNIT_SLEEP_WAKE,
            sdkInt = 35
        )
        val recovered = coordinator.handleProcessVisibleSignal(
            startupMode = StartupMode.BOOT_COMPLETED,
            nowMillis = 35_000L,
            sdkInt = 35
        )

        assertNotNull(direct.playbackDecision)
        assertTrue(direct.armState.isPending())
        assertNotNull(recovered)
        assertTrue(recovered!!.usedRecoveryPath)
        assertFalse(recovered.armState.isPending())
        assertEquals(BootSoundArbiterAction.START_NOW, recovered.playbackDecision?.action)
        assertEquals(BootSignalOrigin.RECEIVER, playbackStore.readState().ownerOrigin)
        assertEquals(BootPlaybackPhase.CLAIMED, playbackStore.readState().phase)
    }

    @Test
    fun `process visible recovery waits on running direct receiver playback and then consumes the arm`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        val direct = coordinator.handleReceiverSignal(
            nowMillis = 30_000L,
            profile = StartupCompatibilityProfile.HEAD_UNIT_SLEEP_WAKE,
            sdkInt = 35
        )

        BootPlaybackService.setServiceMarkedRunningForTest(true)
        try {
            val recovered = coordinator.handleProcessVisibleSignal(
                startupMode = StartupMode.BOOT_COMPLETED,
                nowMillis = 31_000L,
                sdkInt = 35
            )

            assertNotNull(direct.playbackDecision)
            assertTrue(direct.armState.isPending())
            assertNotNull(recovered)
            assertEquals(BootSoundArbiterAction.WAIT_EXISTING, recovered!!.playbackDecision?.action)
            assertFalse(recovered.armState.isPending())
            assertEquals(direct.playbackDecision?.state?.sessionId, playbackStore.readState().sessionId)
            assertEquals(BootSignalOrigin.RECEIVER, playbackStore.readState().ownerOrigin)
        } finally {
            BootPlaybackService.setServiceMarkedRunningForTest(false)
        }
    }

    @Test
    fun `process visible recovery can consume a pending armed startup window in boot completed mode`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        val armed = coordinator.handleReceiverSignal(
            nowMillis = 30_000L,
            profile = StartupCompatibilityProfile.UNKNOWN_GENERIC,
            sdkInt = 36
        )

        assertNull(armed.playbackDecision)

        val recovered = coordinator.handleProcessVisibleSignal(
            startupMode = StartupMode.BOOT_COMPLETED,
            nowMillis = 45_000L,
            sdkInt = 36
        )

        assertNotNull(recovered)
        assertTrue(recovered!!.usedRecoveryPath)
        assertEquals(
            setOf(BootSignalOrigin.RECEIVER, BootSignalOrigin.BOOT_VISIBLE_RECOVERY),
            recovered.armState.observedOrigins
        )
        assertFalse(BootSignalOrigin.APP_AUTO_START in recovered.armState.observedOrigins)
        assertEquals(BootSoundArbiterAction.START_NOW, recovered.playbackDecision?.action)
        assertEquals(StartupExecutionStrategy.START_FOREGROUND_SERVICE_NOW, recovered.executionPlan.strategy)
    }

    @Test
    fun `process visible recovery preserves receiver ownership for deferred boot playback`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        coordinator.handleReceiverSignal(
            nowMillis = 30_000L,
            profile = StartupCompatibilityProfile.UNKNOWN_GENERIC,
            sdkInt = 36
        )

        val recovered = coordinator.handleProcessVisibleSignal(
            startupMode = StartupMode.BOOT_COMPLETED,
            nowMillis = 45_000L,
            sdkInt = 36
        )

        assertNotNull(recovered)
        assertTrue(recovered!!.usedRecoveryPath)
        assertEquals(BootSignalOrigin.RECEIVER, recovered.playbackDecision?.state?.ownerOrigin)
        assertTrue(recovered.playbackDecision?.state?.sessionId?.contains("-receiver-") == true)
        assertEquals(BootSignalOrigin.RECEIVER, playbackStore.readState().ownerOrigin)
    }

    @Test
    fun `runtime reconcile recovery can consume pending locked boot startup window`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        coordinator.handleLockedBootReceiverSignal(
            nowMillis = 30_000L,
            sdkInt = 34
        )

        val recovered = coordinator.handleRuntimeReconcileSignal(
            startupMode = StartupMode.BOOT_COMPLETED,
            nowMillis = 35_000L,
            sdkInt = 34
        )

        assertNotNull(recovered)
        assertTrue(recovered!!.usedRecoveryPath)
        assertFalse(recovered.explicitAppAutoOpen)
        assertEquals(BootSoundArbiterAction.START_NOW, recovered.playbackDecision?.action)
        assertEquals(BootSignalOrigin.RECEIVER, playbackStore.readState().ownerOrigin)
        assertEquals(StartupExecutionStrategy.START_FOREGROUND_SERVICE_NOW, recovered.executionPlan.strategy)
    }

    @Test
    fun `process visible recovery expires dead playback ownership before arbitration`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        val armed = coordinator.handleReceiverSignal(
            nowMillis = 30_000L,
            profile = StartupCompatibilityProfile.UNKNOWN_GENERIC,
            sdkInt = 36
        )
        playbackStore.claimSession(
            startupWindowId = 99L,
            sessionId = "boot-99-app_auto_start-0",
            ownerOrigin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 20_000L
        )
        playbackStore.markPlaying(
            sessionId = "boot-99-app_auto_start-0",
            nowMillis = 20_500L
        )

        val recovered = coordinator.handleProcessVisibleSignal(
            startupMode = StartupMode.BOOT_COMPLETED,
            nowMillis = 35_000L,
            sdkInt = 36
        )

        assertNotNull(recovered)
        assertTrue(recovered!!.usedRecoveryPath)
        assertEquals(BootSoundArbiterAction.START_NOW, recovered.playbackDecision?.action)
        assertEquals(armed.armState.startupWindowId, playbackStore.readState().startupWindowId)
        assertEquals(BootPlaybackPhase.CLAIMED, playbackStore.readState().phase)
        assertEquals(BootSignalOrigin.RECEIVER, playbackStore.readState().ownerOrigin)
        assertNull(playbackStore.readState().lastFailureReason)
    }

    @Test
    fun `explicit app auto open remains distinct from compatibility recovery`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        val explicit = coordinator.handleProcessVisibleSignal(
            startupMode = StartupMode.APP_AUTO_OPEN,
            nowMillis = 30_000L,
            sdkInt = 36
        )

        assertNotNull(explicit)
        assertTrue(explicit!!.explicitAppAutoOpen)
        assertFalse(explicit.usedRecoveryPath)
        assertTrue(explicit.armState.observedOrigins.isEmpty())
        assertEquals(BootSoundArbiterAction.START_NOW, explicit.playbackDecision?.action)
        assertEquals(StartupExecutionStrategy.START_FOREGROUND_SERVICE_NOW, explicit.executionPlan.strategy)
    }

    @Test
    fun `boot completed mode without a pending arm state does not start compatibility recovery`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        val recovered = coordinator.handleProcessVisibleSignal(
            startupMode = StartupMode.BOOT_COMPLETED,
            nowMillis = 30_000L,
            sdkInt = 36
        )

        assertNull(recovered)
    }

    @Test
    fun `boot-like visible compat startup arms a fresh generic window and marks compat fallback usage`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        val compat = coordinator.handleBootLikeProcessVisibleCompatSignal(
            nowMillis = 45_000L,
            sdkInt = 34
        )

        assertTrue(compat.usedVisibleCompatFallback)
        assertFalse(compat.usedRecoveryPath)
        assertFalse(compat.explicitAppAutoOpen)
        assertEquals(StartupCompatibilityProfile.UNKNOWN_GENERIC, compat.armState.compatibilityProfile)
        assertEquals(setOf(BootSignalOrigin.BOOT_VISIBLE_RECOVERY), compat.armState.observedOrigins)
        assertFalse(compat.armState.isPending())
        assertEquals(BootSoundArbiterAction.START_NOW, compat.playbackDecision?.action)
        assertEquals(BootSignalOrigin.BOOT_VISIBLE_RECOVERY, compat.playbackDecision?.state?.ownerOrigin)
        assertEquals(StartupExecutionStrategy.START_FOREGROUND_SERVICE_NOW, compat.executionPlan.strategy)
    }

    @Test
    fun `boot-like visible compat startup stays distinct from recovery and explicit app open`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        val compat = coordinator.handleBootLikeProcessVisibleCompatSignal(
            nowMillis = 75_000L,
            sdkInt = 36
        )

        assertNotNull(compat)
        assertTrue(compat.usedVisibleCompatFallback)
        assertFalse(compat.usedRecoveryPath)
        assertFalse(compat.explicitAppAutoOpen)
        assertEquals(BootSoundArbiterAction.START_NOW, compat.playbackDecision?.action)
    }

    @Test
    fun `process visible recovery still accepts an armed startup near the original window boundary`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        val armed = coordinator.handleReceiverSignal(
            nowMillis = 59_000L,
            profile = StartupCompatibilityProfile.UNKNOWN_GENERIC,
            sdkInt = 36
        )

        assertNull(armed.playbackDecision)

        val recovered = coordinator.handleProcessVisibleSignal(
            startupMode = StartupMode.BOOT_COMPLETED,
            nowMillis = 61_000L,
            sdkInt = 36
        )

        assertNotNull(recovered)
        assertEquals(BootSoundArbiterAction.START_NOW, recovered!!.playbackDecision?.action)
    }

    @Test
    fun `recovery path consumes the armed startup window only once`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        coordinator.handleReceiverSignal(
            nowMillis = 59_000L,
            profile = StartupCompatibilityProfile.UNKNOWN_GENERIC,
            sdkInt = 36
        )

        val firstRecovery = coordinator.handleProcessVisibleSignal(
            startupMode = StartupMode.BOOT_COMPLETED,
            nowMillis = 61_000L,
            sdkInt = 36
        )
        val secondRecovery = coordinator.handleProcessVisibleSignal(
            startupMode = StartupMode.BOOT_COMPLETED,
            nowMillis = 62_000L,
            sdkInt = 36
        )

        assertNotNull(firstRecovery)
        assertNull(secondRecovery)
    }

    @Test
    fun `boot like snapshot expires stale active playback ownership before blocking compat startup`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        playbackStore.claimSession(
            startupWindowId = 7L,
            sessionId = "boot-7-app_auto_start-0",
            ownerOrigin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 1_000L
        )

        val snapshot = coordinator.readBootLikeVisibleCompatSnapshot(
            nowMillis = 1_000L + AppRuntimePolicies.bootSessionStaleTimeoutMillis() + 1L
        )

        assertFalse(snapshot.bootPlaybackOwnershipPresent)
        assertEquals(BootPlaybackPhase.FAILED, playbackStore.readState().phase)
    }

    @Test
    fun `boot like snapshot preserves playing ownership while boot playback service is still running`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        playbackStore.claimSession(
            startupWindowId = 8L,
            sessionId = "boot-8-receiver-0",
            ownerOrigin = BootSignalOrigin.RECEIVER,
            nowMillis = 1_000L
        )
        playbackStore.markPlaying(
            sessionId = "boot-8-receiver-0",
            nowMillis = 1_500L
        )

        BootPlaybackService.setServiceMarkedRunningForTest(true)
        try {
            val snapshot = coordinator.readBootLikeVisibleCompatSnapshot(
                nowMillis = 1_500L + AppRuntimePolicies.bootSessionStaleTimeoutMillis() + 1L
            )

            assertTrue(snapshot.bootPlaybackOwnershipPresent)
            assertEquals(BootPlaybackPhase.PLAYING, playbackStore.readState().phase)
        } finally {
            BootPlaybackService.setServiceMarkedRunningForTest(false)
        }
    }

    @Test
    fun `boot like snapshot ignores terminal playback metadata after prior startup finished`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        playbackStore.claimSession(
            startupWindowId = 9L,
            sessionId = "boot-9-app_auto_start-0",
            ownerOrigin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 2_000L
        )
        playbackStore.markCompleted(
            sessionId = "boot-9-app_auto_start-0",
            nowMillis = 2_500L
        )

        val snapshot = coordinator.readBootLikeVisibleCompatSnapshot(nowMillis = 3_000L)

        assertFalse(snapshot.bootPlaybackOwnershipPresent)
    }

    @Test
    fun `boot like snapshot only reports startup window armed while receiver window is still pending`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        armStore.armStartupWindow(
            startupWindowId = 11L,
            origin = BootSignalOrigin.RECEIVER,
            compatibilityProfile = StartupCompatibilityProfile.UNKNOWN_GENERIC,
            nowMillis = 4_000L
        )
        armStore.markConsumed(
            startupWindowId = 11L,
            nowMillis = 4_500L
        )

        val snapshot = coordinator.readBootLikeVisibleCompatSnapshot(nowMillis = 5_000L)

        assertFalse(snapshot.startupWindowArmed)
    }

    @Test
    fun `boot like compat evaluation is unblocked once stale startup ownership is expired`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        playbackStore.claimSession(
            startupWindowId = 15L,
            sessionId = "boot-15-app_auto_start-0",
            ownerOrigin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 1_000L
        )

        val snapshot = coordinator.readBootLikeVisibleCompatSnapshot(
            nowMillis = 1_000L + AppRuntimePolicies.bootSessionStaleTimeoutMillis() + 1L
        )
        val evaluation = AppRuntimePolicies.evaluateBootLikeVisibleCompatStartup(
            startupMode = StartupMode.BOOT_COMPLETED,
            hasPermissions = true,
            isLoggedIn = true,
            launchIntentFlags = 0,
            hasSavedInstanceState = false,
            deviceBootAgeMillis = 45_000L,
            startupWindowArmed = snapshot.startupWindowArmed,
            bootReceiverSeenInCurrentCandidateWindow = snapshot.bootReceiverSeenInCurrentCandidateWindow,
            bootPlaybackOwnershipPresent = snapshot.bootPlaybackOwnershipPresent,
            hasPlayableSource = true
        )

        assertTrue(evaluation.matches)
        assertEquals(BootLikeVisibleCompatReason.SMALL_UPTIME_NO_RECEIVER, evaluation.reason)
    }

    @Test
    fun `boot like compat evaluation still blocks while fresh startup ownership is active`() {
        val armStore = StartupArmStateStore(Files.createTempDirectory("startup-arm-state").toFile())
        val playbackStore = BootPlaybackStateStore(Files.createTempDirectory("boot-playback-state").toFile())
        val coordinator = createCoordinator(armStore, playbackStore)

        playbackStore.claimSession(
            startupWindowId = 16L,
            sessionId = "boot-16-app_auto_start-0",
            ownerOrigin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 1_000L
        )

        BootPlaybackService.setServiceMarkedRunningForTest(true)
        try {
            val snapshot = coordinator.readBootLikeVisibleCompatSnapshot(nowMillis = 1_001L)
            val evaluation = AppRuntimePolicies.evaluateBootLikeVisibleCompatStartup(
                startupMode = StartupMode.BOOT_COMPLETED,
                hasPermissions = true,
                isLoggedIn = true,
                launchIntentFlags = 0,
                hasSavedInstanceState = false,
                deviceBootAgeMillis = 45_000L,
                startupWindowArmed = snapshot.startupWindowArmed,
                bootReceiverSeenInCurrentCandidateWindow = snapshot.bootReceiverSeenInCurrentCandidateWindow,
                bootPlaybackOwnershipPresent = snapshot.bootPlaybackOwnershipPresent,
                hasPlayableSource = true
            )

            assertFalse(evaluation.matches)
            assertEquals(BootLikeVisibleCompatReason.BOOT_PLAYBACK_OWNERSHIP_PRESENT, evaluation.reason)
        } finally {
            BootPlaybackService.setServiceMarkedRunningForTest(false)
        }
    }
}
