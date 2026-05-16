package com.example.carchatbot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carchatbot.boot.BootPlaybackPhase
import com.example.carchatbot.boot.BootPlaybackState
import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.boot.BootSignalOrigin
import com.example.carchatbot.boot.BootStorageContextProvider
import com.example.carchatbot.boot.StartupDiagnosticsRepository
import com.example.carchatbot.data.remote.model.AppLogRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealDevicePostBootStateInstrumentedTest {

    @Test
    fun reboot_boot_path_reaches_receiver_owned_startup_session_before_test_attach() {
        assumeManualRealDeviceFlowEnabled()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bootContext = BootStorageContextProvider(context)
        // Attaching instrumentation force-stops the target process on Android,
        // so this helper only verifies that reboot reached a receiver-owned startup session.
        val state = waitForObservedBootState(BootPlaybackStateStore(bootContext))
        val diagnostics = StartupDiagnosticsRepository(bootContext).snapshot()

        assumeTrue(
            "Skipping post-boot verification because reboot precondition is absent. " +
                "Run seed_boot_cache_and_restore_prerequisites_for_real_device_boot_run, reboot the device, then run this test.",
            state.startupWindowId != null
        )

        assertNotNull("Expected startupWindowId to be recorded after reboot", state.startupWindowId)
        assertFalse("Expected a non-blank boot playback session id", state.sessionId.isNullOrBlank())
        assertEquals(BootSignalOrigin.RECEIVER, state.ownerOrigin)
        assertFalse(
            "Expected boot playback state to move beyond IDLE after reboot, got: $state",
            state.phase == BootPlaybackPhase.IDLE
        )
        assertNotNull("Expected lastProgressAt to be recorded after reboot", state.lastProgressAt)

        assertTrue(
            "Expected accepted boot action in diagnostics, got: ${diagnostics.describeMessages()}",
            diagnostics.anyAcceptedBootAction()
        )
        assertTrue(
            "Expected floating button restore request in diagnostics, got: ${diagnostics.describeMessages()}",
            diagnostics.anyFloatingButtonRestoreRequest()
        )
    }

    private fun waitForObservedBootState(stateStore: BootPlaybackStateStore): BootPlaybackState {
        repeat(60) {
            val state = stateStore.readState()
            if (state.phase != BootPlaybackPhase.IDLE) {
                return state
            }
            Thread.sleep(500)
        }
        return stateStore.readState()
    }

    private fun List<AppLogRequest>.anyAcceptedBootAction(): Boolean {
        return any { log ->
            log.tag == "BootReceiver" &&
                log.message.startsWith("Accepted startup action: android.intent.action.")
        }
    }

    private fun List<AppLogRequest>.anyFloatingButtonRestoreRequest(): Boolean {
        return any { log ->
            log.tag == "BootReceiver" &&
                log.message == "FloatingButtonService restore requested"
        }
    }

    private fun List<AppLogRequest>.describeMessages(): String {
        return joinToString(separator = " | ") { log ->
            buildString {
                append("${log.tag}:${log.message}")
                if (!log.extra.isNullOrBlank()) {
                    append(" [${log.extra}]")
                }
            }
        }
    }
}
