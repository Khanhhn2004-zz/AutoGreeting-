package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class BootPlaybackServiceSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `boot playback service exists and verifies session ownership before playback`() {
        val path = Paths.get("src/main/java/com/example/carchatbot/boot/BootPlaybackService.kt")
        assertTrue(Files.exists(path))

        val source = readSource(path.toString())

        assertTrue(source.contains("BootPlaybackStateStore"))
        assertTrue(source.contains("BootSoundCacheRepository"))
        assertTrue(source.contains("verify"))
        assertTrue(source.contains("session"))
    }

    @Test
    fun `boot playback service uses the cached boot sound directly`() {
        val path = Paths.get("src/main/java/com/example/carchatbot/boot/BootPlaybackService.kt")
        assertTrue(Files.exists(path))

        val source = readSource(path.toString())

        assertTrue(source.contains("getReadyBootSound()"))
        assertTrue(source.contains("OneShotCachedAudioPlayer"))
        assertTrue(source.contains("BootSoundNotifier"))
        assertTrue(source.contains("markNoCache("))
        assertTrue(source.contains("notifyNoCache("))
        assertTrue(!source.contains("PlaybackSoundResolver"))
    }

    @Test
    fun `boot playback service clears stale no cache notification and removes foreground notification on teardown`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootPlaybackService.kt")

        assertTrue(source.contains("clearNoCacheNotification()"))
        assertTrue(source.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
    }

    @Test
    fun `boot playback terminal state updates do not rely on cancellable service scope dispatch`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootPlaybackService.kt")
        val completionDispatchPattern = Regex("onCompleted\\s*=\\s*\\{\\s*serviceScope\\.launch")
        val failureDispatchPattern = Regex("onFailure\\s*=\\s*\\{\\s*throwable\\s*->\\s*serviceScope\\.launch")

        assertTrue(source.contains("stateStore.markCompletedIfCurrent(sessionId, System.currentTimeMillis())"))
        assertTrue(source.contains("stateStore.markFailedIfCurrent("))
        assertTrue(!completionDispatchPattern.containsMatchIn(source))
        assertTrue(!failureDispatchPattern.containsMatchIn(source))
    }

    @Test
    fun `boot playback service logs cache foreground and audio lifecycle breadcrumbs`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootPlaybackService.kt")
        val oneShotPlayerSource = readSource("src/main/java/com/example/carchatbot/audio/OneShotCachedAudioPlayer.kt")
        val playbackAudioProfileSource = readSource("src/main/java/com/example/carchatbot/audio/PlaybackAudioProfile.kt")

        assertTrue(source.contains("appLogger.logBoot("))
        assertTrue(source.contains("\"Boot playback foreground started\""))
        assertTrue(source.contains("\"Boot playback foreground start failed\""))
        assertTrue(source.contains("\"Boot playback cache lookup started\""))
        assertTrue(source.contains("\"Boot playback cache missing\""))
        assertTrue(source.contains("\"Boot playback audio started\""))
        assertTrue(source.contains("\"Boot playback audio completed\""))
        assertTrue(source.contains("\"Boot playback audio failed\""))
        assertTrue(oneShotPlayerSource.contains("PlaybackAudioProfile.audioAttributes"))
        assertTrue(playbackAudioProfileSource.contains("USAGE_MEDIA"))
        assertTrue(playbackAudioProfileSource.contains("CONTENT_TYPE_MUSIC"))
    }

    @Test
    fun `boot playback service remains sticky while handling an active boot session`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootPlaybackService.kt")

        assertTrue(source.contains("return START_STICKY"))
        assertTrue(source.contains("val requestedSessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: state.sessionId"))
    }

    @Test
    fun `boot playback service direct receiver start reports foreground service rejection without throwing`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootPlaybackService.kt")

        assertTrue(source.contains("return try {"))
        assertTrue(source.contains("ContextCompat.startForegroundService(appContext, createIntent(appContext, decision.state))"))
        assertTrue(source.contains("catch (error: RuntimeException)"))
        assertTrue(source.contains("BootStorageContextProvider(appContext)"))
        assertTrue(source.contains("markFailedIfCurrent("))
        assertTrue(source.contains("foreground_service_start_rejected"))
        assertTrue(source.contains("\"Boot playback foreground service start rejected\""))
    }

    @Test
    fun `manual preemption stop request does not relaunch boot playback through a new foreground service`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootPlaybackService.kt")
        val manualPreemptionForegroundStartPattern = Regex(
            "fun requestStopForManualPreemption\\([\\s\\S]*?\\) \\{[\\s\\S]*?ContextCompat\\.startForegroundService"
        )
        val manualPreemptionStartServicePattern = Regex(
            "fun requestStopForManualPreemption\\([\\s\\S]*?\\) \\{[\\s\\S]*?context\\.startService"
        )

        assertTrue(source.contains("fun requestStopForManualPreemption("))
        assertTrue(source.contains("ACTION_STOP_FOR_MANUAL_PREEMPTION"))
        assertTrue(source.contains("applyManualPreemptionFailure("))
        assertTrue(source.contains("context.applicationContext"))
        assertTrue(source.contains("BootPlaybackStateStore(BootStorageContextProvider(appContext))"))
        assertTrue(source.contains("appContext.stopService(Intent(appContext, BootPlaybackService::class.java))"))
        assertTrue(!manualPreemptionForegroundStartPattern.containsMatchIn(source))
        assertTrue(!manualPreemptionStartServicePattern.containsMatchIn(source))
    }
}
