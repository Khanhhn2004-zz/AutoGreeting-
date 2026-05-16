package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class BootSoundCacheRepositorySourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `startup cache repository exists under boot sound storage`() {
        val path = Paths.get("src/main/java/com/example/carchatbot/boot/BootSoundCacheRepository.kt")
        assertTrue(Files.exists(path))

        val source = readSource(path.toString())

        assertTrue(source.contains("boot_sound"))
        assertTrue(source.contains("BootSoundCacheEntry"))
        assertTrue(source.contains("BootSoundCacheStatus"))
        assertTrue(source.contains("BootStorageContextProvider"))
        assertTrue(source.contains("startupStorageDir(CACHE_DIRECTORY_NAME)"))
        assertTrue(!source.contains("context.filesDir"))
    }

    @Test
    fun `startup cache repository exposes read update and clear operations`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootSoundCacheRepository.kt")

        assertTrue(source.contains("getReadyBootSound()"))
        assertTrue(source.contains("replaceStartupSoundCache("))
        assertTrue(source.contains("clearStartupSoundCache("))
    }

    @Test
    fun `startup cache repository exposes readiness diagnostics with concrete no cache reasons`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootSoundCacheRepository.kt")

        assertTrue(source.contains("BootSoundCacheReadiness"))
        assertTrue(source.contains("inspectReadiness()"))
        assertTrue(source.contains("MISSING_METADATA"))
        assertTrue(source.contains("INVALID_METADATA"))
        assertTrue(source.contains("METADATA_NOT_READY"))
        assertTrue(source.contains("MISSING_AUDIO_FILE"))
        assertTrue(source.contains("AUDIO_TOO_SMALL"))
    }

    @Test
    fun `startup cache updates stage validate and atomically replace the prior cache`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootSoundCacheRepository.kt")

        assertTrue(source.contains("WRITING"))
        assertTrue(source.contains("READY"))
        assertTrue(source.contains("INVALID"))
        assertTrue(source.contains("stageFile.length()"))
        assertTrue(source.contains("Files.move"))
        assertTrue(source.contains("StandardCopyOption.ATOMIC_MOVE"))
    }

    @Test
    fun `startup cache metadata writes through staged atomic replacement`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootSoundCacheRepository.kt")
        val writeMetadataSection = source
            .substringAfter("private fun writeMetadata(metadata: CacheMetadata)")
            .substringBefore("private fun deleteIfExists")

        assertTrue(writeMetadataSection.contains(".startup_sound.metadata-"))
        assertTrue(writeMetadataSection.contains("stageFile.writeText(json.toString())"))
        assertTrue(writeMetadataSection.contains("moveIntoPlace(stageFile, metadataFile)"))
        assertFalse(writeMetadataSection.contains("metadataFile().writeText"))
    }

    @Test
    fun `startup cache repairs missing or invalid metadata when audio file is usable`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootSoundCacheRepository.kt")

        assertTrue(source.contains("repairRecoverableMetadataBlocking("))
        assertTrue(source.contains("BootSoundCacheReadinessReason.MISSING_METADATA"))
        assertTrue(source.contains("BootSoundCacheReadinessReason.INVALID_METADATA"))
        assertTrue(source.contains("readiness.audioByteCount >= MIN_VALID_SOUND_BYTES"))
        assertTrue(source.contains("BootSoundCacheStatus.READY"))
        assertTrue(source.contains("soundIndex = STARTUP_SOUND_INDEX"))
    }

    @Test
    fun `startup cache retries transient writing metadata before returning no cache`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootSoundCacheRepository.kt")

        assertTrue(source.contains("CACHE_READY_RETRY_COUNT"))
        assertTrue(source.contains("CACHE_READY_RETRY_DELAY_MS"))
        assertTrue(source.contains("delay(CACHE_READY_RETRY_DELAY_MS)"))
        assertTrue(source.contains("BootSoundCacheReadinessReason.METADATA_NOT_READY"))
    }
}
