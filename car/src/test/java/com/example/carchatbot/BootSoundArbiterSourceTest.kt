package com.example.carchatbot

import com.example.carchatbot.boot.BootPlaybackPhase
import com.example.carchatbot.boot.BootPlaybackService
import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.boot.BootSignalOrigin
import com.example.carchatbot.boot.BootSoundArbiter
import com.example.carchatbot.boot.BootSoundArbiterAction
import com.example.carchatbot.boot.StartupSignalContract
import com.example.carchatbot.runtime.AppRuntimePolicies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BootSoundArbiterSourceTest {

    @Test
    fun `no active session starts immediately`() {
        val storageDir = Files.createTempDirectory("boot-sound-arbiter").toFile()
        val store = BootPlaybackStateStore(storageDir)
        val arbiter = BootSoundArbiter(store)

        val decision = arbiter.arbitrate(
            origin = BootSignalOrigin.RECEIVER,
            nowMillis = 1_000L
        )

        assertEquals(BootSoundArbiterAction.START_NOW, decision.action)
        assertEquals(BootPlaybackPhase.CLAIMED, store.readState().phase)
    }

    @Test
    fun `healthy same window session waits for the existing owner`() {
        val storageDir = Files.createTempDirectory("boot-sound-arbiter").toFile()
        val store = BootPlaybackStateStore(storageDir)
        val arbiter = BootSoundArbiter(store)

        arbiter.arbitrate(
            origin = BootSignalOrigin.RECEIVER,
            nowMillis = 1_000L
        )
        val decision = arbiter.arbitrate(
            origin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 2_000L
        )

        assertEquals(BootSoundArbiterAction.WAIT_EXISTING, decision.action)
        assertEquals(BootSignalOrigin.RECEIVER, store.readState().ownerOrigin)
    }

    @Test
    fun `stale same window session can be taken over once`() {
        val storageDir = Files.createTempDirectory("boot-sound-arbiter").toFile()
        val store = BootPlaybackStateStore(storageDir)
        val arbiter = BootSoundArbiter(store)

        arbiter.arbitrate(
            origin = BootSignalOrigin.RECEIVER,
            nowMillis = 1_000L
        )
        val decision = arbiter.arbitrate(
            origin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 7_000L
        )

        assertEquals(BootSoundArbiterAction.TAKEOVER_STALE, decision.action)
        assertEquals(1, store.readState().takeoverCount)
        assertEquals(BootSignalOrigin.APP_AUTO_START, store.readState().ownerOrigin)
    }

    @Test
    fun `completed same window session is replayed by explicit app auto open`() {
        val storageDir = Files.createTempDirectory("boot-sound-arbiter").toFile()
        val store = BootPlaybackStateStore(storageDir)
        val arbiter = BootSoundArbiter(store)

        val firstDecision = arbiter.arbitrate(
            signal = StartupSignalContract.appOpenExplicitSignal(),
            nowMillis = 1_000L
        )
        store.markCompleted(
            sessionId = firstDecision.state.sessionId!!,
            nowMillis = 4_000L
        )

        val decision = arbiter.arbitrate(
            signal = StartupSignalContract.appOpenExplicitSignal(),
            nowMillis = 5_000L
        )

        assertEquals(BootSoundArbiterAction.START_NOW, decision.action)
        assertEquals(BootSignalOrigin.APP_AUTO_START, store.readState().ownerOrigin)
        assertEquals(BootPlaybackPhase.CLAIMED, store.readState().phase)
    }

    @Test
    fun `manually stopped same window session is replayed by explicit app auto open`() {
        val storageDir = Files.createTempDirectory("boot-sound-arbiter-manual-stop").toFile()
        val store = BootPlaybackStateStore(storageDir)
        val arbiter = BootSoundArbiter(store)

        val firstDecision = arbiter.arbitrate(
            signal = StartupSignalContract.appOpenExplicitSignal(),
            nowMillis = 1_000L
        )
        store.markManuallyStopped(
            sessionId = firstDecision.state.sessionId!!,
            reason = "floating_button_stop",
            nowMillis = 4_000L
        )

        val decision = arbiter.arbitrate(
            signal = StartupSignalContract.appOpenExplicitSignal(),
            nowMillis = 5_000L
        )

        assertEquals(BootSoundArbiterAction.START_NOW, decision.action)
        assertEquals(BootSignalOrigin.APP_AUTO_START, store.readState().ownerOrigin)
        assertEquals(BootPlaybackPhase.CLAIMED, store.readState().phase)
    }

    @Test
    fun `completed same window receiver session is still deduped for boot signals`() {
        val storageDir = Files.createTempDirectory("boot-sound-arbiter-completed-boot").toFile()
        val store = BootPlaybackStateStore(storageDir)
        val arbiter = BootSoundArbiter(store)

        val startupWindowId = AppRuntimePolicies.calculateBootStartupWindowId(1_000L)
        val firstDecision = arbiter.arbitrate(
            signal = StartupSignalContract.receiverStartupSignal(startupWindowId),
            nowMillis = 1_000L
        )
        store.markCompleted(
            sessionId = firstDecision.state.sessionId!!,
            nowMillis = 4_000L
        )

        val decision = arbiter.arbitrate(
            signal = StartupSignalContract.receiverStartupSignal(startupWindowId),
            nowMillis = 5_000L
        )

        assertEquals(BootSoundArbiterAction.SKIP_ALREADY_HANDLED, decision.action)
        assertEquals(BootSignalOrigin.RECEIVER, store.readState().ownerOrigin)
        assertEquals(BootPlaybackPhase.COMPLETED, store.readState().phase)
    }

    @Test
    fun `recoverable failed same window session is replayed after dangling ownership cleanup`() {
        val storageDir = Files.createTempDirectory("boot-sound-arbiter").toFile()
        val store = BootPlaybackStateStore(storageDir)
        val arbiter = BootSoundArbiter(store)

        val firstDecision = arbiter.arbitrate(
            origin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 30_000L
        )
        store.markFailed(
            sessionId = firstDecision.state.sessionId!!,
            failureReason = "startup_service_not_running",
            nowMillis = 30_500L
        )

        val retryDecision = arbiter.arbitrate(
            origin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 31_000L
        )

        assertEquals(BootSoundArbiterAction.START_NOW, retryDecision.action)
        assertEquals(BootPlaybackPhase.CLAIMED, store.readState().phase)
        assertEquals(BootSignalOrigin.APP_AUTO_START, store.readState().ownerOrigin)
    }

    @Test
    fun `second takeover in the same window is rejected`() {
        val storageDir = Files.createTempDirectory("boot-sound-arbiter").toFile()
        val store = BootPlaybackStateStore(storageDir)
        val arbiter = BootSoundArbiter(store)

        arbiter.arbitrate(
            origin = BootSignalOrigin.RECEIVER,
            nowMillis = 1_000L
        )
        arbiter.arbitrate(
            origin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 7_000L
        )
        val decision = arbiter.arbitrate(
            origin = BootSignalOrigin.RECEIVER,
            nowMillis = 13_000L
        )

        assertEquals(BootSoundArbiterAction.REJECT_SECOND_TAKEOVER, decision.action)
        assertEquals(1, store.readState().takeoverCount)
    }

    @Test
    fun `process visible recovery can take over a stale startup once when coordinator keeps the same startup window`() {
        val storageDir = Files.createTempDirectory("boot-sound-arbiter").toFile()
        val store = BootPlaybackStateStore(storageDir)
        val arbiter = BootSoundArbiter(store)

        arbiter.arbitrate(
            origin = BootSignalOrigin.RECEIVER,
            nowMillis = 1_000L
        )

        val decision = arbiter.arbitrate(
            signal = StartupSignalContract.processVisibleRecoverySignal(
                AppRuntimePolicies.calculateBootStartupWindowId(1_000L)
            ),
            nowMillis = AppRuntimePolicies.bootStartupWindowMillis() + 1_000L
        )

        assertEquals(BootSoundArbiterAction.TAKEOVER_STALE, decision.action)
        assertEquals(BootSignalOrigin.RECEIVER, store.readState().ownerOrigin)
        assertEquals(0L, store.readState().startupWindowId)
        assertEquals(1, store.readState().takeoverCount)
    }

    @Test
    fun `startup window helper dedupes signals inside the same window`() {
        assertTrue(AppRuntimePolicies.isSameBootStartupWindow(1_000L, 14_999L))
        assertFalse(AppRuntimePolicies.isSameBootStartupWindow(1_000L, 15_000L))
        assertEquals(
            AppRuntimePolicies.calculateBootStartupWindowId(1_000L),
            AppRuntimePolicies.calculateBootStartupWindowId(14_999L)
        )
    }

    @Test
    fun `fresh receiver playback ignores later receiver signal from a new startup window while audio is still running`() {
        val storageDir = Files.createTempDirectory("boot-sound-arbiter-receiver-replay").toFile()
        val store = BootPlaybackStateStore(storageDir)
        val arbiter = BootSoundArbiter(store)

        val firstDecision = arbiter.arbitrate(
            signal = StartupSignalContract.receiverStartupSignal(
                startupWindowId = AppRuntimePolicies.calculateBootStartupWindowId(1_000L)
            ),
            nowMillis = 1_000L
        )
        store.markPlaying(
            sessionId = firstDecision.state.sessionId!!,
            nowMillis = 1_500L
        )

        BootPlaybackService.setServiceMarkedRunningForTest(true)
        try {
            val decision = arbiter.arbitrate(
                signal = StartupSignalContract.receiverStartupSignal(
                    startupWindowId = AppRuntimePolicies.calculateBootStartupWindowId(40_000L)
                ),
                nowMillis = 40_000L
            )

            assertEquals(BootSoundArbiterAction.WAIT_EXISTING, decision.action)
            assertEquals(firstDecision.state.sessionId, store.readState().sessionId)
            assertEquals(BootPlaybackPhase.PLAYING, store.readState().phase)
            assertEquals(BootSignalOrigin.RECEIVER, store.readState().ownerOrigin)
        } finally {
            BootPlaybackService.setServiceMarkedRunningForTest(false)
        }
    }

    @Test
    fun `fresh receiver startup waits when later receiver signal arrives while service is still starting`() {
        val storageDir = Files.createTempDirectory("boot-sound-arbiter-receiver-starting").toFile()
        val store = BootPlaybackStateStore(storageDir)
        val arbiter = BootSoundArbiter(store)

        val firstDecision = arbiter.arbitrate(
            signal = StartupSignalContract.receiverStartupSignal(
                startupWindowId = AppRuntimePolicies.calculateBootStartupWindowId(1_000L)
            ),
            nowMillis = 1_000L
        )
        store.markStarting(
            sessionId = firstDecision.state.sessionId!!,
            nowMillis = 1_500L
        )

        BootPlaybackService.setServiceMarkedRunningForTest(true)
        try {
            val decision = arbiter.arbitrate(
                signal = StartupSignalContract.receiverStartupSignal(
                    startupWindowId = AppRuntimePolicies.calculateBootStartupWindowId(40_000L)
                ),
                nowMillis = 40_000L
            )

            assertEquals(BootSoundArbiterAction.WAIT_EXISTING, decision.action)
            assertEquals(firstDecision.state.sessionId, store.readState().sessionId)
            assertEquals(BootPlaybackPhase.STARTING, store.readState().phase)
            assertEquals(BootSignalOrigin.RECEIVER, store.readState().ownerOrigin)
        } finally {
            BootPlaybackService.setServiceMarkedRunningForTest(false)
        }
    }

    @Test
    fun `later receiver signal can restart startup playback when prior playing service is no longer running`() {
        val storageDir = Files.createTempDirectory("boot-sound-arbiter-receiver-recover").toFile()
        val store = BootPlaybackStateStore(storageDir)
        val arbiter = BootSoundArbiter(store)

        val firstDecision = arbiter.arbitrate(
            signal = StartupSignalContract.receiverStartupSignal(
                startupWindowId = AppRuntimePolicies.calculateBootStartupWindowId(1_000L)
            ),
            nowMillis = 1_000L
        )
        store.markPlaying(
            sessionId = firstDecision.state.sessionId!!,
            nowMillis = 1_500L
        )

        BootPlaybackService.setServiceMarkedRunningForTest(false)

        val decision = arbiter.arbitrate(
            signal = StartupSignalContract.receiverStartupSignal(
                startupWindowId = AppRuntimePolicies.calculateBootStartupWindowId(40_000L)
            ),
            nowMillis = 40_000L
        )

        assertEquals(BootSoundArbiterAction.START_NOW, decision.action)
        assertEquals(
            "boot-${AppRuntimePolicies.calculateBootStartupWindowId(40_000L)}-receiver-0",
            store.readState().sessionId
        )
        assertEquals(BootPlaybackPhase.CLAIMED, store.readState().phase)
        assertEquals(BootSignalOrigin.RECEIVER, store.readState().ownerOrigin)
    }
}
