package com.example.carchatbot

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.carchatbot.boot.BootPlaybackPhase
import com.example.carchatbot.boot.BootPlaybackService
import com.example.carchatbot.boot.BootPlaybackState
import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.boot.BootSignalOrigin
import com.example.carchatbot.boot.BootSoundCacheRepository
import com.example.carchatbot.boot.BootStorageContextProvider
import com.example.carchatbot.boot.StartupDiagnosticsRepository
import com.example.carchatbot.boot.StartupMode
import com.example.carchatbot.data.local.UserPreferencesRepository
import com.example.carchatbot.data.remote.model.AppLogRequest
import com.example.carchatbot.service.FloatingButtonService
import com.example.carchatbot.service.SoundPlayerService
import com.example.carchatbot.ui.main.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import java.io.File

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.carchatbot", appContext.packageName)
    }

    @Test
    fun app_auto_open_launch_prep_seeds_startup_cache_and_preferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bootContext = BootStorageContextProvider(context)
        val startupDir = bootContext.startupStorageDir("boot_sound")
        val preferences = UserPreferencesRepository(context, bootContext)
        val cacheRepository = BootSoundCacheRepository(bootContext)
        val diagnosticsRepository = StartupDiagnosticsRepository(bootContext)

        stopAllServices(context)
        File(startupDir, "boot_playback_state.properties").delete()
        File(startupDir, "startup-diagnostics.json").delete()
        diagnosticsRepository.clear()
        cacheRepository.clearStartupSoundCache()

        val seedFile = File(context.cacheDir, "app-auto-open-seed.mp3").apply {
            delete()
            context.resources.openRawResource(R.raw.default_goodbye_sound).use { input ->
                outputStream().use { output -> input.copyTo(output) }
            }
        }

        val cacheEntry = cacheRepository.replaceStartupSoundCache(
            soundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
            sourceFile = seedFile
        )
        assertNotNull(cacheEntry)

        preferences.saveIsLoggedIn(true)
        preferences.saveFloatingButtonEnabled(false)
        preferences.savePlayOnOpenEnabled(true)
        preferences.saveStartupMode(StartupMode.APP_AUTO_OPEN)

        assertEquals(true, preferences.isLoggedIn.first())
        assertEquals(StartupMode.APP_AUTO_OPEN, preferences.startupMode.first())
        assertEquals(true, preferences.playOnOpenEnabled.first())
    }

    @Test
    fun app_auto_open_launch_starts_startup_playback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bootContext = BootStorageContextProvider(context)
        val stateStore = BootPlaybackStateStore(bootContext)
        val diagnosticsRepository = StartupDiagnosticsRepository(bootContext)

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        ) as MainActivity
        instrumentation.waitForIdleSync()

        val observedState = waitForStartupPlayback(stateStore)
        val diagnostics = waitForStartupDiagnostics(diagnosticsRepository)

        assertEquals(BootSignalOrigin.APP_AUTO_START, observedState.ownerOrigin)
        assertFalse(observedState.sessionId.isNullOrBlank())
        assertTrue(
            observedState.phase == BootPlaybackPhase.STARTING ||
                observedState.phase == BootPlaybackPhase.PLAYING ||
                observedState.phase == BootPlaybackPhase.COMPLETED
        )
        assertTrue(
            diagnostics.any { log ->
                log.tag == "BootPlaybackService" &&
                    log.message == "Boot playback foreground started"
            }
        )
        assertTrue(
            diagnostics.any { log ->
                log.tag == "BootPlaybackService" &&
                    (
                        log.message == "Boot playback audio started" ||
                            log.message == "Boot playback audio completed"
                        )
            }
        )

        instrumentation.runOnMainSync {
            activity.finish()
        }
        stopAllServices(context)
    }

    private fun waitForStartupPlayback(stateStore: BootPlaybackStateStore): BootPlaybackState {
        repeat(60) {
            val state = stateStore.readState()
            if (
                state.ownerOrigin == BootSignalOrigin.APP_AUTO_START &&
                state.phase != BootPlaybackPhase.IDLE &&
                state.phase != BootPlaybackPhase.CLAIMED
            ) {
                return state
            }
            Thread.sleep(250)
        }
        return stateStore.readState()
    }

    private fun waitForStartupDiagnostics(
        diagnosticsRepository: StartupDiagnosticsRepository
    ): List<AppLogRequest> {
        repeat(60) {
            val snapshot = diagnosticsRepository.snapshot()
            val sawForegroundStart = snapshot.any { log ->
                log.tag == "BootPlaybackService" &&
                    log.message == "Boot playback foreground started"
            }
            val sawAudioStart = snapshot.any { log ->
                log.tag == "BootPlaybackService" &&
                    (
                        log.message == "Boot playback audio started" ||
                            log.message == "Boot playback audio completed"
                        )
            }
            if (sawForegroundStart && sawAudioStart) {
                return snapshot
            }
            Thread.sleep(250)
        }
        return diagnosticsRepository.snapshot()
    }

    private fun stopAllServices(context: android.content.Context) {
        context.stopService(Intent(context, BootPlaybackService::class.java))
        context.stopService(Intent(context, SoundPlayerService::class.java))
        context.stopService(Intent(context, FloatingButtonService::class.java))
        Thread.sleep(250)
    }
}
