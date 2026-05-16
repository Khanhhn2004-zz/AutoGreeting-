package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class CoreServiceSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `boot init no longer launches boot preview activity`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertFalse(source.contains("Boot preview activity launched"))
        assertFalse(source.contains("EXTRA_BOOT_PREVIEW_MODE"))
        assertFalse(source.contains("startActivity(previewIntent)"))
    }

    @Test
    fun `manual sound download delegates to sound asset manager instead of writing raw filenames directly`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertTrue(source.contains("soundAssetManager.refreshManagedSound(1)"))
        assertTrue(source.contains("soundAssetManager.refreshManagedSound(2)"))
        assertTrue(source.contains("soundAssetManager.ensureSoundAvailable(1)"))
        assertTrue(source.contains("soundAssetManager.ensureSoundAvailable(2)"))
        assertFalse(source.contains("userPreferencesRepository.saveSoundUri1(savedFile.toURI().toString())"))
        assertFalse(source.contains("userPreferencesRepository.saveSoundUri2(savedFile.toURI().toString())"))
    }

    @Test
    fun `runtime service check no longer owns autoplay gating`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertTrue(source.contains("ensureRuntimeServicesHealthy("))
        assertFalse(source.contains("playOnOpenEnabled.first()"))
        assertFalse(source.contains("requestBootAutoplay("))
    }

    @Test
    fun `boot init and app open no longer depend on explicit sound player autoplay requests`() {
        val coreSource = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")
        val activitySource = readSource("src/main/java/com/example/carchatbot/ui/main/MainActivity.kt")

        assertFalse(coreSource.contains("PlaybackRequestIntent.AUTOPLAY_START"))
        assertFalse(activitySource.contains("PlaybackRequestIntent.AUTOPLAY_START"))
        assertTrue(activitySource.contains("BootPlaybackService.startForExecution"))
        assertFalse(coreSource.contains("playbackSoundResolver.resolve("))
    }

    @Test
    fun `manual reload refreshes each managed sound without deleting the current cache upfront`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertTrue(source.contains("soundAssetManager.refreshManagedSound(1)"))
        assertTrue(source.contains("soundAssetManager.refreshManagedSound(2)"))
        assertTrue(source.contains("soundAssetManager.ensureSoundAvailable(1)"))
        assertTrue(source.contains("soundAssetManager.ensureSoundAvailable(2)"))
        assertFalse(source.contains("prepareManagedSoundRefresh(\"manual_sound_download\")"))
        assertFalse(source.contains("soundAssetManager.deleteAllManagedSoundFiles()"))
    }

    @Test
    fun `manual reload stops active manual playback before replacing fixed server files`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        val stopIndex = source.indexOf("SoundPlayerService.requestStopForServerRefresh(")
        val firstRefreshIndex = source.indexOf("soundAssetManager.refreshManagedSound(1)")

        assertTrue(stopIndex >= 0)
        assertTrue(firstRefreshIndex > stopIndex)
    }

    @Test
    fun `manual reload no longer clears slot metadata just because goodbye refresh is missing on server`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertFalse(source.contains("clearStoredSoundState(preserveGoodbyeAvailability = true)"))
        assertFalse(source.contains("private suspend fun prepareManagedSoundRefresh(reason: String)"))
    }

    @Test
    fun `manual reload seeds bundled startup cache during demo instead of requiring server credentials`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertTrue(source.contains("prepareBundledStartupSoundCacheForDemo()"))
        assertTrue(source.contains("userPreferencesRepository.demoExpirationTime.first()"))
        assertTrue(source.contains("soundAssetManager.prepareBundledStartupSoundCache()"))
        assertTrue(source.contains("Preparing bundled demo startup sound cache"))
        assertTrue(source.contains("sendBroadcast(Intent(ACTION_SOUND_DOWNLOADED).setPackage(packageName))"))
    }

    @Test
    fun `core service only consumes pending boot startup through startup coordinator during reconcile`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertFalse(source.contains("requestBootAutoplay("))
        assertFalse(source.contains("private suspend fun requestBootAutoplay"))
        assertFalse(source.contains("private fun buildAutoplayIntent()"))
        assertFalse(source.contains("SoundPlayerService.EXTRA_PLAYBACK_REQUEST_INTENT, PlaybackRequestIntent.AUTOPLAY_START.name"))
        assertTrue(source.contains("StartupCompatCoordinator"))
        assertTrue(source.contains("startupCompatCoordinator.handleRuntimeReconcileSignal("))
        assertTrue(source.contains("StartupTriggerRouter.allowsBootCompleted(startupMode)"))
        assertFalse(source.contains("StartupTriggerRouter.allowsAppAutoOpen(startupMode)"))
    }

    @Test
    fun `startup sound path only routes through core service for post boot reconcile`() {
        val coreSource = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")
        val receiverSource = readSource("src/main/java/com/example/carchatbot/service/BootCompletedReceiver.kt")

        assertFalse(coreSource.contains("ACTION_BOOT_INIT"))
        assertFalse(coreSource.contains("ACTION_SHUTDOWN"))
        assertTrue(receiverSource.contains("BootPlaybackService.startForExecution"))
        assertTrue(receiverSource.contains("CoreService.ACTION_RECONCILE_RUNTIME_SERVICES"))
        assertFalse(receiverSource.contains("CoreService.ACTION_BOOT_INIT"))
        assertFalse(receiverSource.contains("CoreService.ACTION_SHUTDOWN"))
    }

    @Test
    fun `shutdown self cancels service scope before child service teardown`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")
        val shutdownSection = source
            .substringAfter("private fun shutdownSelf(reason: String) {")
            .substringBefore("private suspend fun monitorChildServices()")

        val cancelIndex = shutdownSection.indexOf("serviceScope.cancel(")
        val childShutdownIndex = shutdownSection.indexOf("shutdownChildServices()")
        val stopSelfIndex = shutdownSection.indexOf("stopSelf()")

        assertTrue(cancelIndex >= 0)
        assertTrue(childShutdownIndex >= 0)
        assertTrue(stopSelfIndex >= 0)
        assertTrue(cancelIndex < childShutdownIndex)
        assertTrue(cancelIndex < stopSelfIndex)
    }

    @Test
    fun `child service monitoring avoids deprecated running services lookup`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertFalse(source.contains("ActivityManager"))
        assertFalse(source.contains("getRunningServices("))
        assertTrue(source.contains("FloatingButtonService.isServiceMarkedRunning()"))
    }

    @Test
    fun `core service reconciles floating overlay without using app foreground as a suppression rule`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertTrue(source.contains("ACTION_RECONCILE_RUNTIME_SERVICES"))
        assertTrue(source.contains("shouldRestartFloatingButtonService("))
        assertTrue(source.contains("shouldStopFloatingButtonService("))
        assertTrue(source.contains("FloatingButtonService.createStartIntent("))
        assertTrue(source.contains("FloatingButtonService.LAUNCH_REASON_RUNTIME_RECONCILE"))
        assertFalse(source.contains("ACTION_APP_UI_FOREGROUND"))
        assertFalse(source.contains("ACTION_APP_UI_BACKGROUND"))
        assertFalse(source.contains("appUiVisible"))
        assertTrue(source.contains("recoverPendingBootStartupFromRuntimeReconcile("))
        assertTrue(source.contains("startupCompatCoordinator.handleRuntimeReconcileSignal("))
        assertTrue(source.contains("BootPlaybackService.startForExecution"))
        assertFalse(source.contains("SoundPlayerService.EXTRA_PLAYBACK_REQUEST_INTENT, PlaybackRequestIntent.AUTOPLAY_START.name"))
    }

    @Test
    fun `core service logs detailed floating overlay reconcile state for diagnostics export`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertTrue(source.contains("FloatingButtonService reconcile evaluated"))
        assertTrue(source.contains("overlayCompat="))
        assertTrue(source.contains("overlayRaw="))
        assertTrue(source.contains("shouldStartFloatingButton="))
        assertTrue(source.contains("floatingButtonRunning="))
        assertTrue(source.contains("launchReason = FloatingButtonService.LAUNCH_REASON_RUNTIME_RECONCILE"))
    }

    @Test
    fun `core service does not register power broadcasts as pseudo-startup signals`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertFalse(source.contains("Intent.ACTION_POWER_CONNECTED"))
        assertFalse(source.contains("Intent.ACTION_POWER_DISCONNECTED"))
        assertFalse(source.contains("registerReceiver(powerReceiver"))
        assertFalse(source.contains("unregisterReceiver(powerReceiver"))
    }

    @Test
    fun `core service wake lock is non reference counted and released through guarded helper`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")
        val downloadSection = source
            .substringAfter("action == ACTION_START_SOUND_DOWNLOAD -> {")
            .substringBefore("shouldIgnoreExternalPowerAction(action)")
        val onDestroySection = source
            .substringAfter("override fun onDestroy() {")
            .substringBefore("private fun startHeartbeatLoop()")

        assertTrue(source.contains("wakeLock?.setReferenceCounted(false)"))
        assertTrue(downloadSection.contains("try {"))
        assertTrue(downloadSection.contains("finally {"))
        assertTrue(downloadSection.contains("releaseWakeLock()"))
        assertTrue(onDestroySection.contains("releaseWakeLock()"))
        assertFalse(onDestroySection.contains("wakeLock?.release()"))
    }
}
