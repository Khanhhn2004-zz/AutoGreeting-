package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class BootSoundNotifierSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `boot sound notifier exists and emits the no cache notification path`() {
        val path = Paths.get("src/main/java/com/example/carchatbot/boot/BootSoundNotifier.kt")
        assertTrue(Files.exists(path))

        val source = readSource(path.toString())

        assertTrue(source.contains("Notification"))
        assertTrue(source.contains("recordNotification"))
        assertTrue(source.contains("lastNotificationStartupWindowId"))
    }

    @Test
    fun `boot sound notifier uses a silent user facing notification`() {
        val path = Paths.get("src/main/java/com/example/carchatbot/boot/BootSoundNotifier.kt")
        assertTrue(Files.exists(path))

        val source = readSource(path.toString())

        assertTrue(source.contains("setSilent(true)"))
        assertTrue(source.contains("NotificationCompat"))
        assertTrue(source.contains("notify"))
    }

    @Test
    fun `boot sound notifier can clear stale no cache notifications`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootSoundNotifier.kt")

        assertTrue(source.contains("clearNoCacheNotification"))
        assertTrue(source.contains("cancel(NO_CACHE_NOTIFICATION_ID)"))
    }
}
