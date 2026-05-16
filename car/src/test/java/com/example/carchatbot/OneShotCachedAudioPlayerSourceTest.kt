package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class OneShotCachedAudioPlayerSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `one shot cached audio player exists and owns wake lock audio focus and media player lifecycle`() {
        val path = Paths.get("src/main/java/com/example/carchatbot/audio/OneShotCachedAudioPlayer.kt")
        assertTrue(Files.exists(path))

        val source = readSource(path.toString())

        assertTrue(source.contains("WakeLock"))
        assertTrue(source.contains("AudioFocusRequest"))
        assertTrue(source.contains("MediaPlayer"))
        assertTrue(source.contains("onStarted"))
        assertTrue(source.contains("onCompleted"))
        assertTrue(source.contains("onFailure"))
    }

    @Test
    fun `one shot cached audio player starts from a cached local file path`() {
        val path = Paths.get("src/main/java/com/example/carchatbot/audio/OneShotCachedAudioPlayer.kt")
        assertTrue(Files.exists(path))

        val source = readSource(path.toString())

        assertTrue(source.contains("cachedFile.absolutePath"))
        assertTrue(source.contains("setDataSource"))
        assertTrue(source.contains("file"))
        assertTrue(source.contains("Uri"))
    }

    @Test
    fun `cached playback avoids file descriptor data source on automotive builds`() {
        val oneShotSource = readSource("src/main/java/com/example/carchatbot/audio/OneShotCachedAudioPlayer.kt")
        val soundPlayerSource = readSource("src/main/java/com/example/carchatbot/service/SoundPlayerService.kt")

        assertFalse(oneShotSource.contains("FileInputStream"))
        assertFalse(soundPlayerSource.contains("FileInputStream"))
        assertTrue(oneShotSource.contains("player.setDataSource(cachedFile.absolutePath)"))
        assertTrue(soundPlayerSource.contains("newPlayer.setDataSource(localFile.absolutePath)"))
    }

    @Test
    fun `audio focus request builder installs a focus change listener for android o and above`() {
        val source = readSource("src/main/java/com/example/carchatbot/audio/OneShotCachedAudioPlayer.kt")

        assertTrue(source.contains(".setOnAudioFocusChangeListener(audioFocusChangeListener)"))
    }

    @Test
    fun `boot cached audio player logs focus volume route and prepared duration diagnostics`() {
        val source = readSource("src/main/java/com/example/carchatbot/audio/OneShotCachedAudioPlayer.kt")

        assertTrue(source.contains("AppLogger"))
        assertTrue(source.contains("audioDiagnosticDetails()"))
        assertTrue(source.contains("getStreamVolume(PlaybackAudioProfile.legacyStreamType)"))
        assertTrue(source.contains("getStreamMaxVolume(PlaybackAudioProfile.legacyStreamType)"))
        assertTrue(source.contains("AudioManager.GET_DEVICES_OUTPUTS"))
        assertTrue(source.contains("\"Boot audio prepared\""))
        assertTrue(source.contains("\"Boot audio focus result\""))
        assertTrue(source.contains("\"Boot audio player started\""))
        assertTrue(source.contains("durationMs="))
        assertTrue(source.contains("volume="))
        assertTrue(source.contains("routes="))
    }

    @Test
    fun `boot cached audio player treats audio focus as best effort like macrodroid`() {
        val source = readSource("src/main/java/com/example/carchatbot/audio/OneShotCachedAudioPlayer.kt")
        val delayedBranch = source
            .substringAfter("AudioFocusResult.DELAYED ->")
            .substringBefore("AudioFocusResult.FAILED ->")
        val failedBranch = source
            .substringAfter("AudioFocusResult.FAILED ->")
            .substringBefore("player.setOnCompletionListener")

        assertTrue(source.contains("AudioManager.AUDIOFOCUS_GAIN ->"))
        assertTrue(source.contains("startPendingPreparedPlayback(\"audio_focus_gain\")"))
        assertTrue(source.contains(".setAcceptsDelayedFocusGain(false)"))
        assertTrue(source.contains("\"Boot audio focus delayed; starting anyway\""))
        assertTrue(source.contains("\"Boot audio focus unavailable; starting anyway\""))
        assertTrue(delayedBranch.contains("startPendingPreparedPlayback(\"audio_focus_delayed_starting_anyway\")"))
        assertTrue(failedBranch.contains("startPendingPreparedPlayback(\"audio_focus_failed_starting_anyway\")"))
        assertFalse(delayedBranch.contains("cleanupPlayer()"))
        assertFalse(delayedBranch.contains("onFailure("))
        assertFalse(failedBranch.contains("cleanupPlayer()"))
        assertFalse(failedBranch.contains("onFailure("))
    }

    @Test
    fun `boot cached audio player extends wake lock to cover prepared audio duration`() {
        val source = readSource("src/main/java/com/example/carchatbot/audio/OneShotCachedAudioPlayer.kt")

        assertTrue(source.contains("extendWakeLockForPlayback(durationMs)"))
        assertTrue(source.contains("durationMs.toLong() + PLAYBACK_WAKE_LOCK_BUFFER_MS"))
        assertTrue(source.contains("PLAYBACK_WAKE_LOCK_BUFFER_MS"))
    }
}
