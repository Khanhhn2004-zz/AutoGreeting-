package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class ManualSoundReloadSessionExpirySourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `main screen shows reauth prompt instead of starting manual reload when remote sync is blocked`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("val sessionExpiredMessage ="))
        assertTrue(source.contains("Toast.makeText(context, sessionExpiredMessage, Toast.LENGTH_LONG).show()"))
        assertTrue(source.contains("CoreService.ACTION_SOUND_FAILED"))
        assertTrue(source.contains("CoreService.EXTRA_SOUND_FAILURE_REASON"))
        assertTrue(source.contains("CoreService.SOUND_FAILURE_REASON_SESSION_EXPIRED"))
        assertTrue(source.contains("if (isRemoteSyncBlocked)"))
    }

    @Test
    fun `core service broadcasts session expired reason for manual reload failures caused by unauthorized state`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertTrue(source.contains("EXTRA_SOUND_FAILURE_REASON"))
        assertTrue(source.contains("SOUND_FAILURE_REASON_SESSION_EXPIRED"))
        assertTrue(source.contains("userPreferencesRepository.isRemoteSyncBlocked.first()"))
        assertTrue(source.contains("sendSoundFailureBroadcast(SOUND_FAILURE_REASON_SESSION_EXPIRED)"))
        assertTrue(source.contains("intent.putExtra(EXTRA_SOUND_FAILURE_REASON, reason)"))
    }

    @Test
    fun `account switch clears prior assets in a cleanup generation before login hands off refresh work`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/login/LoginViewModel.kt")

        assertTrue(source.contains("prepareManagedSoundRefresh(\"account_switch_login\")"))
        assertTrue(source.contains("userPreferencesRepository.saveCleanupInProgress(true)"))
        assertTrue(source.contains("userPreferencesRepository.saveSoundGeneration("))
        assertTrue(source.contains("soundAssetManager.deleteAllManagedSoundFiles()"))
        assertTrue(source.contains("bootSoundCacheRepository.clearStartupSoundCache()"))
        assertTrue(source.contains("try {"))
        assertTrue(source.contains("finally {"))
        assertTrue(source.contains("userPreferencesRepository.saveCleanupInProgress(false)"))
        assertTrue(source.contains("workManager.enqueueUniqueWork("))
        assertFalse(source.contains("soundAssetManager.warmupRequiredSounds()"))
        assertTrue(source.contains("clearAutoplaySuppressed(1)"))
        assertTrue(source.contains("clearAutoplaySuppressed(2)"))
        assertTrue(source.contains("awaitPlaybackIdleAfterCleanupStop()"))

        val prepareIndex = source.indexOf("prepareManagedSoundRefresh(\"account_switch_login\")")
        val workerIndex = source.indexOf("workManager.enqueueUniqueWork(")
        val helperIndex = source.indexOf("private suspend fun prepareManagedSoundRefresh(reason: String)")
        val cleanupStartIndex = source.indexOf("userPreferencesRepository.saveCleanupInProgress(true)", helperIndex)
        val deleteIndex = source.indexOf("soundAssetManager.deleteAllManagedSoundFiles()", helperIndex)
        val cleanupEndIndex = source.indexOf("userPreferencesRepository.saveCleanupInProgress(false)", helperIndex)

        assertTrue(prepareIndex in 0 until workerIndex)
        assertTrue(helperIndex >= 0)
        assertTrue(cleanupStartIndex in helperIndex until deleteIndex)
        assertTrue(deleteIndex in helperIndex until cleanupEndIndex)
    }
}
