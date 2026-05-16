package com.example.carchatbot.boot

/**
 * Loại startup signal sau khi đã chuẩn hóa.
 */
enum class StartupSignalType {
    RECEIVER_BOOT,
    PROCESS_VISIBLE_RECOVERY,
    RUNTIME_RECONCILE_RECOVERY,
    PROCESS_VISIBLE_COMPAT_STARTUP,
    APP_AUTO_OPEN_EXPLICIT
}

/**
 * Startup event đã được chuẩn hóa để arbiter xử lý.
 *
 * Android broadcast thô, app visibility và runtime reconcile đều được chuyển
 * thành contract này để code phía sau không cần biết component nào đã quan sát
 * bằng chứng startup trước.
 */
data class StartupSignal(
    val origin: BootSignalOrigin,
    val type: StartupSignalType,
    val recoveryOnly: Boolean,
    val startupWindowIdOverride: Long? = null
)

/**
 * Factory tạo startup signal với ngữ nghĩa mode/origin rõ ràng.
 *
 * Invariant quan trọng: BOOT recovery vẫn thuộc ownership kiểu receiver, còn
 * `APP_AUTO_OPEN` vẫn thuộc ownership kiểu app-auto-start. Việc giữ ranh giới
 * này ngăn boot receiver, runtime reconcile hoặc visible recovery âm thầm đổi
 * product mode mà người dùng đã chọn.
 */
object StartupSignalContract {
    /**
     * Tạo signal cho BOOT broadcast do receiver nhận được.
     */
    fun receiverStartupSignal(startupWindowId: Long? = null): StartupSignal {
        return StartupSignal(
            origin = BootSignalOrigin.RECEIVER,
            type = StartupSignalType.RECEIVER_BOOT,
            recoveryOnly = false,
            startupWindowIdOverride = startupWindowId
        )
    }

    /**
     * Tạo signal recovery khi app trở nên visible và đang có BOOT window pending.
     */
    fun processVisibleRecoverySignal(startupWindowId: Long): StartupSignal {
        return StartupSignal(
            origin = BootSignalOrigin.RECEIVER,
            type = StartupSignalType.PROCESS_VISIBLE_RECOVERY,
            recoveryOnly = true,
            startupWindowIdOverride = startupWindowId
        )
    }

    /**
     * Tạo signal recovery khi runtime reconcile phát hiện BOOT window pending.
     */
    fun runtimeReconcileRecoverySignal(startupWindowId: Long): StartupSignal {
        return StartupSignal(
            origin = BootSignalOrigin.RECEIVER,
            type = StartupSignalType.RUNTIME_RECONCILE_RECOVERY,
            recoveryOnly = true,
            startupWindowIdOverride = startupWindowId
        )
    }

    /**
     * Tạo signal compat khi app visible giống một lần boot nhưng receiver không đủ bằng chứng.
     */
    fun processVisibleCompatStartupSignal(startupWindowId: Long? = null): StartupSignal {
        return StartupSignal(
            origin = BootSignalOrigin.BOOT_VISIBLE_RECOVERY,
            type = StartupSignalType.PROCESS_VISIBLE_COMPAT_STARTUP,
            recoveryOnly = false,
            startupWindowIdOverride = startupWindowId
        )
    }

    /**
     * Tạo signal explicit cho mode `APP_AUTO_OPEN`.
     */
    fun appOpenExplicitSignal(): StartupSignal {
        return StartupSignal(
            origin = BootSignalOrigin.APP_AUTO_START,
            type = StartupSignalType.APP_AUTO_OPEN_EXPLICIT,
            recoveryOnly = false
        )
    }

    /**
     * Chuyển origin cũ thành signal tương ứng để các caller legacy vẫn đi qua contract mới.
     */
    fun signalForOrigin(origin: BootSignalOrigin): StartupSignal {
        return when (origin) {
            BootSignalOrigin.RECEIVER -> receiverStartupSignal()
            BootSignalOrigin.BOOT_VISIBLE_RECOVERY -> processVisibleCompatStartupSignal()
            BootSignalOrigin.APP_AUTO_START -> appOpenExplicitSignal()
        }
    }
}
