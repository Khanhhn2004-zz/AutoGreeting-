package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class LocalSoundRepositorySourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `local imports stage files in app-private temp storage before replacing fixed slots`() {
        val path = Paths.get("src/main/java/com/example/carchatbot/data/repository/LocalSoundRepository.kt")
        assertTrue(Files.exists(path))

        val source = readSource(path.toString())

        assertTrue(source.contains("cacheDir"))
        assertTrue(source.contains("fixedLocalSoundFileName(soundIndex)"))
        assertTrue(source.contains("replaceFile("))
        assertTrue(source.contains("copyPickedFileToTemp"))
    }

    @Test
    fun `stale local writers are blocked when generation changes during import`() {
        val path = Paths.get("src/main/java/com/example/carchatbot/data/repository/LocalSoundRepository.kt")
        assertTrue(Files.exists(path))

        val source = readSource(path.toString())

        assertTrue(source.contains("soundGeneration"))
        assertTrue(source.contains("cleanupInProgress"))
        assertTrue(source.contains("saveSoundAcceptedGeneration"))
        assertTrue(source.contains("currentGeneration == expectedGeneration"))
    }

    @Test
    fun `failed local replacement keeps the previous valid file in place`() {
        val path = Paths.get("src/main/java/com/example/carchatbot/data/repository/LocalSoundRepository.kt")
        assertTrue(Files.exists(path))

        val source = readSource(path.toString())

        assertTrue(source.contains("fileRepository.replaceFile("))
        assertTrue(source.contains("currentGeneration == expectedGeneration"))
        assertTrue(source.contains("deleteIfExists"))
    }

    @Test
    fun `successful local imports refresh the boot cache after commit`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/LocalSoundRepository.kt")

        assertTrue(source.contains("BootSoundCacheRepository"))
        assertTrue(source.contains("if (soundIndex == BootSoundCacheRepository.STARTUP_SOUND_INDEX)"))
        assertTrue(source.contains("replaceStartupSoundCache("))
    }

    @Test
    fun `local startup imports log whether the boot cache copy is ready`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/LocalSoundRepository.kt")

        assertTrue(source.contains("refreshStartupSoundCache("))
        assertTrue(source.contains("\"Startup sound cache ready\""))
        assertTrue(source.contains("\"Startup sound cache not ready after local import\""))
        assertTrue(source.contains("inspectReadiness()"))
    }

    @Test
    fun `invalid local writes do not clear the previous boot cache`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/LocalSoundRepository.kt")

        assertTrue(source.contains("replaceStartupSoundCache("))
        assertFalse(source.contains("clearStartupSoundCache("))
    }
}
