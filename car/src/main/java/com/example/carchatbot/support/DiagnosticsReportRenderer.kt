package com.example.carchatbot.support

import com.example.carchatbot.boot.BootPlaybackPhase
import com.example.carchatbot.boot.BootPlaybackState
import com.example.carchatbot.boot.BootReceiverProbe
import com.example.carchatbot.boot.BootSignalOrigin
import com.example.carchatbot.boot.StartupMode
import com.example.carchatbot.boot.StartupSignalType
import com.example.carchatbot.boot.StartupTriggerRouter
import com.example.carchatbot.data.remote.model.AppLogRequest
import com.example.carchatbot.data.remote.model.DeviceInfoRequest
import com.example.carchatbot.service.BootCompletedReceiver
import com.example.carchatbot.utils.DeviceUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class DiagnosticsReportInput(
    val exportedAt: String,
    val startupMode: StartupMode,
    val floatingButtonEnabled: Boolean,
    val isLoggedIn: Boolean,
    val remoteSyncBlocked: Boolean,
    val diagnosticsLogs: List<AppLogRequest>,
    val bootPlaybackState: BootPlaybackState,
    val rawBootReceiverProbe: BootReceiverProbe? = null,
    val deviceInfo: DeviceInfoRequest,
    val supportEmail: String,
    val appName: String,
    val appVersion: String,
    val buildPublishedAt: String
)

@Singleton
class DiagnosticsReportRenderer @Inject constructor(
    private val sanitizer: DiagnosticsPrivacySanitizer
) {

    fun render(input: DiagnosticsReportInput): String {
        val diagnostics = input.diagnosticsLogs.sortedBy { it.createdAt }
        val recentCriticalErrors = diagnostics.filter(::isCriticalErrorLog).takeLast(MAX_RECENT_CRITICAL_ERRORS)
        val compatSummary = summarizeCompatDiagnostics(diagnostics)
        val bootReceiverSeen = diagnostics.any { it.tag == "BootReceiver" }
        val rawBootReceiverSeen = input.rawBootReceiverProbe != null
        val rawBootReceiverAction = input.rawBootReceiverProbe?.action ?: "none"
        val bootReceiverDeliveryStage = deriveBootReceiverDeliveryStage(
            bootReceiverSeen = bootReceiverSeen,
            rawBootReceiverSeen = rawBootReceiverSeen
        )
        val bootFlowSummary = summarizeBootFlow(
            diagnostics = diagnostics,
            compatSummary = compatSummary,
            bootPlaybackState = input.bootPlaybackState,
            bootReceiverSeen = bootReceiverSeen,
            startupMode = input.startupMode
        )
        val bootExecutionSummary = summarizeBootExecution(diagnostics)
        val observedStartupPath = deriveObservedStartupPath(
            bootFlowSummary = bootFlowSummary,
            rawBootReceiverAction = rawBootReceiverAction
        )
        val startupVerdict = deriveStartupVerdict(
            startupMode = input.startupMode,
            observedStartupPath = observedStartupPath,
            bootPlaybackState = input.bootPlaybackState,
            isLoggedIn = input.isLoggedIn,
            bootReceiverSeen = bootReceiverSeen,
            rawBootReceiverSeen = rawBootReceiverSeen,
            compatSummary = compatSummary
        )
        val startupCacheSummary = inferStartupCacheSummary(diagnostics, input.bootPlaybackState)
        val playableSourceAvailable = startupCacheSummary.ready
        val lastReasonCode = compatSummary.bootLikeReason.takeUnless { it == "none" }
            ?: input.bootPlaybackState.lastFailureReason
            ?: inferReasonCodeFromLogs(diagnostics)
            ?: "none"
        val lastFailureStage = inferFailureStage(input.bootPlaybackState, diagnostics)

        return buildString {
            appendLine("report_version=1")
            appendLine("app_name=${sanitize(input.appName)}")
            appendLine("app_version=${sanitize(input.appVersion)}")
            appendLine("build_published_at=${sanitize(input.buildPublishedAt)}")
            appendLine("exported_at=${sanitize(input.exportedAt)}")
            appendLine("support_email=${sanitize(input.supportEmail)}")
            appendLine()

            appendLine("[Executive Summary]")
            appendLine("startup_mode=${input.startupMode.name}")
            appendLine("expected_startup_behavior=${sanitize(describeExpectedBehavior(input.startupMode))}")
            appendLine("observed_startup_path=$observedStartupPath")
            appendLine("startup_verdict=$startupVerdict")
            appendLine("last_failure_stage=$lastFailureStage")
            appendLine("last_reason_code=${sanitize(lastReasonCode)}")
            appendLine("boot_receiver_seen=$bootReceiverSeen")
            appendLine("raw_boot_receiver_seen=$rawBootReceiverSeen")
            appendLine("raw_boot_receiver_action=${sanitize(rawBootReceiverAction)}")
            appendLine("boot_receiver_delivery_stage=$bootReceiverDeliveryStage")
            appendLine("compat_path_used=${compatSummary.startupCompatPathUsed}")
            appendLine("playable_source_available=$playableSourceAvailable")
            appendLine("startup_cache_ready=${startupCacheSummary.ready}")
            appendLine("startup_cache_reason=${sanitize(startupCacheSummary.reason)}")
            appendLine("session_present=${input.isLoggedIn}")
            appendLine()

            appendLine("[Startup Verdict]")
            appendLine("startup_mode=${input.startupMode.name}")
            appendLine("startup_allows_boot_completed=${StartupTriggerRouter.allowsBootCompleted(input.startupMode)}")
            appendLine("startup_allows_app_auto_open=${StartupTriggerRouter.allowsAppAutoOpen(input.startupMode)}")
            appendLine("expected_startup_behavior=${sanitize(describeExpectedBehavior(input.startupMode))}")
            appendLine("observed_startup_path=$observedStartupPath")
            appendLine("startup_verdict=$startupVerdict")
            appendLine("last_failure_stage=$lastFailureStage")
            appendLine("last_reason_code=${sanitize(lastReasonCode)}")
            appendLine("boot_receiver_seen=$bootReceiverSeen")
            appendLine("raw_boot_receiver_seen=$rawBootReceiverSeen")
            appendLine("raw_boot_receiver_action=${sanitize(rawBootReceiverAction)}")
            appendLine("boot_receiver_delivery_stage=$bootReceiverDeliveryStage")
            appendLine("compat_path_used=${compatSummary.startupCompatPathUsed}")
            appendLine("boot_like_signature_detected=${compatSummary.bootLikeSignatureDetected}")
            appendLine("boot_like_reason=${sanitize(compatSummary.bootLikeReason)}")
            appendLine("boot_receiver_seen_in_current_candidate_window=${compatSummary.bootReceiverSeenInCurrentCandidateWindow}")
            appendLine("playable_source_available=$playableSourceAvailable")
            appendLine("startup_cache_ready=${startupCacheSummary.ready}")
            appendLine("startup_cache_reason=${sanitize(startupCacheSummary.reason)}")
            appendLine()

            appendLine("[Boot Flow Classification]")
            appendLine("startup_signal_type=${bootFlowSummary.startupSignalType}")
            appendLine("boot_receiver_action=${sanitize(bootFlowSummary.bootReceiverAction)}")
            appendLine("raw_boot_receiver_action=${sanitize(rawBootReceiverAction)}")
            appendLine("boot_receiver_delivery_stage=$bootReceiverDeliveryStage")
            appendLine("startup_decision=${sanitize(bootExecutionSummary.startupDecision)}")
            appendLine("execution_strategy=${sanitize(bootExecutionSummary.executionStrategy)}")
            appendLine("execution_reason=${sanitize(bootExecutionSummary.executionReason)}")
            appendLine("audio_focus_result=${sanitize(bootExecutionSummary.audioFocusResult)}")
            appendLine("boot_origin=${sanitize(bootFlowSummary.bootOrigin)}")
            appendLine("recovery_only=${bootFlowSummary.recoveryOnly}")
            appendLine("compat_path_used=${compatSummary.startupCompatPathUsed}")
            appendLine("observed_startup_path=$observedStartupPath")
            appendLine()

            appendLine("[Raw Boot Receiver Probe]")
            appendLine("raw_boot_receiver_seen=$rawBootReceiverSeen")
            appendLine("action=${sanitize(rawBootReceiverAction)}")
            appendLine("received_at=${formatEpoch(input.rawBootReceiverProbe?.receivedAtMillis)}")
            appendLine("elapsed_realtime_ms=${input.rawBootReceiverProbe?.elapsedRealtimeMillis ?: "null"}")
            appendLine("process_id=${input.rawBootReceiverProbe?.processId ?: "null"}")
            appendLine("thread=${sanitize(input.rawBootReceiverProbe?.threadName ?: "null")}")
            appendLine()

            appendLine("[Device Snapshot]")
            appendLine("device_brand=${sanitize(input.deviceInfo.brand)}")
            appendLine("device_model=${sanitize(input.deviceInfo.model)}")
            appendLine("android_version=${sanitize(input.deviceInfo.androidVersion)}")
            appendLine("sdk_version=${input.deviceInfo.sdkVersion}")
            appendLine("screen_resolution=${sanitize(input.deviceInfo.screenResolution)}")
            appendLine("dpi=${input.deviceInfo.dpi}")
            appendLine("cpu_arch=${sanitize(input.deviceInfo.cpuArch)}")
            appendLine("network_type=${sanitize(input.deviceInfo.networkType)}")
            appendLine("internet_status=${sanitize(input.deviceInfo.internetStatus)}")
            appendLine("overlay_granted=${input.deviceInfo.overlay}")
            appendLine("accessibility_enabled=${input.deviceInfo.accessibility}")
            appendLine("battery_optimization_ignored=${input.deviceInfo.batteryOptimization}")
            appendLine("notification_permission=${input.deviceInfo.notification}")
            appendLine("app_language=${sanitize(input.deviceInfo.language)}")
            appendLine("timezone=${sanitize(input.deviceInfo.timezone)}")
            appendLine("carrier=${sanitize(input.deviceInfo.carrier)}")
            appendLine("internal_ip=${sanitize(input.deviceInfo.internalIp)}")
            appendLine()

            appendLine("[Runtime Configuration Snapshot]")
            appendLine("is_logged_in=${input.isLoggedIn}")
            appendLine("remote_sync_blocked=${input.remoteSyncBlocked}")
            appendLine("floating_button_enabled=${input.floatingButtonEnabled}")
            appendLine("known_boot_actions=${BootCompletedReceiver.approvedStartupActions().sorted().joinToString(",")}")
            appendLine("known_startup_paths=BOOT_COMPLETED,LOCKED_BOOT_COMPLETED,APP_AUTO_OPEN,PROCESS_VISIBLE_RECOVERY,PROCESS_VISIBLE_COMPAT_STARTUP")
            appendLine()

            appendLine("[Boot Playback State]")
            appendLine("startupWindowId=${input.bootPlaybackState.startupWindowId}")
            appendLine("sessionId=${sanitize(input.bootPlaybackState.sessionId)}")
            appendLine("ownerOrigin=${input.bootPlaybackState.ownerOrigin}")
            appendLine("phase=${input.bootPlaybackState.phase}")
            appendLine("lastProgressAt=${formatEpoch(input.bootPlaybackState.lastProgressAt)}")
            appendLine("takeoverCount=${input.bootPlaybackState.takeoverCount}")
            appendLine("lastFailureReason=${sanitize(input.bootPlaybackState.lastFailureReason)}")
            appendLine("lastNotificationStartupWindowId=${input.bootPlaybackState.lastNotificationStartupWindowId}")
            appendLine("has_startup_window=${input.bootPlaybackState.startupWindowId != null}")
            appendLine("has_session=${!input.bootPlaybackState.sessionId.isNullOrBlank()}")
            appendLine("has_owner_origin=${input.bootPlaybackState.ownerOrigin != null}")
            appendLine("is_terminal_success=${input.bootPlaybackState.phase == BootPlaybackPhase.COMPLETED}")
            appendLine("is_terminal_failure=${input.bootPlaybackState.phase == BootPlaybackPhase.FAILED || input.bootPlaybackState.phase == BootPlaybackPhase.NO_CACHE}")
            appendLine()

            appendLine("[Startup Diagnostics Timeline]")
            diagnostics.forEach { log ->
                appendLine(renderTimelineLine(log))
            }
            appendLine()

            appendLine("[Recent Critical Errors]")
            appendLine("critical_error_count=${recentCriticalErrors.size}")
            if (recentCriticalErrors.isEmpty()) {
                appendLine("none")
            } else {
                recentCriticalErrors.forEachIndexed { index, log ->
                    append(renderCriticalErrorBlock(index + 1, log))
                }
            }
            appendLine()

            appendLine("[Privacy Notes]")
            appendLine("phone=masked")
            appendLine("email=masked")
            appendLine("token=redacted")
            appendLine("ip=masked")
            appendLine("absolute_paths=collapsed_to_basename")
            appendLine("stacktrace=sanitized_recent_critical_errors_included")
        }
    }

    private fun renderTimelineLine(log: AppLogRequest): String {
        val eventKey = deriveEventKey(log)
        val reasonCode = inferReasonCode(log)
        val failureStage = inferFailureStage(log)
        val details = sanitize(log.extra)
        return buildString {
            append(formatEpoch(log.createdAt))
            append(" | ")
            append(log.type)
            append(" | ")
            append(sanitize(log.tag))
            append(" | event_key=")
            append(eventKey)
            append(" | message=")
            append(sanitize(log.message))
            append(" | reason_code=")
            append(reasonCode)
            append(" | failure_stage=")
            append(failureStage)
            if (details.isNotBlank()) {
                append(" | details=")
                append(details)
            }
        }
    }

    private fun renderCriticalErrorBlock(index: Int, log: AppLogRequest): String {
        val details = sanitize(log.extra)
        return buildString {
            appendLine("error_index=$index")
            appendLine("timestamp=${formatEpoch(log.createdAt)}")
            appendLine("type=${sanitize(log.type)}")
            appendLine("tag=${sanitize(log.tag)}")
            appendLine("message=${sanitize(log.message)}")
            if (details.isNotBlank()) {
                appendLine("details_begin")
                details.lineSequence().forEach { line ->
                    appendLine(line)
                }
                appendLine("details_end")
            }
            appendLine()
        }
    }

    private fun summarizeCompatDiagnostics(logs: List<AppLogRequest>): CompatDiagnosticsSummary {
        val latestCompatLog = logs.lastOrNull { it.tag == "MainActivity" && it.message.startsWith("Compat startup") }
        val parsedExtra = parseCompatExtra(latestCompatLog?.extra)
        val pathUsed = when {
            logs.any { it.tag == "MainActivity" && it.message == "Compat startup started from process-visible compatibility path" } -> "compat_visible"
            logs.any { it.tag == "MainActivity" && it.message == "Consuming pending startup window through process-visible recovery" } -> "recovery"
            logs.any { it.tag == "MainActivity" && it.message.contains("APP_AUTO_OPEN", ignoreCase = true) } -> "app_auto_open"
            else -> "none"
        }

        return CompatDiagnosticsSummary(
            bootLikeSignatureDetected = latestCompatLog?.message?.contains("candidate detected") == true ||
                latestCompatLog?.message?.contains("started from process-visible compatibility path") == true,
            bootLikeReason = parsedExtra["reason"] ?: "none",
            bootReceiverSeenInCurrentCandidateWindow =
                parsedExtra["bootReceiverSeen"]?.toBooleanStrictOrNull() ?: false,
            startupCompatPathUsed = pathUsed
        )
    }

    private fun parseCompatExtra(extra: String?): Map<String, String> {
        return parseKeyValueFields(extra)
    }

    private fun parseKeyValueFields(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) {
            return emptyMap()
        }

        return text
            .split(' ')
            .mapNotNull { token ->
                val separatorIndex = token.indexOf('=')
                if (separatorIndex <= 0) null else token.substring(0, separatorIndex) to token.substring(separatorIndex + 1)
            }
            .toMap()
    }

    private fun describeExpectedBehavior(startupMode: StartupMode): String {
        return when (startupMode) {
            StartupMode.OFF -> "No startup playback should occur automatically"
            StartupMode.BOOT_COMPLETED -> "The app should start playback when the device performs a boot-like startup"
            StartupMode.APP_AUTO_OPEN -> "The app should start playback when the app is explicitly opened"
        }
    }

    private fun deriveObservedStartupPath(
        bootFlowSummary: BootFlowSummary,
        rawBootReceiverAction: String
    ): String {
        return when {
            bootFlowSummary.startupSignalType == StartupSignalType.PROCESS_VISIBLE_COMPAT_STARTUP.name -> "process_visible_compat_startup"
            bootFlowSummary.startupSignalType == StartupSignalType.PROCESS_VISIBLE_RECOVERY.name -> "process_visible_recovery"
            bootFlowSummary.bootReceiverAction == "android.intent.action.LOCKED_BOOT_COMPLETED" -> "receiver_locked_boot_completed"
            bootFlowSummary.bootReceiverAction == "android.intent.action.BOOT_COMPLETED" -> "receiver_boot_completed"
            bootFlowSummary.bootReceiverAction == ACTION_QUICKBOOT_POWERON -> "receiver_quickboot_poweron"
            bootFlowSummary.bootReceiverAction == ACTION_HTC_QUICKBOOT_POWERON -> "receiver_htc_quickboot_poweron"
            bootFlowSummary.startupSignalType == StartupSignalType.RECEIVER_BOOT.name -> "receiver_boot"
            bootFlowSummary.startupSignalType == StartupSignalType.APP_AUTO_OPEN_EXPLICIT.name -> "app_auto_open"
            rawBootReceiverAction != "none" -> "raw_receiver_probe_only"
            else -> "none"
        }
    }

    private fun deriveBootReceiverDeliveryStage(
        bootReceiverSeen: Boolean,
        rawBootReceiverSeen: Boolean
    ): String {
        return when {
            bootReceiverSeen && rawBootReceiverSeen -> "app_logger_and_raw_probe"
            rawBootReceiverSeen -> "raw_probe_only"
            bootReceiverSeen -> "app_logger_only"
            else -> "none"
        }
    }

    private fun deriveStartupVerdict(
        startupMode: StartupMode,
        observedStartupPath: String,
        bootPlaybackState: BootPlaybackState,
        isLoggedIn: Boolean,
        bootReceiverSeen: Boolean,
        rawBootReceiverSeen: Boolean,
        compatSummary: CompatDiagnosticsSummary
    ): String {
        return when {
            bootPlaybackState.phase == BootPlaybackPhase.COMPLETED &&
                (observedStartupPath == "receiver_boot" ||
                    observedStartupPath == "receiver_boot_completed" ||
                    observedStartupPath == "receiver_locked_boot_completed" ||
                    observedStartupPath == "receiver_quickboot_poweron" ||
                    observedStartupPath == "receiver_htc_quickboot_poweron") -> "receiver_boot_completed"
            bootPlaybackState.phase == BootPlaybackPhase.COMPLETED && observedStartupPath == "process_visible_recovery" -> "process_visible_recovery_completed"
            bootPlaybackState.phase == BootPlaybackPhase.COMPLETED && observedStartupPath == "process_visible_compat_startup" -> "compat_visible_started"
            bootPlaybackState.phase == BootPlaybackPhase.MANUALLY_STOPPED -> "playback_stopped_by_user"
            bootPlaybackState.phase == BootPlaybackPhase.NO_CACHE -> "playback_no_cache"
            bootPlaybackState.phase == BootPlaybackPhase.FAILED -> "playback_failed_at_${inferFailureStage(bootPlaybackState, emptyList())}"
            startupMode == StartupMode.BOOT_COMPLETED && rawBootReceiverSeen && !bootReceiverSeen && bootPlaybackState.phase == BootPlaybackPhase.IDLE && isLoggedIn -> "boot_receiver_raw_probe_only_runtime_or_logging_failed"
            startupMode == StartupMode.BOOT_COMPLETED && !bootReceiverSeen && bootPlaybackState.phase == BootPlaybackPhase.IDLE && isLoggedIn && compatSummary.startupCompatPathUsed == "none" -> "no_startup_signal_runtime_only"
            startupMode == StartupMode.APP_AUTO_OPEN && bootPlaybackState.phase == BootPlaybackPhase.IDLE -> "app_open_not_triggered"
            else -> "state_unknown"
        }
    }

    private fun inferStartupCacheSummary(
        logs: List<AppLogRequest>,
        state: BootPlaybackState
    ): StartupCacheSummary {
        val latestCacheLog = logs.lastOrNull(::isStartupCacheReadinessLog)
        if (latestCacheLog != null) {
            val reason = parseReason(latestCacheLog.extra)
            return when {
                latestCacheLog.message.contains("Startup sound cache ready", ignoreCase = true) ||
                    latestCacheLog.message.contains("Boot playback audio started", ignoreCase = true) ->
                    StartupCacheSummary(ready = true, reason = reason ?: "ready")

                latestCacheLog.message.contains("Boot playback cache missing", ignoreCase = true) ||
                    latestCacheLog.message.contains("Startup sound cache not ready", ignoreCase = true) ->
                    StartupCacheSummary(ready = false, reason = reason ?: "no_cache")

                else -> StartupCacheSummary(ready = false, reason = reason ?: "unknown")
            }
        }

        return when (state.phase) {
            BootPlaybackPhase.PLAYING,
            BootPlaybackPhase.COMPLETED -> StartupCacheSummary(ready = true, reason = "ready")
            BootPlaybackPhase.NO_CACHE -> StartupCacheSummary(
                ready = false,
                reason = state.lastFailureReason ?: "no_cache"
            )
            else -> StartupCacheSummary(ready = false, reason = "unknown")
        }
    }

    private fun isStartupCacheReadinessLog(log: AppLogRequest): Boolean {
        return log.message.contains("Startup sound cache ready", ignoreCase = true) ||
            log.message.contains("Startup sound cache not ready", ignoreCase = true) ||
            log.message.contains("Boot playback cache missing", ignoreCase = true) ||
            log.message.contains("Boot playback audio started", ignoreCase = true)
    }

    private fun parseReason(extra: String?): String? {
        if (extra.isNullOrBlank()) {
            return null
        }

        return extra
            .split(' ')
            .firstOrNull { it.startsWith("reason=") }
            ?.substringAfter("reason=")
            ?.takeIf { it.isNotBlank() }
    }

    private fun inferReasonCodeFromLogs(logs: List<AppLogRequest>): String? {
        return logs.lastOrNull()?.let(::inferReasonCode)?.takeUnless { it == "none" }
    }

    private fun inferReasonCode(log: AppLogRequest): String {
        val message = log.message
        return when {
            message.startsWith("Accepted startup action") -> "approved_boot_action"
            message.startsWith("Ignored duplicate startup action") -> "duplicate_boot_action"
            message.contains("signature incomplete", ignoreCase = true) -> "signature_incomplete"
            message.contains("candidate detected", ignoreCase = true) -> "compat_candidate_detected"
            message.contains("started from process-visible compatibility path", ignoreCase = true) -> "compat_visible_started"
            message.contains("no playable source", ignoreCase = true) -> "no_playable_source"
            message.contains("Unauthorized", ignoreCase = true) -> "unauthorized"
            message.contains("Stopping playback", ignoreCase = true) -> "playback_stop"
            else -> "none"
        }
    }

    private fun inferFailureStage(log: AppLogRequest): String {
        val message = log.message
        return when {
            log.tag == "BootReceiver" -> "trigger_detection"
            message.contains("signature", ignoreCase = true) -> "startup_signature"
            message.contains("cache", ignoreCase = true) -> "cache_resolution"
            message.contains("foreground", ignoreCase = true) -> "foreground_service_start"
            message.contains("prepared", ignoreCase = true) || message.contains("preparing", ignoreCase = true) -> "audio_prepare"
            message.contains("audio focus", ignoreCase = true) -> "audio_focus"
            message.contains("Starting playback", ignoreCase = true) || message.contains("Playback completed", ignoreCase = true) -> "audio_runtime"
            else -> "none"
        }
    }

    private fun inferFailureStage(state: BootPlaybackState, logs: List<AppLogRequest>): String {
        return when (state.phase) {
            BootPlaybackPhase.NO_CACHE -> "cache_resolution"
            BootPlaybackPhase.FAILED -> logs.lastOrNull()?.let(::inferFailureStage) ?: "audio_runtime"
            BootPlaybackPhase.STARTING -> "foreground_service_start"
            BootPlaybackPhase.PLAYING -> "audio_runtime"
            BootPlaybackPhase.COMPLETED -> "completion"
            else -> "none"
        }
    }

    private fun summarizeBootFlow(
        diagnostics: List<AppLogRequest>,
        compatSummary: CompatDiagnosticsSummary,
        bootPlaybackState: BootPlaybackState,
        bootReceiverSeen: Boolean,
        startupMode: StartupMode
    ): BootFlowSummary {
        val bootReceiverAction = diagnostics
            .lastOrNull { it.tag == "BootReceiver" && it.message.startsWith("Accepted startup action:") }
            ?.message
            ?.substringAfter("Accepted startup action:")
            ?.trim()
            ?: "none"

        val startupSignalType = when {
            compatSummary.startupCompatPathUsed == "compat_visible" -> StartupSignalType.PROCESS_VISIBLE_COMPAT_STARTUP.name
            compatSummary.startupCompatPathUsed == "recovery" -> StartupSignalType.PROCESS_VISIBLE_RECOVERY.name
            bootReceiverAction != "none" || bootPlaybackState.ownerOrigin == BootSignalOrigin.RECEIVER || bootReceiverSeen ->
                StartupSignalType.RECEIVER_BOOT.name
            bootPlaybackState.ownerOrigin == BootSignalOrigin.APP_AUTO_START && startupMode == StartupMode.APP_AUTO_OPEN ->
                StartupSignalType.APP_AUTO_OPEN_EXPLICIT.name
            else -> "none"
        }

        val recoveryOnly = startupSignalType == StartupSignalType.PROCESS_VISIBLE_RECOVERY.name
        val bootOrigin = bootPlaybackState.ownerOrigin?.name ?: "none"

        return BootFlowSummary(
            startupSignalType = startupSignalType,
            bootReceiverAction = bootReceiverAction,
            bootOrigin = bootOrigin,
            recoveryOnly = recoveryOnly
        )
    }

    private fun summarizeBootExecution(logs: List<AppLogRequest>): BootExecutionSummary {
        val startRequestLog = logs.lastOrNull { log ->
            log.message == "Boot playback foreground start requested" ||
                log.message.startsWith("Runtime BOOT reconcile decision=")
        }
        val requestFields = parseKeyValueFields(
            listOfNotNull(startRequestLog?.message, startRequestLog?.extra).joinToString(" ")
        )
        val audioFocusLog = logs.lastOrNull { log ->
            log.tag == "OneShotCachedAudioPlayer" &&
                log.message.contains("Boot audio focus result", ignoreCase = true)
        }
        val audioFocusFields = parseKeyValueFields(audioFocusLog?.extra)

        return BootExecutionSummary(
            startupDecision = requestFields["decision"] ?: "none",
            executionStrategy = requestFields["strategy"] ?: "none",
            executionReason = requestFields["reason"] ?: "none",
            audioFocusResult = audioFocusFields["result"] ?: "none"
        )
    }

    private fun deriveEventKey(log: AppLogRequest): String {
        val message = log.message
        return when {
            log.tag == "BootReceiver" && message.startsWith("Event received") -> "boot.receiver.event_received"
            log.tag == "BootReceiver" && message.startsWith("Accepted startup action") -> "boot.receiver.accepted"
            log.tag == "BootReceiver" && message.startsWith("Ignored duplicate startup action") -> "boot.receiver.ignored_duplicate"
            log.tag == "BootReceiver" && message.contains("restore requested", ignoreCase = true) -> "boot.receiver.restore_requested"
            log.tag == "BootReceiver" && message.contains("restore skipped", ignoreCase = true) -> "boot.receiver.restore_skipped"
            log.tag == "MainActivity" && message.contains("candidate detected", ignoreCase = true) -> "startup.compat.candidate_detected"
            log.tag == "MainActivity" && message.contains("started from process-visible compatibility path", ignoreCase = true) -> "startup.compat.started"
            log.tag == "MainActivity" && message.contains("signature incomplete", ignoreCase = true) -> "startup.compat.skipped"
            log.tag == "OneShotCachedAudioPlayer" && message.contains("Boot audio prepared", ignoreCase = true) -> "boot.playback.audio_prepared"
            log.tag == "OneShotCachedAudioPlayer" && message.contains("Boot audio focus result", ignoreCase = true) -> "boot.playback.audio_focus_result"
            log.tag == "OneShotCachedAudioPlayer" && message.contains("Boot audio player started", ignoreCase = true) -> "boot.playback.boot_audio_started"
            log.tag == "SoundPlayerService" && message.contains("Starting playback", ignoreCase = true) -> "boot.playback.audio_started"
            log.tag == "SoundPlayerService" && message.contains("Playback completed", ignoreCase = true) -> "boot.playback.audio_completed"
            log.tag == "SoundPlayerService" && message.contains("Stopping playback", ignoreCase = true) -> "boot.playback.audio_stopped"
            else -> "${log.tag.lowercase(Locale.US)}.${log.type.lowercase(Locale.US)}"
        }
    }

    private fun isCriticalErrorLog(log: AppLogRequest): Boolean {
        return log.type == "CRASH" ||
            log.type == "EXCEPTION" ||
            log.type == "WORKER_ERROR"
    }

    private fun sanitize(value: Any?): String {
        return sanitizer.sanitizeText(value?.toString())
    }

    private fun formatEpoch(value: Long?): String {
        if (value == null) {
            return "null"
        }
        return DATE_FORMAT.format(Date(value))
    }

    private data class CompatDiagnosticsSummary(
        val bootLikeSignatureDetected: Boolean,
        val bootLikeReason: String,
        val bootReceiverSeenInCurrentCandidateWindow: Boolean,
        val startupCompatPathUsed: String
    )

    private data class StartupCacheSummary(
        val ready: Boolean,
        val reason: String
    )

    private data class BootFlowSummary(
        val startupSignalType: String,
        val bootReceiverAction: String,
        val bootOrigin: String,
        val recoveryOnly: Boolean
    )

    private data class BootExecutionSummary(
        val startupDecision: String,
        val executionStrategy: String,
        val executionReason: String,
        val audioFocusResult: String
    )

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        private const val MAX_RECENT_CRITICAL_ERRORS = 5
    }
}
