package com.example.carchatbot.boot

/**
 * Router product-mode cho các startup trigger.
 *
 * Object này cố ý rất chặt: BOOT signal chỉ chạy khi người dùng chọn
 * [StartupMode.BOOT_COMPLETED], còn explicit app-open playback chỉ chạy khi
 * người dùng chọn [StartupMode.APP_AUTO_OPEN].
 */
object StartupTriggerRouter {
    /**
     * Trả về true khi startup mode hiện tại cho phép xử lý BOOT broadcast/recovery.
     */
    fun allowsBootCompleted(startupMode: StartupMode): Boolean {
        return startupMode == StartupMode.BOOT_COMPLETED
    }

    /**
     * Trả về true khi startup mode hiện tại cho phép phát khi app được mở explicit.
     */
    fun allowsAppAutoOpen(startupMode: StartupMode): Boolean {
        return startupMode == StartupMode.APP_AUTO_OPEN
    }
}
