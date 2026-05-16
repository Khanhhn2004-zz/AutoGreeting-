package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class DiagnosticsReportRendererSourceTest {

    private fun readSource(relativePath: String): String {
        val path = Paths.get(relativePath)
        return if (Files.exists(path)) {
            String(Files.readAllBytes(path), UTF_8)
        } else {
            ""
        }
    }

    @Test
    fun `diagnostics report renderer defines the required top-level sections`() {
        val source = readSource("src/main/java/com/example/carchatbot/support/DiagnosticsReportRenderer.kt")

        assertTrue(source.contains("Executive Summary"))
        assertTrue(source.contains("Startup Verdict"))
        assertTrue(source.contains("Boot Flow Classification"))
        assertTrue(source.contains("Device Snapshot"))
        assertTrue(source.contains("Runtime Configuration Snapshot"))
        assertTrue(source.contains("Boot Playback State"))
        assertTrue(source.contains("Startup Diagnostics Timeline"))
        assertTrue(source.contains("Privacy Notes"))
    }

    @Test
    fun `diagnostics report renderer includes startup verdict compat summary and privacy-safe device fields`() {
        val source = readSource("src/main/java/com/example/carchatbot/support/DiagnosticsReportRenderer.kt")

        assertTrue(source.contains("startup_verdict="))
        assertTrue(source.contains("boot_like_signature_detected="))
        assertTrue(source.contains("boot_like_reason="))
        assertTrue(source.contains("boot_receiver_seen_in_current_candidate_window="))
        assertTrue(source.contains("compat_path_used="))
        assertTrue(source.contains("startup_signal_type="))
        assertTrue(source.contains("boot_receiver_action="))
        assertTrue(source.contains("startup_decision="))
        assertTrue(source.contains("execution_strategy="))
        assertTrue(source.contains("execution_reason="))
        assertTrue(source.contains("audio_focus_result="))
        assertTrue(source.contains("raw_boot_receiver_seen="))
        assertTrue(source.contains("boot_receiver_delivery_stage="))
        assertTrue(source.contains("Raw Boot Receiver Probe"))
        assertTrue(source.contains("receiver_locked_boot_completed"))
        assertTrue(source.contains("receiver_boot_completed"))
        assertTrue(source.contains("receiver_quickboot_poweron"))
        assertTrue(source.contains("process_visible_compat_startup"))
        assertTrue(source.contains("internal_ip="))
        assertTrue(source.contains("sanitize("))
    }
}
