package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class SoundAssetManagerSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `server downloads normalize into fixed slot filenames`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/SoundAssetManager.kt")

        assertTrue(source.contains("fixedServerSoundFileName(soundIndex)"))
        assertTrue(source.contains("fixedLocalSoundFileName(soundIndex)"))
        assertFalse(source.contains("server_sound_"))
    }

    @Test
    fun `cleanup deletes both local custom files and server files`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/SoundAssetManager.kt")

        assertTrue(source.contains("deleteManagedSoundFiles(ownerUserId = null, soundIndex = null)"))
        assertTrue(source.contains("fixedLocalSoundFileName(soundIndex)"))
        assertTrue(source.contains("fixedServerSoundFileName(soundIndex)"))
    }

    @Test
    fun `goodbye availability is recorded as a tri-state value`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/SoundAssetManager.kt")

        assertTrue(source.contains("GoodbyeServerAvailability.PRESENT"))
        assertTrue(source.contains("saveGoodbyeServerAvailability"))
    }

    @Test
    fun `successful managed downloads refresh the boot cache after commit`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/SoundAssetManager.kt")

        assertTrue(source.contains("BootSoundCacheRepository"))
        assertTrue(source.contains("if (soundIndex == BootSoundCacheRepository.STARTUP_SOUND_INDEX)"))
        assertTrue(source.contains("replaceStartupSoundCache("))
    }

    @Test
    fun `managed startup downloads log whether the boot cache copy is ready`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/SoundAssetManager.kt")

        assertTrue(source.contains("refreshStartupSoundCache("))
        assertTrue(source.contains("\"Startup sound cache ready\""))
        assertTrue(source.contains("\"Startup sound cache not ready after server download\""))
        assertTrue(source.contains("inspectReadiness()"))
    }

    @Test
    fun `bundled demo startup sound can seed the boot cache without server credentials`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/SoundAssetManager.kt")

        assertTrue(source.contains("prepareBundledStartupSoundCache()"))
        assertTrue(source.contains("AppRuntimePolicies.defaultSoundResIdForIndex(BootSoundCacheRepository.STARTUP_SOUND_INDEX)"))
        assertTrue(source.contains("context.resources.openRawResource("))
        assertTrue(source.contains("source=bundled_default"))
        assertTrue(source.contains("\"Startup sound cache not ready after bundled default copy\""))
    }

    @Test
    fun `invalid managed writes do not clear the previous boot cache`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/SoundAssetManager.kt")

        assertTrue(source.contains("replaceStartupSoundCache("))
        assertFalse(source.contains("clearStartupSoundCache("))
    }

    @Test
    fun `goodbye playback falls back to bundled audio when server sound is unavailable`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/SoundAssetManager.kt")

        assertTrue(source.contains("ManagedSoundRefreshResult.MissingOnServer -> defaultGoodbyeSoundUri()"))
        assertTrue(source.contains("ManagedSoundRefreshResult.Unavailable -> defaultGoodbyeSoundUri()"))
    }

    @Test
    fun `managed downloads are serialized per slot to avoid overlapping writers`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/SoundAssetManager.kt")

        assertTrue(source.contains("Mutex"))
        assertTrue(source.contains("withLock"))
    }

    @Test
    fun `warmup treats missing goodbye as optional when hello is already ready`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/SoundAssetManager.kt")

        assertTrue(source.contains("if (helloUri == null) add(1)"))
        assertFalse(source.contains("if (goodbyeUri == null) add(2)"))
    }

    @Test
    fun `managed downloads retry transient failures before giving up`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/SoundAssetManager.kt")

        assertTrue(source.contains("repeat(MANAGED_DOWNLOAD_ATTEMPTS)"))
        assertTrue(source.contains("delay(MANAGED_DOWNLOAD_RETRY_DELAY_MS)"))
        assertTrue(source.contains("retryable = true"))
    }
}
