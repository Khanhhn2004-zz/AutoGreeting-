package com.example.carchatbot.boot

import android.os.Build
import com.example.carchatbot.runtime.AppRuntimePolicies
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot ngắn gọn để đánh giá app-visible event có đủ giống một lần BOOT hay không.
 */
data class BootLikeVisibleCompatSnapshot(
    val startupWindowArmed: Boolean,
    val bootReceiverSeenInCurrentCandidateWindow: Boolean,
    val bootPlaybackOwnershipPresent: Boolean
)

/**
 * Kết quả chuẩn hóa startup signal sau khi đi qua coordinator.
 *
 * Object này gom cả arm state, execution plan, quyết định playback và cờ cho
 * biết path nào đã được dùng để support log không phải suy luận từ nhiều file.
 */
data class StartupCompatResult(
    val armState: StartupArmState,
    val executionPlan: StartupExecutionPlan,
    val playbackDecision: BootSoundArbiterDecision?,
    val usedRecoveryPath: Boolean,
    val explicitAppAutoOpen: Boolean,
    val usedVisibleCompatFallback: Boolean
)

/**
 * Bộ điều phối trung tâm cho mọi startup signal được phép ảnh hưởng đến audio.
 *
 * Độ ổn định của BOOT phụ thuộc vào việc mọi nguồn tín hiệu dùng chung một mô
 * hình: receiver signal là đường chính, runtime/process-visible recovery chỉ
 * chạy khi còn BOOT window đang chờ, và `APP_AUTO_OPEN` chỉ chạy khi người dùng
 * chọn đúng startup mode đó. Cách tách này giúp recovery rộng giống MacroDroid
 * nhưng không cho các lần app visible không liên quan giả dạng BOOT playback.
 */
@Singleton
class StartupCompatCoordinator @Inject constructor(
    private val startupArmStateStore: StartupArmStateStore,
    private val bootSoundArbiter: BootSoundArbiter,
    private val startupExecutionGate: StartupExecutionGate,
    private val bootPlaybackStateStore: BootPlaybackStateStore
) {

    /**
     * Xử lý tín hiệu BOOT rõ ràng do receiver nhận được và có thể chạy ngay.
     *
     * Method này arm một startup window bền vững trước khi hỏi arbiter xem ai
     * được sở hữu playback.
     */
    fun handleReceiverSignal(
        nowMillis: Long = System.currentTimeMillis(),
        profile: StartupCompatibilityProfile = StartupCompatibilityProfile.HEAD_UNIT_SLEEP_WAKE,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): StartupCompatResult {
        val startupWindowId = AppRuntimePolicies.calculateBootStartupWindowId(
            nowMillis,
            profile.startupWindowMillis
        )
        val armState = startupArmStateStore.armStartupWindow(
            startupWindowId = startupWindowId,
            origin = BootSignalOrigin.RECEIVER,
            compatibilityProfile = profile,
            nowMillis = nowMillis
        )
        val signal = StartupSignalContract.receiverStartupSignal(startupWindowId)
        val executionPlan = startupExecutionGate.planExecution(signal, profile, sdkInt)
        val playbackDecision = if (executionPlan.allowsForegroundServiceStart()) {
            bootSoundArbiter.tryHandleStartupSignal(
                signal,
                nowMillis
            )
        } else {
            null
        }
        return StartupCompatResult(
            armState = armState,
            executionPlan = executionPlan,
            playbackDecision = playbackDecision,
            usedRecoveryPath = false,
            explicitAppAutoOpen = false,
            usedVisibleCompatFallback = false
        )
    }

    /**
     * Ghi nhận bằng chứng locked boot nhưng chưa phát nhạc ngay.
     *
     * Android box thường phát `LOCKED_BOOT_COMPLETED` trước khi audio/storage ổn
     * định. Việc lưu BOOT window ở đây cho phép `BOOT_COMPLETED`, runtime
     * reconcile hoặc visible recovery sau đó hoàn tất cùng một lần BOOT.
     */
    fun handleLockedBootReceiverSignal(
        nowMillis: Long = System.currentTimeMillis(),
        sdkInt: Int = Build.VERSION.SDK_INT
    ): StartupCompatResult {
        val profile = StartupCompatibilityProfile.UNKNOWN_GENERIC
        val startupWindowId = AppRuntimePolicies.calculateBootStartupWindowId(
            nowMillis,
            profile.startupWindowMillis
        )
        val armState = startupArmStateStore.armStartupWindow(
            startupWindowId = startupWindowId,
            origin = BootSignalOrigin.RECEIVER,
            compatibilityProfile = profile,
            nowMillis = nowMillis
        )
        val signal = StartupSignalContract.receiverStartupSignal(startupWindowId)
        val executionPlan = startupExecutionGate.planExecution(signal, profile, sdkInt)

        return StartupCompatResult(
            armState = armState,
            executionPlan = executionPlan,
            playbackDecision = null,
            usedRecoveryPath = false,
            explicitAppAutoOpen = false,
            usedVisibleCompatFallback = false
        )
    }

    /**
     * Xử lý việc app trở nên visible theo đúng [startupMode].
     *
     * Method này cố ý giữ hai product mode tách biệt: `APP_AUTO_OPEN` có thể mở
     * một session explicit mới, còn BOOT mode chỉ được recovery BOOT window đang
     * chờ đã được arm trước đó.
     */
    fun handleProcessVisibleSignal(
        startupMode: StartupMode,
        nowMillis: Long = System.currentTimeMillis(),
        sdkInt: Int = Build.VERSION.SDK_INT
    ): StartupCompatResult? {
        if (StartupTriggerRouter.allowsAppAutoOpen(startupMode)) {
            expireDanglingPlaybackOwnership(
                nowMillis = nowMillis,
                failureReason = "startup_service_not_running"
            )
            val signal = StartupSignalContract.appOpenExplicitSignal()
            val executionPlan = startupExecutionGate.planExecution(
                signal = signal,
                profile = StartupCompatibilityProfile.DEFAULT,
                sdkInt = sdkInt
            )
            return StartupCompatResult(
                armState = startupArmStateStore.readState(),
                executionPlan = executionPlan,
                playbackDecision = if (executionPlan.allowsForegroundServiceStart()) {
                    bootSoundArbiter.tryHandleStartupSignal(signal, nowMillis)
                } else {
                    null
                },
                usedRecoveryPath = false,
                explicitAppAutoOpen = true,
                usedVisibleCompatFallback = false
            )
        }

        if (!StartupTriggerRouter.allowsBootCompleted(startupMode)) {
            return null
        }

        val currentArmState = startupArmStateStore.readState()
        val startupWindowId = currentArmState.startupWindowId ?: return null
        if (!currentArmState.isPending()) {
            return null
        }
        if (!currentArmState.compatibilityProfile.supports(StartupCompatibilityTrigger.PROCESS_VISIBLE)) {
            return null
        }
        val armedAtMillis = currentArmState.armedAtMillis ?: return null
        if (!AppRuntimePolicies.isWithinCompatibilityStartupGraceWindow(
                armedAtMillis = armedAtMillis,
                nowMillis = nowMillis,
                windowMillis = currentArmState.compatibilityProfile.startupWindowMillis
            )
        ) {
            return null
        }

        expireDanglingPlaybackOwnership(
            nowMillis = nowMillis,
            failureReason = "stale_startup_ownership"
        )
        val armState = startupArmStateStore.recordObservedOrigin(
            startupWindowId = startupWindowId,
            origin = BootSignalOrigin.BOOT_VISIBLE_RECOVERY,
            nowMillis = nowMillis
        )
        val signal = StartupSignalContract.processVisibleRecoverySignal(startupWindowId)
        val executionPlan = startupExecutionGate.planExecution(
            signal = signal,
            profile = armState.compatibilityProfile,
            sdkInt = sdkInt
        )
        val playbackDecision = if (executionPlan.allowsForegroundServiceStart()) {
            bootSoundArbiter.tryHandleStartupSignal(signal, nowMillis)
        } else {
            null
        }
        val consumedState = startupArmStateStore.markConsumed(
            startupWindowId = startupWindowId,
            nowMillis = nowMillis
        )

        return StartupCompatResult(
            armState = consumedState,
            executionPlan = executionPlan,
            playbackDecision = playbackDecision,
            usedRecoveryPath = true,
            explicitAppAutoOpen = false,
            usedVisibleCompatFallback = false
        )
    }

    /**
     * Cho phép runtime reconcile ở nền consume một BOOT window đang chờ.
     *
     * Đây là đường recovery không phụ thuộc UI, dùng khi nút nổi hoặc runtime
     * service lên trước màn hình chính. Đường này không bao giờ chạy cho `APP_AUTO_OPEN`.
     */
    fun handleRuntimeReconcileSignal(
        startupMode: StartupMode,
        nowMillis: Long = System.currentTimeMillis(),
        sdkInt: Int = Build.VERSION.SDK_INT
    ): StartupCompatResult? {
        if (!StartupTriggerRouter.allowsBootCompleted(startupMode)) {
            return null
        }

        val currentArmState = startupArmStateStore.readState()
        val startupWindowId = currentArmState.startupWindowId ?: return null
        if (!currentArmState.isPending()) {
            return null
        }
        val armedAtMillis = currentArmState.armedAtMillis ?: return null
        if (!AppRuntimePolicies.isWithinCompatibilityStartupGraceWindow(
                armedAtMillis = armedAtMillis,
                nowMillis = nowMillis,
                windowMillis = currentArmState.compatibilityProfile.startupWindowMillis
            )
        ) {
            return null
        }

        expireDanglingPlaybackOwnership(
            nowMillis = nowMillis,
            failureReason = "runtime_reconcile_stale_startup_ownership"
        )
        val armState = startupArmStateStore.recordObservedOrigin(
            startupWindowId = startupWindowId,
            origin = BootSignalOrigin.RECEIVER,
            nowMillis = nowMillis
        )
        val signal = StartupSignalContract.runtimeReconcileRecoverySignal(startupWindowId)
        val executionPlan = startupExecutionGate.planExecution(
            signal = signal,
            profile = armState.compatibilityProfile,
            sdkInt = sdkInt
        )
        val playbackDecision = if (executionPlan.allowsForegroundServiceStart()) {
            bootSoundArbiter.tryHandleStartupSignal(signal, nowMillis)
        } else {
            null
        }
        val consumedState = startupArmStateStore.markConsumed(
            startupWindowId = startupWindowId,
            nowMillis = nowMillis
        )

        return StartupCompatResult(
            armState = consumedState,
            executionPlan = executionPlan,
            playbackDecision = playbackDecision,
            usedRecoveryPath = true,
            explicitAppAutoOpen = false,
            usedVisibleCompatFallback = false
        )
    }

    /**
     * Fallback cho thiết bị không gửi receiver signal dùng được nhưng có bằng
     * chứng process-visible đủ mạnh cho một lần app start giống BOOT.
     *
     * Đường này được tách riêng khỏi receiver recovery để support log phân biệt
     * được "đã thấy receiver" và "compat startup được suy luận".
     */
    fun handleBootLikeProcessVisibleCompatSignal(
        nowMillis: Long = System.currentTimeMillis(),
        sdkInt: Int = Build.VERSION.SDK_INT
    ): StartupCompatResult {
        val profile = StartupCompatibilityProfile.UNKNOWN_GENERIC
        val startupWindowId = AppRuntimePolicies.calculateBootStartupWindowId(
            nowMillis = nowMillis,
            windowMillis = profile.startupWindowMillis
        )
        val armState = startupArmStateStore.armStartupWindow(
            startupWindowId = startupWindowId,
            origin = BootSignalOrigin.BOOT_VISIBLE_RECOVERY,
            compatibilityProfile = profile,
            nowMillis = nowMillis
        )
        val signal = StartupSignalContract.processVisibleCompatStartupSignal(startupWindowId)
        val executionPlan = startupExecutionGate.planExecution(
            signal = signal,
            profile = profile,
            sdkInt = sdkInt
        )
        val playbackDecision = if (executionPlan.allowsForegroundServiceStart()) {
            bootSoundArbiter.tryHandleStartupSignal(signal, nowMillis)
        } else {
            null
        }
        val finalArmState = if (playbackDecision != null) {
            startupArmStateStore.markConsumed(
                startupWindowId = startupWindowId,
                nowMillis = nowMillis
            )
        } else {
            armState
        }

        return StartupCompatResult(
            armState = finalArmState,
            executionPlan = executionPlan,
            playbackDecision = playbackDecision,
            usedRecoveryPath = false,
            explicitAppAutoOpen = false,
            usedVisibleCompatFallback = true
        )
    }

    /**
     * Trả về bằng chứng ngắn gọn để diagnostics giải thích vì sao một lần app
     * visible có hoặc không đủ điều kiện trở thành BOOT recovery.
     */
    fun readBootLikeVisibleCompatSnapshot(
        nowMillis: Long = System.currentTimeMillis()
    ): BootLikeVisibleCompatSnapshot {
        val armState = startupArmStateStore.readState()
        val playbackState = expireDanglingPlaybackOwnership(
            nowMillis = nowMillis,
            failureReason = "stale_startup_ownership"
        )
        val pendingWindowStillRelevant =
            armState.isPending() &&
                armState.armedAtMillis?.let { armedAtMillis ->
                    AppRuntimePolicies.isWithinCompatibilityStartupGraceWindow(
                        armedAtMillis = armedAtMillis,
                        nowMillis = nowMillis,
                        windowMillis = armState.compatibilityProfile.startupWindowMillis
                    )
                } == true
        val receiverEvidenceStillRelevant =
            armState.armedAtMillis?.let { armedAtMillis ->
                AppRuntimePolicies.isWithinCompatibilityStartupGraceWindow(
                    armedAtMillis = armedAtMillis,
                    nowMillis = nowMillis,
                    windowMillis = AppRuntimePolicies.bootLikeVisibleCompatibilityWindowMillis()
                )
            } == true

        return BootLikeVisibleCompatSnapshot(
            startupWindowArmed = pendingWindowStillRelevant,
            bootReceiverSeenInCurrentCandidateWindow =
                receiverEvidenceStillRelevant &&
                    BootSignalOrigin.RECEIVER in armState.observedOrigins,
            bootPlaybackOwnershipPresent = playbackState.isActive()
        )
    }

    /**
     * Dọn ownership bị treo trước khi recovery path thử claim lại BOOT playback.
     *
     * Nếu state đang active nhưng service không còn chạy, session được mark
     * failed với reason cụ thể để arbiter có thể quyết định retry/takeover.
     */
    private fun expireDanglingPlaybackOwnership(
        nowMillis: Long,
        failureReason: String
    ): BootPlaybackState {
        val current = bootPlaybackStateStore.readState()
        if (current.isActive() && !BootPlaybackService.isServiceMarkedRunning()) {
            val sessionId = current.sessionId
            if (!sessionId.isNullOrBlank()) {
                return bootPlaybackStateStore.markFailed(
                    sessionId = sessionId,
                    failureReason = failureReason,
                    nowMillis = nowMillis
                )
            }
        }

        return bootPlaybackStateStore.expireStaleActiveSession(
            nowMillis = nowMillis,
            staleTimeoutMillis = AppRuntimePolicies.bootSessionStaleTimeoutMillis(),
            failureReason = failureReason
        )
    }
}
