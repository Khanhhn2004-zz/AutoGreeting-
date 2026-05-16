package com.example.carchatbot

import com.example.carchatbot.service.PlaybackRequest
import com.example.carchatbot.service.PlaybackRequestDecision
import com.example.carchatbot.service.SoundPlayerState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class SoundPlayerServiceSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `indexed playback no longer falls back to bundled default audio`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/SoundPlayerService.kt")

        assertFalse(source.contains("Fallback to default sound"))
        assertFalse(source.contains("playSound(defaultUri"))
        assertFalse(source.contains("EXTRA_SONG_RES_ID"))
        assertFalse(source.contains("android.resource://"))
        assertTrue(source.contains("notifyPlaybackUnavailable("))
    }

    @Test
    fun `audio focus callback is routed through guarded helpers instead of touching player directly`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/SoundPlayerService.kt")

        assertFalse(source.contains("mediaPlayer!!.isPlaying"))
        assertTrue(source.contains("safeStartPlaybackFromFocusGain("))
        assertTrue(source.contains("runCatching"))
    }

    @Test
    fun `service abandons audio focus during playback teardown`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/SoundPlayerService.kt")

        assertTrue(source.contains("abandonAudioFocus()"))
        assertTrue(source.contains("stopPlaybackSession("))
    }

    @Test
    fun `manual playback uses the shared media audio profile instead of navigation guidance route`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/SoundPlayerService.kt")
        val playbackAudioProfileSource = readSource("src/main/java/com/example/carchatbot/audio/PlaybackAudioProfile.kt")

        assertTrue(source.contains("PlaybackAudioProfile.audioAttributes"))
        assertTrue(source.contains("PlaybackAudioProfile.focusGain"))
        assertTrue(source.contains("PlaybackAudioProfile.legacyStreamType"))
        assertTrue(playbackAudioProfileSource.contains("USAGE_MEDIA"))
        assertTrue(playbackAudioProfileSource.contains("CONTENT_TYPE_MUSIC"))
        assertFalse(source.contains("USAGE_ASSISTANCE_NAVIGATION_GUIDANCE"))
        assertFalse(source.contains("CONTENT_TYPE_SPEECH"))
    }

    @Test
    fun `sound player foreground notification id does not collide with core service`() {
        val soundPlayerSource = readSource("src/main/java/com/example/carchatbot/service/SoundPlayerService.kt")
        val coreServiceSource = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        val soundPlayerNotificationId = Regex("""NOTIFICATION_ID\s*=\s*(\d+)""")
            .find(soundPlayerSource)
            ?.groupValues
            ?.get(1)
        val coreServiceNotificationId = Regex("""NOTIFICATION_ID\s*=\s*(\d+)""")
            .find(coreServiceSource)
            ?.groupValues
            ?.get(1)

        assertTrue(soundPlayerSource.contains("startForeground(NOTIFICATION_ID, notification)"))
        assertTrue(coreServiceSource.contains("startForeground(NOTIFICATION_ID, buildNotification())"))
        assertTrue(soundPlayerNotificationId != null)
        assertTrue(coreServiceNotificationId != null)
        assertTrue(soundPlayerNotificationId != coreServiceNotificationId)
    }

    @Test
    fun `manual playback failure reuses sound failure broadcast contract for user facing errors`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/SoundPlayerService.kt")

        assertTrue(source.contains("CoreService.ACTION_SOUND_FAILED"))
        assertTrue(source.contains("CoreService.EXTRA_SOUND_FAILURE_REASON"))
        assertTrue(source.contains("CoreService.SOUND_FAILURE_REASON_SESSION_EXPIRED"))
        assertTrue(source.contains("CoreService.SOUND_FAILURE_REASON_PLAYBACK_UNAVAILABLE"))
    }

    @Test
    fun `user stop beats pending autoplay`() {
        val state = SoundPlayerState.RequestState(
            activeRequest = PlaybackRequest.autoplayStart(soundIndex = 1),
            isStartPending = true
        )

        assertEquals(
            PlaybackRequestDecision.CANCEL_PENDING_AUTOPLAY,
            state.decisionFor(
                incoming = PlaybackRequest.userStop(soundIndex = 1),
                cleanupInProgress = false,
                autoplaySuppressed = false
            )
        )
    }

    @Test
    fun `same-slot duplicate autoplay coalesces`() {
        val state = SoundPlayerState.RequestState(
            activeRequest = PlaybackRequest.autoplayStart(soundIndex = 1),
            isStartPending = true
        )

        assertEquals(
            PlaybackRequestDecision.IGNORE_DUPLICATE_AUTOPLAY,
            state.decisionFor(
                incoming = PlaybackRequest.autoplayStart(soundIndex = 1),
                cleanupInProgress = false,
                autoplaySuppressed = false
            )
        )
    }

    @Test
    fun `cross-slot handoff keeps only one requested slot active in ui state`() {
        SoundPlayerState.resetForTest()

        SoundPlayerState.onPlaybackStarted(PlaybackRequest.userStart(soundIndex = 1))
        SoundPlayerState.onPlaybackRequestQueued(PlaybackRequest.userStart(soundIndex = 2))

        assertFalse(SoundPlayerState.isPlaying.value)
        assertEquals(2, SoundPlayerState.currentSoundIndex.value)
        assertEquals(PlaybackRequest.userStart(soundIndex = 2), SoundPlayerState.activeRequest.value)
        assertNull(SoundPlayerState.playingRequest.value)
    }

    @Test
    fun `manual actions preempt startup playback through boot playback stop contract`() {
        val soundPlayerSource = readSource("src/main/java/com/example/carchatbot/service/SoundPlayerService.kt")
        val bootPlaybackSource = readSource("src/main/java/com/example/carchatbot/boot/BootPlaybackService.kt")
        val receiverSource = readSource("src/main/java/com/example/carchatbot/service/BootCompletedReceiver.kt")

        assertTrue(soundPlayerSource.contains("preemptStartupPlaybackIfNeeded(playbackRequest)"))
        assertTrue(soundPlayerSource.contains("BootPlaybackService.requestStopForManualPreemption("))
        assertTrue(soundPlayerSource.contains("stateStore = bootPlaybackStateStore"))
        assertTrue(bootPlaybackSource.contains("ACTION_STOP_FOR_MANUAL_PREEMPTION"))
        assertFalse(receiverSource.contains("requestStopForManualPreemption"))
    }

    @Test
    fun `startup playback stays out of manual sound player state`() {
        val bootPlaybackSource = readSource("src/main/java/com/example/carchatbot/boot/BootPlaybackService.kt")

        assertFalse(bootPlaybackSource.contains("SoundPlayerState.onPlaybackRequestQueued"))
        assertFalse(bootPlaybackSource.contains("SoundPlayerState.onPlaybackStarted"))
        assertFalse(bootPlaybackSource.contains("SoundPlayerState.onPlaybackStopped"))
        assertTrue(bootPlaybackSource.contains("SoundPlayerService.requestStopForStartupTakeover("))
    }

    @Test
    fun `startup playback publishes separate ui playback state`() {
        val bootPlaybackSource = readSource("src/main/java/com/example/carchatbot/boot/BootPlaybackService.kt")

        assertTrue(bootPlaybackSource.contains("SoundPlayerState.onStartupPlaybackStarted(readyBootSound.soundIndex)"))
        assertTrue(bootPlaybackSource.contains("SoundPlayerState.onStartupPlaybackStopped()"))
    }

    @Test
    fun `manual playback state can be cleared without implying startup playback ownership`() {
        SoundPlayerState.resetForTest()
        SoundPlayerState.onPlaybackStarted(PlaybackRequest.userStart(soundIndex = 1))

        assertFalse(SoundPlayerState.isManualPlaybackIdle())

        SoundPlayerState.clearManualPlaybackForTeardown()

        assertTrue(SoundPlayerState.isManualPlaybackIdle())
        assertNull(SoundPlayerState.activeRequest.value)
        assertNull(SoundPlayerState.playingRequest.value)
    }

    @Test
    fun `startup takeover clears manual goodbye state before startup playback owns audio`() {
        SoundPlayerState.resetForTest()
        SoundPlayerState.onPlaybackStarted(PlaybackRequest.userStart(soundIndex = 2))

        assertFalse(SoundPlayerState.isManualPlaybackIdle())

        SoundPlayerState.clearManualPlaybackForStartupTakeover()

        assertTrue(SoundPlayerState.isManualPlaybackIdle())
        assertNull(SoundPlayerState.activeRequest.value)
        assertNull(SoundPlayerState.playingRequest.value)
        assertNull(SoundPlayerState.currentSoundIndex.value)
    }

    @Test
    fun `sound player state uses atomic snapshot as source of truth`() {
        val stateSource = readSource("src/main/java/com/example/carchatbot/service/SoundPlayerState.kt")
        val serviceSource = readSource("src/main/java/com/example/carchatbot/service/SoundPlayerService.kt")

        assertTrue(stateSource.contains("data class PlaybackSnapshot"))
        assertTrue(stateSource.contains("private val _snapshot = MutableStateFlow(PlaybackSnapshot())"))
        assertTrue(stateSource.contains("val snapshot: StateFlow<PlaybackSnapshot>"))
        assertTrue(stateSource.contains("private fun publishSnapshot("))
        assertFalse(stateSource.contains("val isPlaying = MutableStateFlow(false)"))
        assertFalse(stateSource.contains("val activeRequest = MutableStateFlow<PlaybackRequest?>(null)"))
        assertFalse(serviceSource.contains("SoundPlayerState.isPlaying.value = false"))
        assertTrue(serviceSource.contains("SoundPlayerState.onPlaybackPaused()"))
    }
}
