package com.example.carchatbot.boot

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Policy gate quyết định một startup signal đã chuẩn hóa có được execute ngay hay không.
 *
 * Gate này tách riêng "đã thấy bằng chứng startup" khỏi "Android cho phép app
 * start foreground playback service ngay bây giờ". Receiver BOOT có thể bị defer
 * theo compatibility profile, còn recovery và explicit app-open signal chạy qua
 * đường foreground service bình thường.
 */
@Singleton
class StartupExecutionGate @Inject constructor() {

    /**
     * Tạo execution plan để receiver, runtime reconcile và UI recovery dùng
     * trước khi hỏi arbiter có được start playback hay không.
     */
    fun planExecution(
        signal: StartupSignal,
        profile: StartupCompatibilityProfile,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): StartupExecutionPlan {
        return when (signal.type) {
            StartupSignalType.APP_AUTO_OPEN_EXPLICIT -> {
                StartupExecutionPlan.direct("explicit_app_auto_open")
            }

            StartupSignalType.PROCESS_VISIBLE_COMPAT_STARTUP -> {
                StartupExecutionPlan.direct("process_visible_compat_startup")
            }

            StartupSignalType.PROCESS_VISIBLE_RECOVERY -> {
                StartupExecutionPlan.direct("process_visible_recovery")
            }

            StartupSignalType.RUNTIME_RECONCILE_RECOVERY -> {
                StartupExecutionPlan.direct("runtime_reconcile_recovery")
            }

            StartupSignalType.RECEIVER_BOOT -> {
                when {
                    profile.requiresVisibleExecutionSignal -> {
                        StartupExecutionPlan.deferred("profile_requires_visible_execution_signal")
                    }

                    !profile.attemptsDirectBootExecution -> {
                        StartupExecutionPlan.deferred("profile_disallows_direct_boot_execution")
                    }

                    else -> {
                        StartupExecutionPlan.direct("receiver_boot_execution_allowed")
                    }
                }
            }
        }
    }
}
