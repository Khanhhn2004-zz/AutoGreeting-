package com.example.carchatbot

import com.example.carchatbot.service.PlaybackRequest
import com.example.carchatbot.service.SoundPlayerService
import com.example.carchatbot.service.SoundPlayerState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class SoundPlayerServiceRaceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `stale start loses ownership once stop cleared the active request`() {
        val request = PlaybackRequest.userStart(soundIndex = 1)

        SoundPlayerState.resetForTest()
        SoundPlayerState.onPlaybackRequestQueued(request)
        assertTrue(
            SoundPlayerService.canCommitPendingPlayerOwnership(
                activeRequest = SoundPlayerState.activeRequest.value,
                playbackRequest = request
            )
        )

        SoundPlayerState.onPlaybackStopped()

        assertFalse(
            SoundPlayerService.canCommitPendingPlayerOwnership(
                activeRequest = SoundPlayerState.activeRequest.value,
                playbackRequest = request
            )
        )
    }

    @Test
    fun `service claims player ownership through a stale start guard before callbacks use the player`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/SoundPlayerService.kt")

        assertTrue(source.contains("claimPendingPlayerOwnership(newPlayer, playbackRequest)"))
        assertTrue(source.contains("canCommitPendingPlayerOwnership("))
        assertTrue(source.contains("newPlayer.release()"))
    }

    @Test
    fun `only manual requests preempt startup playback`() {
        assertTrue(
            SoundPlayerService.shouldPreemptStartupPlayback(
                playbackRequest = PlaybackRequest.userStart(soundIndex = 1),
                startupPlaybackActive = true
            )
        )
        assertFalse(
            SoundPlayerService.shouldPreemptStartupPlayback(
                playbackRequest = PlaybackRequest.autoplayStart(soundIndex = 1),
                startupPlaybackActive = true
            )
        )
        assertFalse(
            SoundPlayerService.shouldPreemptStartupPlayback(
                playbackRequest = PlaybackRequest.userStart(soundIndex = 1),
                startupPlaybackActive = false
            )
        )
    }
}
