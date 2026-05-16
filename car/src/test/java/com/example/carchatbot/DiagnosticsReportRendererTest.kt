package com.example.carchatbot

import com.example.carchatbot.boot.BootPlaybackPhase
import com.example.carchatbot.boot.BootPlaybackState
import com.example.carchatbot.boot.BootReceiverProbe
import com.example.carchatbot.boot.BootSignalOrigin
import com.example.carchatbot.boot.StartupMode
import com.example.carchatbot.data.remote.model.AppLogRequest
import com.example.carchatbot.data.remote.model.DeviceInfoRequest
import com.example.carchatbot.support.DiagnosticsPrivacySanitizer
import com.example.carchatbot.support.DiagnosticsReportInput
import com.example.carchatbot.support.DiagnosticsReportRenderer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportRendererTest {

    @Test
    fun `renderer includes sanitized recent critical error details instead of hiding stack traces`() {
        val renderer = DiagnosticsReportRenderer(DiagnosticsPrivacySanitizer())

        val report = renderer.render(
            DiagnosticsReportInput(
                exportedAt = "2026-04-19 22:10:00",
                startupMode = StartupMode.BOOT_COMPLETED,
                floatingButtonEnabled = true,
                isLoggedIn = true,
                remoteSyncBlocked = false,
                diagnosticsLogs = listOf(
                    AppLogRequest(
                        type = "BOOT",
                        tag = "BootReceiver",
                        message = "Accepted startup action: android.intent.action.LOCKED_BOOT_COMPLETED",
                        createdAt = 1L
                    ),
                    AppLogRequest(
                        type = "CRASH",
                        tag = "AndroidRuntime",
                        message = "IllegalStateException: The ACTION_HOVER_EXIT event was not cleared.",
                        extra = """
                            java.lang.IllegalStateException: The ACTION_HOVER_EXIT event was not cleared.
                            	at androidx.compose.ui.platform.AndroidComposeView.sendHoverExitEvent(AndroidComposeView.android.kt:565)
                            	at com.example.carchatbot.ui.main.MainActivity.dispatchGenericMotionEvent(MainActivity.kt:254)
                            	at token=abc123
                        """.trimIndent(),
                        createdAt = 2L
                    )
                ),
                bootPlaybackState = BootPlaybackState(
                    phase = BootPlaybackPhase.FAILED,
                    lastFailureReason = "compose_hover_exit"
                ),
                deviceInfo = DeviceInfoRequest(
                    brand = "alps",
                    model = "825X_Pro",
                    androidVersion = "10",
                    sdkVersion = 28,
                    screenResolution = "1280x480",
                    dpi = 240,
                    batteryLevel = 80,
                    isRooted = false,
                    networkType = "WIFI",
                    internalIp = "192.168.1.10",
                    gpsLocation = null,
                    appVersion = "1.0.1 (2)",
                    cpuArch = "arm64-v8a",
                    timezone = "Asia/Saigon",
                    carrier = "none",
                    language = "vi",
                    internetStatus = "Online",
                    overlay = true,
                    notification = true,
                    batteryOptimization = false,
                    accessibility = false
                ),
                supportEmail = "quanha191224@gmail.com",
                appName = "Chao Xe",
                appVersion = "1.0.1 (2)",
                buildPublishedAt = "2026-04-18 20:48 GMT+7"
            )
        )

        assertTrue(report.contains("[Recent Critical Errors]"))
        assertTrue(report.contains("IllegalStateException: The ACTION_HOVER_EXIT event was not cleared."))
        assertTrue(report.contains("AndroidComposeView.sendHoverExitEvent"))
        assertFalse(report.contains("token=abc123"))
        assertFalse(report.contains("stacktrace=trimmed_and_sanitized"))
    }

    @Test
    fun `renderer distinguishes locked boot receiver flow from other startup paths`() {
        val renderer = DiagnosticsReportRenderer(DiagnosticsPrivacySanitizer())

        val report = renderer.render(
            DiagnosticsReportInput(
                exportedAt = "2026-04-19 22:20:00",
                startupMode = StartupMode.BOOT_COMPLETED,
                floatingButtonEnabled = true,
                isLoggedIn = true,
                remoteSyncBlocked = false,
                diagnosticsLogs = listOf(
                    AppLogRequest(
                        type = "BOOT",
                        tag = "BootReceiver",
                        message = "Accepted startup action: android.intent.action.LOCKED_BOOT_COMPLETED",
                        createdAt = 1L
                    ),
                    AppLogRequest(
                        type = "BOOT",
                        tag = "BootPlaybackService",
                        message = "Boot playback foreground started",
                        createdAt = 2L
                    )
                ),
                bootPlaybackState = BootPlaybackState(
                    ownerOrigin = BootSignalOrigin.RECEIVER,
                    phase = BootPlaybackPhase.PLAYING,
                    sessionId = "boot-1",
                    startupWindowId = 1L
                ),
                deviceInfo = deviceInfo(),
                supportEmail = "quanha191224@gmail.com",
                appName = "Chao Xe",
                appVersion = "1.0.1 (2)",
                buildPublishedAt = "2026-04-19 19:37 GMT+7"
            )
        )

        assertTrue(report.contains("[Boot Flow Classification]"))
        assertTrue(report.contains("startup_signal_type=RECEIVER_BOOT"))
        assertTrue(report.contains("boot_receiver_action=android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertTrue(report.contains("observed_startup_path=receiver_locked_boot_completed"))
    }

    @Test
    fun `renderer exposes quickboot decision strategy and audio focus in boot summary`() {
        val renderer = DiagnosticsReportRenderer(DiagnosticsPrivacySanitizer())

        val report = renderer.render(
            DiagnosticsReportInput(
                exportedAt = "2026-04-28 20:45:00",
                startupMode = StartupMode.BOOT_COMPLETED,
                floatingButtonEnabled = true,
                isLoggedIn = true,
                remoteSyncBlocked = false,
                diagnosticsLogs = listOf(
                    AppLogRequest(
                        type = "BOOT",
                        tag = "BootReceiver",
                        message = "Accepted startup action: android.intent.action.QUICKBOOT_POWERON",
                        createdAt = 1L
                    ),
                    AppLogRequest(
                        type = "BOOT",
                        tag = "BootReceiver",
                        message = "Boot playback foreground start requested",
                        extra = "decision=START_NOW profile=HEAD_UNIT_SLEEP_WAKE strategy=START_FOREGROUND_SERVICE_NOW reason=receiver_boot_execution_allowed sessionId=boot-1 startupWindowId=1",
                        createdAt = 2L
                    ),
                    AppLogRequest(
                        type = "BOOT",
                        tag = "OneShotCachedAudioPlayer",
                        message = "Boot audio focus result",
                        extra = "result=GRANTED file=startup_sound.audio durationMs=67030",
                        createdAt = 3L
                    ),
                    AppLogRequest(
                        type = "BOOT",
                        tag = "BootPlaybackService",
                        message = "Boot playback audio completed",
                        createdAt = 4L
                    )
                ),
                bootPlaybackState = BootPlaybackState(
                    ownerOrigin = BootSignalOrigin.RECEIVER,
                    phase = BootPlaybackPhase.COMPLETED,
                    sessionId = "boot-1",
                    startupWindowId = 1L
                ),
                deviceInfo = deviceInfo(),
                supportEmail = "quanha191224@gmail.com",
                appName = "Chao Xe",
                appVersion = "1.0.1 (2)",
                buildPublishedAt = "2026-04-28 20:18 GMT+7"
            )
        )

        assertTrue(report.contains("boot_receiver_action=android.intent.action.QUICKBOOT_POWERON"))
        assertTrue(report.contains("observed_startup_path=receiver_quickboot_poweron"))
        assertTrue(report.contains("startup_decision=START_NOW"))
        assertTrue(report.contains("execution_strategy=START_FOREGROUND_SERVICE_NOW"))
        assertTrue(report.contains("execution_reason=receiver_boot_execution_allowed"))
        assertTrue(report.contains("audio_focus_result=GRANTED"))
        assertTrue(report.contains("known_boot_actions="))
        assertTrue(report.contains("android.intent.action.QUICKBOOT_POWERON"))
        assertTrue(report.contains("com.htc.intent.action.QUICKBOOT_POWERON"))
    }

    @Test
    fun `renderer distinguishes process visible compat startup from receiver boot`() {
        val renderer = DiagnosticsReportRenderer(DiagnosticsPrivacySanitizer())

        val report = renderer.render(
            DiagnosticsReportInput(
                exportedAt = "2026-04-19 22:25:00",
                startupMode = StartupMode.BOOT_COMPLETED,
                floatingButtonEnabled = true,
                isLoggedIn = true,
                remoteSyncBlocked = false,
                diagnosticsLogs = listOf(
                    AppLogRequest(
                        type = "BOOT",
                        tag = "MainActivity",
                        message = "Compat startup candidate detected from fresh visible launch",
                        extra = "reason=SMALL_UPTIME_NO_RECEIVER bootReceiverSeen=false elapsedRealtime=79136 fresh=true",
                        createdAt = 1L
                    ),
                    AppLogRequest(
                        type = "BOOT",
                        tag = "MainActivity",
                        message = "Compat startup started from process-visible compatibility path",
                        extra = "reason=SMALL_UPTIME_NO_RECEIVER bootReceiverSeen=false elapsedRealtime=79136 fresh=true",
                        createdAt = 2L
                    )
                ),
                bootPlaybackState = BootPlaybackState(
                    ownerOrigin = BootSignalOrigin.APP_AUTO_START,
                    phase = BootPlaybackPhase.PLAYING,
                    sessionId = "boot-2",
                    startupWindowId = 2L
                ),
                deviceInfo = deviceInfo(),
                supportEmail = "quanha191224@gmail.com",
                appName = "Chao Xe",
                appVersion = "1.0.1 (2)",
                buildPublishedAt = "2026-04-19 19:37 GMT+7"
            )
        )

        assertTrue(report.contains("startup_signal_type=PROCESS_VISIBLE_COMPAT_STARTUP"))
        assertTrue(report.contains("boot_receiver_action=none"))
        assertTrue(report.contains("observed_startup_path=process_visible_compat_startup"))
    }

    @Test
    fun `renderer reports floating button stop as user stopped instead of audio failure`() {
        val renderer = DiagnosticsReportRenderer(DiagnosticsPrivacySanitizer())

        val report = renderer.render(
            DiagnosticsReportInput(
                exportedAt = "2026-04-21 15:30:00",
                startupMode = StartupMode.BOOT_COMPLETED,
                floatingButtonEnabled = true,
                isLoggedIn = true,
                remoteSyncBlocked = false,
                diagnosticsLogs = listOf(
                    AppLogRequest(
                        type = "BOOT",
                        tag = "BootPlaybackService",
                        message = "Boot playback audio started",
                        createdAt = 1L
                    ),
                    AppLogRequest(
                        type = "AUDIO",
                        tag = "SoundPlayerService",
                        message = "Stopping playback session (explicit_stop)",
                        createdAt = 2L
                    )
                ),
                bootPlaybackState = BootPlaybackState(
                    ownerOrigin = BootSignalOrigin.APP_AUTO_START,
                    phase = BootPlaybackPhase.MANUALLY_STOPPED,
                    sessionId = "boot-3",
                    startupWindowId = 3L,
                    lastFailureReason = "floating_button_stop"
                ),
                deviceInfo = deviceInfo(),
                supportEmail = "quanha191224@gmail.com",
                appName = "Chao Xe",
                appVersion = "1.0.1 (2)",
                buildPublishedAt = "2026-04-21 13:14 GMT+7"
            )
        )

        assertTrue(report.contains("startup_verdict=playback_stopped_by_user"))
        assertTrue(report.contains("phase=MANUALLY_STOPPED"))
        assertTrue(report.contains("is_terminal_failure=false"))
        assertFalse(report.contains("startup_verdict=playback_failed_at_audio_runtime"))
    }

    @Test
    fun `downloaded user sound alone does not mean startup boot cache is playable`() {
        val renderer = DiagnosticsReportRenderer(DiagnosticsPrivacySanitizer())

        val report = renderer.render(
            DiagnosticsReportInput(
                exportedAt = "2026-04-24 22:30:00",
                startupMode = StartupMode.BOOT_COMPLETED,
                floatingButtonEnabled = true,
                isLoggedIn = true,
                remoteSyncBlocked = false,
                diagnosticsLogs = listOf(
                    AppLogRequest(
                        type = "LOG",
                        tag = "SoundAssetManager",
                        message = "Downloaded latest sound 1 to file:server_hello.audio",
                        createdAt = 1L
                    ),
                    AppLogRequest(
                        type = "BOOT",
                        tag = "BootPlaybackService",
                        message = "Boot playback cache missing",
                        extra = "reason=missing_metadata metadataExists=false audioExists=false",
                        createdAt = 2L
                    )
                ),
                bootPlaybackState = BootPlaybackState(
                    ownerOrigin = BootSignalOrigin.RECEIVER,
                    phase = BootPlaybackPhase.NO_CACHE,
                    sessionId = "boot-4",
                    startupWindowId = 4L,
                    lastFailureReason = "no_cache"
                ),
                deviceInfo = deviceInfo(),
                supportEmail = "quanha191224@gmail.com",
                appName = "Chao Xe",
                appVersion = "1.0.1 (2)",
                buildPublishedAt = "2026-04-24 18:46 GMT+7"
            )
        )

        assertTrue(report.contains("startup_verdict=playback_no_cache"))
        assertTrue(report.contains("playable_source_available=false"))
        assertTrue(report.contains("startup_cache_ready=false"))
        assertTrue(report.contains("startup_cache_reason=missing_metadata"))
        assertTrue(report.contains("details=reason=missing_metadata"))
    }

    @Test
    fun `renderer exposes raw receiver probe when injected boot logs are missing`() {
        val renderer = DiagnosticsReportRenderer(DiagnosticsPrivacySanitizer())

        val report = renderer.render(
            DiagnosticsReportInput(
                exportedAt = "2026-05-07 16:30:00",
                startupMode = StartupMode.BOOT_COMPLETED,
                floatingButtonEnabled = true,
                isLoggedIn = true,
                remoteSyncBlocked = false,
                diagnosticsLogs = emptyList(),
                bootPlaybackState = BootPlaybackState(),
                deviceInfo = deviceInfo(),
                supportEmail = "quanha191224@gmail.com",
                appName = "Chao Xe",
                appVersion = "1.0.1 (2)",
                buildPublishedAt = "2026-05-07 14:47 GMT+7",
                rawBootReceiverProbe = BootReceiverProbe(
                    action = "android.intent.action.BOOT_COMPLETED",
                    receivedAtMillis = 1_778_148_000_000L,
                    elapsedRealtimeMillis = 42_000L,
                    processId = 2222,
                    threadName = "main"
                )
            )
        )

        assertTrue(report.contains("boot_receiver_seen=false"))
        assertTrue(report.contains("raw_boot_receiver_seen=true"))
        assertTrue(report.contains("raw_boot_receiver_action=android.intent.action.BOOT_COMPLETED"))
        assertTrue(report.contains("boot_receiver_delivery_stage=raw_probe_only"))
        assertTrue(report.contains("startup_verdict=boot_receiver_raw_probe_only_runtime_or_logging_failed"))
        assertTrue(report.contains("[Raw Boot Receiver Probe]"))
        assertTrue(report.contains("elapsed_realtime_ms=42000"))
    }

    private fun deviceInfo(): DeviceInfoRequest {
        return DeviceInfoRequest(
            brand = "alps",
            model = "825X_Pro",
            androidVersion = "10",
            sdkVersion = 28,
            screenResolution = "1280x480",
            dpi = 240,
            batteryLevel = 80,
            isRooted = false,
            networkType = "WIFI",
            internalIp = "192.168.1.10",
            gpsLocation = null,
            appVersion = "1.0.1 (2)",
            cpuArch = "arm64-v8a",
            timezone = "Asia/Saigon",
            carrier = "none",
            language = "vi",
            internetStatus = "Online",
            overlay = true,
            notification = true,
            batteryOptimization = false,
            accessibility = false
        )
    }
}
