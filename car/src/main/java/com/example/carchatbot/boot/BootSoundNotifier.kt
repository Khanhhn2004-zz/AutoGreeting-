package com.example.carchatbot.boot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.carchatbot.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quản lý notification khi BOOT playback không có cache để phát.
 *
 * Notification này giúp người dùng biết cần mở app để tải/chọn lại âm thanh,
 * đồng thời state store ghi nhận window đã notify để tránh lặp notification.
 */
@Singleton
class BootSoundNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateStore: BootPlaybackStateStore
) {

    /**
     * Hiển thị no-cache notification một lần cho mỗi startup window.
     */
    fun notifyNoCache(
        startupWindowId: Long,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        if (stateStore.readState().lastNotificationStartupWindowId == startupWindowId) {
            return
        }

        stateStore.recordNotification(startupWindowId, nowMillis)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle("Am thanh khoi dong chua san sang")
            .setContentText("Mo ung dung de chon hoac tai lai am thanh khoi dong")
            .setSilent(true)
            .setOngoing(false)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(notificationManager)
        notificationManager.notify(NO_CACHE_NOTIFICATION_ID, notification)
    }

    /**
     * Xóa no-cache notification khi cache đã sẵn sàng hoặc playback bắt đầu thành công.
     */
    fun clearNoCacheNotification() {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NO_CACHE_NOTIFICATION_ID)
    }

    /**
     * Tạo notification channel nếu thiết bị chưa có channel này.
     */
    private fun ensureChannel(notificationManager: NotificationManager) {
        val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existingChannel != null) {
            return
        }

        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Boot startup sound",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "boot_startup_sound"
        private const val NO_CACHE_NOTIFICATION_ID = 0x5B007
    }
}
