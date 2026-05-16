package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class PlaybackSoundResolverSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `user initiated playback may refresh a missing managed sound before giving up`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/PlaybackSoundResolver.kt")

        assertTrue(source.contains("preferRefresh: Boolean = false"))
        assertTrue(source.contains("fallbackUri.isNullOrBlank() && preferRefresh"))
        assertTrue(source.contains("soundAssetManager.downloadLatestSound(soundIndex)?.toString()"))
    }
}
