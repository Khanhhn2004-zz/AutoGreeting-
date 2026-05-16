package com.example.carchatbot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.carchatbot.boot.BootReceiverProbeStore
import androidx.core.content.ContextCompat
import com.example.carchatbot.boot.BootPlaybackService
import com.example.carchatbot.boot.BootSoundArbiterAction
import com.example.carchatbot.boot.StartupCompatCoordinator
import com.example.carchatbot.boot.StartupTriggerRouter
import com.example.carchatbot.data.local.UserPreferencesRepository
import com.example.carchatbot.runtime.AppRuntimePolicies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Điểm vào chỉ dành cho nhánh BOOT khi Android hoặc Android box phát broadcast khởi động.
 *
 * Receiver này cố ý chỉ làm phần việc tối thiểu nhưng phải bền vững: ghi nhận
 * rằng app đã thấy tín hiệu giống BOOT, khôi phục runtime service nếu được phép,
 * rồi chuyển quyền quyết định phát nhạc cho [StartupCompatCoordinator]. Receiver
 * không được phụ thuộc `MainActivity` hoặc nhánh `APP_AUTO_OPEN`; UI chỉ được
 * dùng để recovery một BOOT window đang chờ mà receiver hoặc runtime reconcile
 * đã arm trước đó.
 */
@dagger.hilt.android.AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @javax.inject.Inject
    lateinit var appLogger: com.example.carchatbot.utils.AppLogger

    @javax.inject.Inject
    lateinit var startupCompatCoordinator: StartupCompatCoordinator

    @javax.inject.Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    companion object {
        const val TAG = "BootCompletedReceiver"
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
        private val APPROVED_STARTUP_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT_POWERON
        )
        private var lastBootInitAction: String? = null
        private var lastBootInitAtMillis: Long? = null

        /**
         * Danh sách broadcast được xem là bằng chứng BOOT đủ rõ cho nhánh BOOT.
         *
         * Danh sách này đi theo nguyên tắc giống MacroDroid: chấp nhận các
         * quick-boot broadcast phổ biến của OEM, nhưng loại bỏ những sự kiện mơ
         * hồ như nguồn điện hoặc sạc vì chúng có thể xảy ra ngoài một lần khởi động thật.
         */
        fun approvedStartupActions(): Set<String> = APPROVED_STARTUP_ACTIONS

        /**
         * Kiểm tra một action có nằm trong allow-list BOOT cụ thể hay không.
         */
        fun isApprovedStartupAction(action: String): Boolean = action in APPROVED_STARTUP_ACTIONS
    }

    /**
     * Entry point Android gọi khi receiver nhận được broadcast.
     *
     * Hàm này lọc action, chống duplicate `LOCKED_BOOT_COMPLETED`/`BOOT_COMPLETED`,
     * rồi chuyển xử lý nặng sang [handleBootEventAsync] để không block main thread.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        BootReceiverProbeStore.recordRawReceive(context, action)
        Log.d(TAG, "****** BootCompletedReceiver: Received intent: $action ******")
        
        try {
            appLogger.logBoot("BootReceiver", "Event received: $action", "action=$action")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log to AppLogger", e)
        }

        if (action == null) {
            Log.d(TAG, "Received intent with null action.")
            return
        }

        if (!isApprovedStartupAction(action)) {
            Log.d(TAG, "Rejected startup action: $action")
            appLogger.logBoot("BootReceiver", "Rejected startup action: $action", "action=$action")
            return
        }

        val now = System.currentTimeMillis()
        val (shouldHandle, retryFloatingRestore) = synchronized(BootCompletedReceiver::class.java) {
            val previousAction = lastBootInitAction
            val previousHandledAtMillis = lastBootInitAtMillis
            val handle = AppRuntimePolicies.shouldHandleBootInitAction(
                action = action,
                lastHandledAction = previousAction,
                lastHandledAtMillis = previousHandledAtMillis,
                nowMillis = now
            )
            val retryFloatingRestore =
                !handle &&
                    AppRuntimePolicies.shouldRetryFloatingRestoreOnUnlockedBootCompleted(
                        action = action,
                        lastHandledAction = previousAction,
                        lastHandledAtMillis = previousHandledAtMillis,
                        nowMillis = now
                    )

            if (handle) {
                lastBootInitAction = action
                lastBootInitAtMillis = now
            }

            handle to retryFloatingRestore
        }

        if (!shouldHandle) {
            if (retryFloatingRestore) {
                Log.d(TAG, "Reattempting floating button restore on BOOT_COMPLETED after locked boot")
                appLogger.logBoot(
                    "BootReceiver",
                    "Reattempting floating button restore on BOOT_COMPLETED after locked boot",
                    "action=$action previousAction=${Intent.ACTION_LOCKED_BOOT_COMPLETED}"
                )
                restoreFloatingButtonAfterBootSafe(context)
            }
            Log.d(TAG, "Ignoring duplicate boot init action: $action")
            appLogger.logBoot("BootReceiver", "Ignored duplicate startup action: $action", "action=$action")
            return
        }

        Log.d(TAG, "Accepted startup action: $action")
        appLogger.logBoot("BootReceiver", "Accepted startup action: $action", "action=$action")
        handleBootEventAsync(context, action)
    }

    /**
     * Chạy toàn bộ xử lý BOOT broadcast đã được chấp nhận trong một scope [goAsync].
     *
     * Khôi phục nút nổi, kiểm tra startup mode, arm playback và runtime reconcile
     * được chạy tuần tự trong cùng một [BroadcastReceiver.PendingResult]. Nhờ vậy
     * Android không release receiver khi quyết định BOOT bền vững vẫn đang được ghi.
     */
    private fun handleBootEventAsync(context: Context, action: String) {
        val pendingResult: BroadcastReceiver.PendingResult? = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Task 1: Restore floating button overlay
                try {
                    restoreFloatingButtonAfterBoot(appContext)
                } catch (error: Exception) {
                    Log.e(TAG, "Floating button restore failed", error)
                    appLogger.logBoot(
                        "BootReceiver",
                        "Floating button restore failed",
                        "${error.javaClass.simpleName}: ${error.message}"
                    )
                }
                // Task 2: Handle startup signal (playback)
                try {
                    val startupMode = userPreferencesRepository.startupMode.first()
                    if (StartupTriggerRouter.allowsBootCompleted(startupMode)) {
                        if (shouldDeferPlaybackUntilUnlockedBoot(action)) {
                            val compatResult = startupCompatCoordinator.handleLockedBootReceiverSignal()
                            appLogger.logBoot(
                                "BootReceiver",
                                "Boot playback armed until BOOT_COMPLETED",
                                "mode=$startupMode action=$action profile=${compatResult.armState.compatibilityProfile} strategy=${compatResult.executionPlan.strategy} reason=${compatResult.executionPlan.reason} startupWindowId=${compatResult.armState.startupWindowId}"
                            )
                        } else {
                            handleStartupSignal(appContext)
                        }
                    } else {
                        appLogger.logBoot(
                            "BootReceiver",
                            "Startup playback skipped for mode=$startupMode",
                            "mode=$startupMode action=$action"
                        )
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Startup signal handling failed", error)
                    appLogger.logBoot(
                        "BootReceiver",
                        "Startup signal handling failed",
                        "${error.javaClass.simpleName}: ${error.message} action=$action"
                    )
                }

                requestRuntimeReconcileAfterBoot(appContext, action)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    /**
     * Tạm hoãn phát trực tiếp ở locked boot vì credential-protected storage,
     * audio routing và foreground service trên đầu Android thường chưa ổn định
     * tại thời điểm này.
     */
    private fun shouldDeferPlaybackUntilUnlockedBoot(action: String): Boolean {
        return action == Intent.ACTION_LOCKED_BOOT_COMPLETED
    }

    /**
     * Yêu cầu [CoreService] kiểm tra lại BOOT window đang chờ sau khi runtime
     * service đã chạy. Đây là đường recovery của nhánh BOOT khi receiver chạy
     * quá sớm hoặc process bị kill trước khi kịp bắt đầu phát nhạc.
     */
    private fun requestRuntimeReconcileAfterBoot(context: Context, action: String) {
        val reconcileIntent = Intent(context, CoreService::class.java).apply {
            this.action = CoreService.ACTION_RECONCILE_RUNTIME_SERVICES
        }
        try {
            ContextCompat.startForegroundService(context, reconcileIntent)
            appLogger.logBoot(
                "BootReceiver",
                "Runtime reconcile requested after boot",
                "action=$action"
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "Runtime reconcile request failed after boot", error)
            appLogger.logBoot(
                "BootReceiver",
                "Runtime reconcile request failed after boot",
                "action=$action error=${error.javaClass.simpleName}:${error.message}"
            )
        }
    }

    /**
     * Chuyển một tín hiệu BOOT rõ ràng từ receiver thành một quyết định duy nhất của arbiter.
     *
     * Toàn bộ logic chống phát trùng, takeover session bị treo và điều kiện được
     * phép start foreground service nằm sau [StartupCompatCoordinator] và
     * [BootPlaybackService]; receiver này không bao giờ tự phát audio trực tiếp.
     */
    private fun handleStartupSignal(context: Context) {
        val compatResult = startupCompatCoordinator.handleReceiverSignal()
        val decision = compatResult.playbackDecision ?: run {
            appLogger.logBoot(
                "BootReceiver",
                "Startup playback deferred for profile=${compatResult.armState.compatibilityProfile} strategy=${compatResult.executionPlan.strategy}",
                "profile=${compatResult.armState.compatibilityProfile} strategy=${compatResult.executionPlan.strategy}"
            )
            return
        }
        when (decision.action) {
            BootSoundArbiterAction.START_NOW,
            BootSoundArbiterAction.TAKEOVER_STALE -> {
                val started = BootPlaybackService.startForExecution(
                    context = context,
                    decision = decision,
                    executionPlan = compatResult.executionPlan
                )
                if (started) {
                    appLogger.logBoot(
                        "BootReceiver",
                        "Boot playback foreground start requested",
                        "decision=${decision.action} profile=${compatResult.armState.compatibilityProfile} strategy=${compatResult.executionPlan.strategy} reason=${compatResult.executionPlan.reason} sessionId=${decision.state.sessionId} startupWindowId=${decision.state.startupWindowId}"
                    )
                } else {
                    appLogger.logBoot(
                        "BootReceiver",
                        "Boot playback foreground start rejected",
                        "decision=${decision.action} profile=${compatResult.armState.compatibilityProfile} strategy=${compatResult.executionPlan.strategy} reason=${compatResult.executionPlan.reason} sessionId=${decision.state.sessionId} startupWindowId=${decision.state.startupWindowId}"
                    )
                }
            }

            BootSoundArbiterAction.WAIT_EXISTING,
            BootSoundArbiterAction.REJECT_SECOND_TAKEOVER,
            BootSoundArbiterAction.SKIP_ALREADY_HANDLED -> {
                appLogger.logBoot(
                    "BootReceiver",
                    "Startup playback decision=${decision.action}",
                    "decision=${decision.action} profile=${compatResult.armState.compatibilityProfile} strategy=${compatResult.executionPlan.strategy}"
                )
            }
        }
    }

    /**
     * Khôi phục nút nổi an toàn cho retry path.
     *
     * Hàm này dùng scope [goAsync] riêng để receiver không bị release quá sớm.
     */
    private fun restoreFloatingButtonAfterBootSafe(context: Context) {
        val pendingResult: PendingResult? = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                restoreFloatingButtonAfterBoot(appContext)
            } catch (error: Exception) {
                Log.e(TAG, "Floating button restore failed", error)
                appLogger.logBoot(
                    "BootReceiver",
                    "Floating button restore failed",
                    "${error.javaClass.simpleName}: ${error.message}"
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }

    /**
     * Khôi phục nút nổi theo setting hiện tại của user sau boot.
     *
     * Đây là runtime UI restore, không phải nguồn phát nhạc; playback vẫn phải
     * đi qua startup coordinator và arbiter.
     */
    private suspend fun restoreFloatingButtonAfterBoot(context: Context) {
        val isLoggedIn = userPreferencesRepository.isLoggedIn.first()
        val floatingButtonEnabled = userPreferencesRepository.floatingButtonEnabled.first()
        val canDrawOverlays = AppRuntimePolicies.canDrawOverlaysCompat(context)

        if (!AppRuntimePolicies.shouldStartFloatingButton(isLoggedIn, floatingButtonEnabled, canDrawOverlays)) {
            appLogger.logBoot(
                "BootReceiver",
                "Floating button restore skipped",
                "loggedIn=$isLoggedIn enabled=$floatingButtonEnabled overlay=$canDrawOverlays"
            )
            return
        }

        ContextCompat.startForegroundService(
            context,
            FloatingButtonService.createStartIntent(
                context = context,
                launchReason = FloatingButtonService.LAUNCH_REASON_BOOT_RESTORE
            )
        )
        appLogger.logBoot("BootReceiver", "FloatingButtonService restore requested")
    }
}
