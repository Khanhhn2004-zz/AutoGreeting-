package com.example.carchatbot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carchatbot.boot.BootPlaybackPhase
import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.boot.BootSignalOrigin
import com.example.carchatbot.boot.BootSoundCacheRepository
import com.example.carchatbot.boot.BootStorageContextProvider
import com.example.carchatbot.boot.StartupArmStateStore
import com.example.carchatbot.boot.StartupCompatibilityProfile
import com.example.carchatbot.boot.StartupDiagnosticsRepository
import com.example.carchatbot.boot.StartupMode
import com.example.carchatbot.data.local.UserPreferencesRepository
import com.example.carchatbot.data.remote.model.AppLogRequest
import com.example.carchatbot.runtime.AppRuntimePolicies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RealDeviceSleepWakePreparationInstrumentedTest {

    @Test
    fun seed_pending_startup_window_for_manual_sleep_wake_recovery() = runBlocking {
        assumeManualRealDeviceFlowEnabled()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bootContext = BootStorageContextProvider(context)
        val bootDir = bootContext.startupStorageDir("boot_sound")
        val armDir = bootContext.startupStorageDir("startup_arm")

        File(bootDir, "boot_playback_state.properties").delete()
        File(bootDir, "startup-diagnostics.json").delete()
        File(armDir, "startup_arm_state.properties").delete()
        StartupDiagnosticsRepository(bootContext).clear()

        val seedFile = File(context.cacheDir, "sleep-wake-seed.mp3").apply {
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

        val profile = StartupCompatibilityProfile.HEAD_UNIT_SLEEP_WAKE
        val nowMillis = System.currentTimeMillis()
        val startupWindowId = AppRuntimePolicies.calculateBootStartupWindowId(
            nowMillis = nowMillis,
            windowMillis = profile.startupWindowMillis
        )
        val armedState = StartupArmStateStore(bootContext).armStartupWindow(
            startupWindowId = startupWindowId,
            origin = BootSignalOrigin.RECEIVER,
            compatibilityProfile = profile,
            nowMillis = nowMillis
        )

        val playbackState = BootPlaybackStateStore(bootContext).readState()
        val diagnostics = StartupDiagnosticsRepository(bootContext).snapshot()

        assertEquals(startupWindowId, armedState.startupWindowId)
        assertEquals(profile, armedState.compatibilityProfile)
        assertEquals(setOf(BootSignalOrigin.RECEIVER), armedState.observedOrigins)
        assertTrue(armedState.isPending())
        assertNotNull(armedState.armedAtMillis)
        assertNull(armedState.consumedAtMillis)

        assertEquals(BootPlaybackPhase.IDLE, playbackState.phase)
        assertNull(playbackState.startupWindowId)
        assertNull(playbackState.ownerOrigin)
        assertEquals(emptyList<AppLogRequest>(), diagnostics)
    }
}
