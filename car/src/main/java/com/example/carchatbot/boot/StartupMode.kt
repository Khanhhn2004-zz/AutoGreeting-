package com.example.carchatbot.boot

/**
 * Product mode quyết định app sẽ tự phát nhạc theo điều kiện nào.
 *
 * `BOOT_COMPLETED` và `APP_AUTO_OPEN` là hai contract riêng; không được dùng
 * một nhánh để lấp lỗi cho nhánh còn lại nếu chưa có quyết định product rõ ràng.
 */
enum class StartupMode {
    OFF,
    BOOT_COMPLETED,
    APP_AUTO_OPEN;

    companion object {
        val DEFAULT = StartupMode.BOOT_COMPLETED
    }
}
