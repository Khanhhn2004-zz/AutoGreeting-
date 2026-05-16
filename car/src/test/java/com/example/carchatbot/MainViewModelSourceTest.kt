package com.example.carchatbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class MainViewModelSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `logout deletes managed sound cache before clearing the local session`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainViewModel.kt")

        assertTrue(source.contains("userPreferencesRepository.saveCleanupInProgress(true)"))
        assertTrue(source.contains("userPreferencesRepository.saveSoundGeneration(nextGeneration)"))
        assertTrue(source.contains("awaitPlaybackIdleAfterCleanupStop()"))
        assertTrue(source.contains("soundAssetManager.deleteAllManagedSoundFiles()"))
        assertTrue(source.contains("bootSoundCacheRepository.clearStartupSoundCache()"))
        assertTrue(source.contains("userPreferencesRepository.clearUserSessionForTeardown(nextGeneration)"))
    }

    @Test
    fun `main view model imports selected local sounds through local sound repository`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainViewModel.kt")

        assertTrue(source.contains("LocalSoundRepository"))
        assertTrue(source.contains("fun importLocalSound("))
        assertTrue(source.contains("localSoundRepository.importPickedSound("))
    }

    @Test
    fun `logout and demo teardown stop boot playback before forcing manual state clear`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainViewModel.kt")

        assertTrue(source.contains("BootPlaybackStateStore"))
        assertTrue(source.contains("com.example.carchatbot.boot.BootPlaybackService::class.java"))
        assertTrue(source.contains("bootPlaybackStateStore.readState().isActive()"))
        assertTrue(source.contains("SoundPlayerState.clearManualPlaybackForTeardown()"))
        assertEquals(3, "awaitPlaybackIdleAfterCleanupStop()".toRegex().findAll(source).count())
    }

    @Test
    fun `main view model exposes startup mode and syncs the legacy app open flag`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainViewModel.kt")

        assertTrue(source.contains("import com.example.carchatbot.boot.StartupMode"))
        assertTrue(source.contains("val startupMode = userPreferencesRepository.startupMode"))
        assertTrue(source.contains("fun setStartupMode(startupMode: StartupMode)"))
        assertTrue(source.contains("userPreferencesRepository.saveStartupMode(startupMode)"))
        assertTrue(source.contains("userPreferencesRepository.savePlayOnOpenEnabled(startupMode == StartupMode.APP_AUTO_OPEN)"))
    }

    @Test
    fun `main view model exposes boot playback state for all automatic startup flows`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainViewModel.kt")

        assertTrue(source.contains("val bootPlaybackState = bootPlaybackStateStore.stateFlow"))
    }

    @Test
    fun `main view model exposes startup cache readiness for user visible boot confidence`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainViewModel.kt")

        assertTrue(source.contains("data class StartupCacheUiState"))
        assertTrue(source.contains("val startupCacheUiState: StateFlow<StartupCacheUiState>"))
        assertTrue(source.contains("bootSoundCacheRepository.inspectReadiness()"))
        assertTrue(source.contains("BootSoundCacheReadinessReason.READY"))
        assertTrue(source.contains("S\\u1eb5n s\\u00e0ng ph\\u00e1t khi kh\\u1edfi \\u0111\\u1ed9ng l\\u1ea1i"))
        assertTrue(source.contains("File \\u00e2m thanh c\\u00f3 nh\\u01b0ng cache kh\\u1edfi \\u0111\\u1ed9ng l\\u1ed7i"))
        assertTrue(source.contains("t\\u1ea3i l\\u1ea1i ho\\u1eb7c \\u0111\\u1ed5i file"))
        assertTrue(source.contains("fun refreshStartupCacheStatus()"))
        assertTrue(source.contains("fun prepareDemoStartupSoundCache()"))
        assertTrue(source.contains("soundAssetManager.prepareBundledStartupSoundCache()"))
    }

    @Test
    fun `main view model refreshes startup cache readiness after sound one changes`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainViewModel.kt")

        assertTrue(source.contains("observeStartupSoundCacheReadiness()"))
        assertTrue(source.contains("savedSoundUri1.distinctUntilChanged().collect"))
        assertTrue(source.contains("STARTUP_CACHE_READINESS_RETRY_COUNT"))
        assertTrue(source.contains("STARTUP_CACHE_READINESS_RETRY_DELAY_MS"))
        assertTrue(source.contains("if (soundIndex == BootSoundCacheRepository.STARTUP_SOUND_INDEX)"))
        assertTrue(source.contains("refreshStartupCacheReadiness()"))
    }

    @Test
    fun `main view model only exposes apps script support upload`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainViewModel.kt")

        assertTrue(source.contains("suspend fun uploadSupportLog(): SupportLogUploadResult"))
        assertTrue(source.contains("supportLogAppsScriptUploader.upload()"))
        assertTrue(source.contains("SupportLogAppsScriptUploader"))
        assertFalse(source.contains("fun supportLogExportFileName(): String"))
        assertFalse(source.contains("suspend fun buildSupportLogShareIntent(): Intent"))
        assertFalse(source.contains("suspend fun exportSupportLogToUri(uri: Uri)"))
        assertFalse(source.contains("supportLogExporter."))
    }
}
