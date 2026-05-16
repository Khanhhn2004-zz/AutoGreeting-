package com.example.carchatbot.boot

/**
 * Nguồn sở hữu của một startup playback session.
 *
 * Origin này xuất hiện trong session id và diagnostics để phân biệt playback do
 * receiver, BOOT visible recovery hay `APP_AUTO_OPEN` tạo ra.
 */
enum class BootSignalOrigin {
    RECEIVER,
    BOOT_VISIBLE_RECOVERY,
    APP_AUTO_START
}
