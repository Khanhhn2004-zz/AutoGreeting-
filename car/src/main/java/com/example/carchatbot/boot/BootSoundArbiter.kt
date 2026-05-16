package com.example.carchatbot.boot

import com.example.carchatbot.runtime.AppRuntimePolicies
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hành động mà arbiter trả về sau khi xét một startup signal.
 */
enum class BootSoundArbiterAction {
    START_NOW,
    WAIT_EXISTING,
    TAKEOVER_STALE,
    REJECT_SECOND_TAKEOVER,
    SKIP_ALREADY_HANDLED
}

/**
 * Kết quả quyết định một startup signal có được sở hữu BOOT playback hay không.
 */
data class BootSoundArbiterDecision(
    val action: BootSoundArbiterAction,
    val state: BootPlaybackState
)

/**
 * Cổng ownership duy nhất cho startup playback.
 *
 * MacroDroid có thể bắn action trực tiếp vì nó là automation tool. App này có
 * product mode, manual playback, diagnostics và duplicate boot signal, nên mọi
 * tín hiệu BOOT/app-open/recovery phải claim hoặc tái sử dụng một session bền
 * vững tại đây trước khi bất kỳ service nào được phép phát audio.
 */
@Singleton
class BootSoundArbiter @Inject constructor(
    private val stateStore: BootPlaybackStateStore
) {
    /**
     * API tiện ích cho caller cũ chỉ biết [BootSignalOrigin].
     */
    fun tryHandleStartupSignal(
        origin: BootSignalOrigin,
        nowMillis: Long = System.currentTimeMillis()
    ): BootSoundArbiterDecision = arbitrate(StartupSignalContract.signalForOrigin(origin), nowMillis)

    /**
     * API chính để xử lý một startup signal đã chuẩn hóa.
     */
    fun tryHandleStartupSignal(
        signal: StartupSignal,
        nowMillis: Long = System.currentTimeMillis()
    ): BootSoundArbiterDecision = arbitrate(signal, nowMillis)

    /**
     * API tương thích cho caller cũ cần tên `arbitrate` nhưng chỉ truyền origin.
     */
    fun arbitrate(
        origin: BootSignalOrigin,
        nowMillis: Long = System.currentTimeMillis()
    ): BootSoundArbiterDecision = arbitrate(StartupSignalContract.signalForOrigin(origin), nowMillis)

    /**
     * Áp dụng luật chống phát trùng, takeover stale session và recovery-only cho
     * một startup signal.
     */
    fun arbitrate(
        signal: StartupSignal,
        nowMillis: Long = System.currentTimeMillis()
    ): BootSoundArbiterDecision {
        val startupWindowId = signal.startupWindowIdOverride
            ?: AppRuntimePolicies.calculateBootStartupWindowId(nowMillis)
        val current = stateStore.readState()
        val sameWindow = current.startupWindowId == startupWindowId
        val bypassTerminalSameWindowDedup =
            signal.type == StartupSignalType.APP_AUTO_OPEN_EXPLICIT

        if (
            signal.type == StartupSignalType.RECEIVER_BOOT &&
            current.ownerOrigin == BootSignalOrigin.RECEIVER &&
            current.isActive() &&
            BootPlaybackService.isServiceMarkedRunning()
        ) {
            return BootSoundArbiterDecision(
                action = BootSoundArbiterAction.WAIT_EXISTING,
                state = current
            )
        }

        if (!current.isActive()) {
            if (
                sameWindow &&
                !bypassTerminalSameWindowDedup &&
                (
                    current.phase == BootPlaybackPhase.COMPLETED ||
                    current.phase == BootPlaybackPhase.MANUALLY_STOPPED ||
                    current.phase == BootPlaybackPhase.NO_CACHE
                )
            ) {
                return BootSoundArbiterDecision(
                    action = BootSoundArbiterAction.SKIP_ALREADY_HANDLED,
                    state = current
                )
            }
            if (sameWindow && !bypassTerminalSameWindowDedup && current.phase == BootPlaybackPhase.FAILED) {
                if (current.lastFailureReason.isRecoverableSameWindowFailure()) {
                    return startNewSession(signal.origin, startupWindowId, nowMillis, takeoverCount = 0)
                }
                return BootSoundArbiterDecision(
                    action = BootSoundArbiterAction.SKIP_ALREADY_HANDLED,
                    state = current
                )
            }
            return startNewSession(signal.origin, startupWindowId, nowMillis, takeoverCount = 0)
        }

        val stale = current.lastProgressAt == null ||
            nowMillis - current.lastProgressAt >= AppRuntimePolicies.bootSessionStaleTimeoutMillis()

        if (sameWindow && !stale) {
            return BootSoundArbiterDecision(
                action = BootSoundArbiterAction.WAIT_EXISTING,
                state = current
            )
        }

        if (sameWindow && stale && current.takeoverCount == 0) {
            return startNewSession(
                origin = signal.origin,
                startupWindowId = startupWindowId,
                nowMillis = nowMillis,
                takeoverCount = current.takeoverCount + 1
            )
        }

        if (sameWindow && stale) {
            return BootSoundArbiterDecision(
                action = BootSoundArbiterAction.REJECT_SECOND_TAKEOVER,
                state = current
            )
        }

        if (signal.recoveryOnly) {
            return BootSoundArbiterDecision(
                action = BootSoundArbiterAction.SKIP_ALREADY_HANDLED,
                state = current
            )
        }

        return startNewSession(signal.origin, startupWindowId, nowMillis, takeoverCount = 0)
    }

    /**
     * Claim một session mới và trả về action tương ứng với start đầu tiên hoặc takeover.
     */
    private fun startNewSession(
        origin: BootSignalOrigin,
        startupWindowId: Long,
        nowMillis: Long,
        takeoverCount: Int
    ): BootSoundArbiterDecision {
        val sessionId = sessionIdFor(startupWindowId, origin, takeoverCount)
        val claimed = stateStore.claimSession(
            startupWindowId = startupWindowId,
            sessionId = sessionId,
            ownerOrigin = origin,
            nowMillis = nowMillis,
            takeoverCount = takeoverCount
        )

        return BootSoundArbiterDecision(
            action = if (takeoverCount == 0) {
                BootSoundArbiterAction.START_NOW
            } else {
                BootSoundArbiterAction.TAKEOVER_STALE
            },
            state = claimed
        )
    }

    /**
     * Sinh session id ổn định theo startup window, origin và số lần takeover.
     */
    private fun sessionIdFor(
        startupWindowId: Long,
        origin: BootSignalOrigin,
        takeoverCount: Int
    ): String {
        return "boot-$startupWindowId-${origin.name.lowercase()}-$takeoverCount"
    }

    /**
     * Xác định lỗi terminal nào được phép thử lại trong cùng startup window.
     */
    private fun String?.isRecoverableSameWindowFailure(): Boolean {
        return this == "startup_service_not_running" ||
            this?.startsWith("stale_") == true ||
            this?.startsWith("foreground_service_start_") == true
    }
}
