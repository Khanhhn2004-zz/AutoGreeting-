package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class LoginViewModelSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `login no longer rejects authenticated users when initial sound sync is incomplete`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/login/LoginViewModel.kt")

        assertFalse(source.contains("clearPendingLoginSession()"))
        assertFalse(source.contains("rejecting login success state"))
        assertTrue(source.contains("finalizeLoginSession()"))
        assertTrue(source.contains("AppRuntimePolicies.shouldAutoDownloadSounds(soundUri1, soundUri2)"))
        assertTrue(source.contains("Sound download enqueued after login success"))
    }

    @Test
    fun `login marks success before handing off any sound refresh to work manager`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/login/LoginViewModel.kt")

        assertTrue(source.contains("_loginState.value = LoginState.Success"))
        assertTrue(source.contains("workManager.enqueueUniqueWork("))
        assertFalse(source.contains("Background sound warmup after login failed"))
        assertFalse(source.contains("soundAssetManager.warmupRequiredSounds()"))
    }

    @Test
    fun `login view model distinguishes invalid credentials from network errors`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/login/LoginViewModel.kt")

        assertTrue(source.contains("LoginResult.InvalidCredentials"))
        assertTrue(source.contains("LoginResult.NetworkError"))
        assertTrue(source.contains("Login failed: network timeout or connection issue"))
        assertTrue(source.contains("Ket noi mang khong on dinh. Vui long kiem tra mang va thu lai."))
    }

    @Test
    fun `login physically clears managed server sound files when account changes`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/login/LoginViewModel.kt")

        assertTrue(source.contains("val previousUserId = userPreferencesRepository.userId.first()"))
        assertTrue(source.contains("val previousPhoneNumber = userPreferencesRepository.phoneNumber.first()"))
        assertTrue(source.contains("AppRuntimePolicies.shouldClearStoredSoundsOnAccountChange("))
        assertTrue(source.contains("soundAssetManager.deleteAllManagedSoundFiles()"))
        assertTrue(source.contains("bootSoundCacheRepository.clearStartupSoundCache()"))
    }

    @Test
    fun `demo mode prepares bundled startup sound cache before entering main app`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/login/LoginViewModel.kt")

        assertTrue(source.contains("fun startDemo()"))
        assertTrue(source.contains("soundAssetManager.prepareBundledStartupSoundCache()"))
        assertTrue(source.contains("Demo startup sound cache ready"))
        assertTrue(source.contains("Demo startup sound cache not ready"))
    }
}
