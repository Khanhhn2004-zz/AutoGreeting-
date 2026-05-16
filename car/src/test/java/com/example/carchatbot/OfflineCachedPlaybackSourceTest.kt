package com.example.carchatbot

import com.example.carchatbot.data.model.SoundSourceType
import com.example.carchatbot.data.repository.PlaybackSoundResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class OfflineCachedPlaybackSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `sound playback still fails fast after player errors but may hydrate missing sound before start`() {
        val serviceSource = readSource("src/main/java/com/example/carchatbot/service/SoundPlayerService.kt")
        val resolverSource = readSource("src/main/java/com/example/carchatbot/data/repository/PlaybackSoundResolver.kt")

        assertFalse(serviceSource.contains("soundAssetManager.downloadLatestSound(soundIndex ?: 1)"))
        assertFalse(serviceSource.contains("Recovered sound from server"))
        assertTrue(serviceSource.contains("notifyPlaybackUnavailable(reason, soundIndex, playbackRequest)"))
        assertTrue(resolverSource.contains("soundAssetManager.downloadLatestSound(soundIndex)?.toString()"))
    }

    @Test
    fun `core service no longer auto-downloads missing sounds during heartbeat`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertFalse(source.contains("checkAndDownloadSounds()"))
        assertFalse(source.contains("auto_download_ping"))
        assertFalse(source.contains("Sounds missing. Triggering DownloadWorker."))
    }

    @Test
    fun `sound asset manager only reuses previously stored server-managed sounds during playback`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/SoundAssetManager.kt")

        assertTrue(source.contains("suspend fun ensureSoundAvailable(soundIndex: Int): Uri?"))
        assertFalse(source.contains("return downloadLatestSound(soundIndex)"))
    }

    @Test
    fun `runtime policy exposes bundled default resources only for explicit cache seeding`() {
        val source = readSource("src/main/java/com/example/carchatbot/runtime/AppRuntimePolicies.kt")
        val resolverSource = readSource("src/main/java/com/example/carchatbot/data/repository/PlaybackSoundResolver.kt")

        assertTrue(source.contains("defaultSoundResIdForIndex("))
        assertTrue(source.contains("default_startup_sound"))
        assertTrue(source.contains("default_goodbye_sound"))
        assertFalse(resolverSource.contains("defaultSoundResIdForIndex("))
    }

    @Test
    fun `bundled startup and goodbye raw assets remain in the app`() {
        assertTrue(Files.exists(Paths.get("src/main/res/raw/default_startup_sound.MP3")))
        assertTrue(Files.exists(Paths.get("src/main/res/raw/default_goodbye_sound.mp3")))
    }

    @Test
    fun `local source beats server source`() {
        val resolution = PlaybackSoundResolver.choosePreferredSource(
            soundIndex = 1,
            localUri = "file:///storage/emulated/0/custom-hello.mp3",
            isLocalValid = true,
            fallbackUri = "file:///data/user/0/com.example.carchatbot/files/sound_assets_v2/server_hello.audio",
            fallbackSourceType = SoundSourceType.SERVER_MANAGED
        )

        assertEquals("file:///storage/emulated/0/custom-hello.mp3", resolution?.uriString)
        assertEquals(SoundSourceType.USER_LOCAL, resolution?.sourceType)
    }

    @Test
    fun `invalid local demotes correctly`() {
        val demotedToServer = PlaybackSoundResolver.choosePreferredSource(
            soundIndex = 1,
            localUri = "file:///storage/emulated/0/missing-hello.mp3",
            isLocalValid = false,
            fallbackUri = "file:///data/user/0/com.example.carchatbot/files/sound_assets_v2/server_hello.audio",
            fallbackSourceType = SoundSourceType.SERVER_MANAGED
        )
        val demotedToGoodbyeFallback = PlaybackSoundResolver.choosePreferredSource(
            soundIndex = 2,
            localUri = "file:///storage/emulated/0/missing-goodbye.mp3",
            isLocalValid = false,
            fallbackUri = "android.resource://com.example.carchatbot/raw/default_goodbye_sound",
            fallbackSourceType = SoundSourceType.DEFAULT_GOODBYE
        )
        val noFallbackAvailable = PlaybackSoundResolver.choosePreferredSource(
            soundIndex = 1,
            localUri = "file:///storage/emulated/0/missing-hello.mp3",
            isLocalValid = false,
            fallbackUri = null,
            fallbackSourceType = SoundSourceType.NONE
        )

        assertEquals(
            "file:///data/user/0/com.example.carchatbot/files/sound_assets_v2/server_hello.audio",
            demotedToServer?.uriString
        )
        assertEquals(SoundSourceType.SERVER_MANAGED, demotedToServer?.sourceType)
        assertEquals(
            "android.resource://com.example.carchatbot/raw/default_goodbye_sound",
            demotedToGoodbyeFallback?.uriString
        )
        assertEquals(SoundSourceType.DEFAULT_GOODBYE, demotedToGoodbyeFallback?.sourceType)
        assertNull(noFallbackAvailable)
    }
}
