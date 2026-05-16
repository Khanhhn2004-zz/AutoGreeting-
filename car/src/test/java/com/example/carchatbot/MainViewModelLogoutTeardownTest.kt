package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class MainViewModelLogoutTeardownTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `boot playback service is stopped before manual playback state may be force-cleared`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainViewModel.kt")

        val bootStopIndex = source.indexOf(
            "context.stopService(Intent(context, com.example.carchatbot.boot.BootPlaybackService::class.java))"
        )
        val manualClearIndex = source.indexOf("SoundPlayerState.clearManualPlaybackForTeardown()")

        assertTrue(bootStopIndex >= 0)
        assertTrue(manualClearIndex >= 0)
        assertTrue(bootStopIndex < manualClearIndex)
    }

    @Test
    fun `teardown only force clears manual playback state after boot playback is inactive`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainViewModel.kt")

        assertTrue(source.contains("if (!bootPlaybackStateStore.readState().isActive())"))
        assertTrue(source.contains("SoundPlayerState.isManualPlaybackIdle()"))
    }
}
