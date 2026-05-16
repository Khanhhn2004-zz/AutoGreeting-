package com.example.carchatbot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.boot.BootPlaybackPhase
import com.example.carchatbot.boot.BootSoundCacheRepository
import com.example.carchatbot.boot.BootStorageContextProvider
import com.example.carchatbot.boot.StartupMode
import com.example.carchatbot.boot.StartupDiagnosticsRepository
import com.example.carchatbot.data.local.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RealDeviceBootPreparationInstrumentedTest {

    @Test
    fun seed_boot_cache_and_restore_prerequisites_for_real_device_boot_run() = runBlocking {
        assumeManualRealDeviceFlowEnabled()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bootContext = BootStorageContextProvider(context)
        val startupDir = bootContext.startupStorageDir("boot_sound")
        File(startupDir, "boot_playback_state.properties").delete()
        File(startupDir, "startup-diagnostics.json").delete()

        StartupDiagnosticsRepository(bootContext).clear()

        val seedFile = File(context.cacheDir, "boot-seed.mp3").apply {
            delete()
            context.resources.openRawResource(R.raw.default_goodbye_sound).use { input ->
                outputStream().use { output -> input.copyTo(output) }
            }
        }

        val cacheEntry = BootSoundCacheRepository(bootContext).replaceStartupSoundCache(
            soundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
            sourceFile = seedFile
        )
        assertNotNull(cacheEntry)

        val preferences = UserPreferencesRepository(context, bootContext)
        preferences.saveIsLoggedIn(true)
        preferences.saveFloatingButtonEnabled(true)
        preferences.savePlayOnOpenEnabled(false)
        preferences.saveStartupMode(StartupMode.BOOT_COMPLETED)

        assertEquals(true, preferences.isLoggedIn.first())
        assertEquals(true, preferences.floatingButtonEnabled.first())
        assertEquals(false, preferences.playOnOpenEnabled.first())
        assertEquals(StartupMode.BOOT_COMPLETED, preferences.startupMode.first())

        val state = BootPlaybackStateStore(bootContext).readState()
        assertEquals(BootPlaybackPhase.IDLE, state.phase)
        assertEquals(null, state.startupWindowId)
        assertEquals(emptyList<com.example.carchatbot.data.remote.model.AppLogRequest>(), StartupDiagnosticsRepository(bootContext).snapshot())
    }
}
