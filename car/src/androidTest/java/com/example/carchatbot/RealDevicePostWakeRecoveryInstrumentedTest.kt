package com.example.carchatbot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carchatbot.boot.BootPlaybackPhase
import com.example.carchatbot.boot.BootPlaybackState
import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.boot.BootSignalOrigin
import com.example.carchatbot.boot.BootStorageContextProvider
import com.example.carchatbot.boot.StartupArmStateStore
import com.example.carchatbot.boot.StartupCompatibilityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealDevicePostWakeRecoveryInstrumentedTest {

    @Test
    fun sleep_wake_recovery_consumes_pending_arm_state_through_process_visible_path() {
        assumeManualRealDeviceFlowEnabled()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bootContext = BootStorageContextProvider(context)
        val armState = StartupArmStateStore(bootContext).readState()

        assumeTrue(
            "Skipping post-wake verification because sleep/wake precondition is absent. " +
                "Run seed_pending_startup_window_for_manual_sleep_wake_recovery, sleep/wake the device, then run this test.",
            armState.startupWindowId != null
        )

        val playbackState = waitForObservedRecoveryState(BootPlaybackStateStore(bootContext))

        assertNotNull("Expected startupWindowId to remain recorded after wake recovery", armState.startupWindowId)
        assertEquals(
            StartupCompatibilityProfile.HEAD_UNIT_SLEEP_WAKE,
            armState.compatibilityProfile
        )
        assertFalse(
            "Expected pending arm state to be consumed after wake recovery, got: $armState",
            armState.isPending()
        )
        assertTrue(
            "Expected receiver and process-visible origins after wake recovery, got: ${armState.observedOrigins}",
            armState.observedOrigins.containsAll(
                setOf(BootSignalOrigin.RECEIVER, BootSignalOrigin.APP_AUTO_START)
            )
        )
        assertNotNull("Expected consumedAtMillis to be recorded after wake recovery", armState.consumedAtMillis)

        assertEquals(armState.startupWindowId, playbackState.startupWindowId)
        assertEquals(BootSignalOrigin.RECEIVER, playbackState.ownerOrigin)
        assertFalse(
            "Expected wake recovery playback state to move beyond IDLE, got: $playbackState",
            playbackState.phase == BootPlaybackPhase.IDLE
        )
        assertNotNull("Expected lastProgressAt after wake recovery", playbackState.lastProgressAt)
    }

    private fun waitForObservedRecoveryState(stateStore: BootPlaybackStateStore): BootPlaybackState {
        repeat(60) {
            val state = stateStore.readState()
            if (state.ownerOrigin == BootSignalOrigin.RECEIVER && state.phase != BootPlaybackPhase.IDLE) {
                return state
            }
            Thread.sleep(500)
        }
        return stateStore.readState()
    }
}
