package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class UserPreferencesRepositorySourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `sound source enums exist for slot source type and goodbye availability`() {
        assertTrue(
            Files.exists(
                Paths.get("src/main/java/com/example/carchatbot/data/model/SoundSourceType.kt")
            )
        )
        assertTrue(
            Files.exists(
                Paths.get("src/main/java/com/example/carchatbot/data/model/GoodbyeServerAvailability.kt")
            )
        )
    }

    @Test
    fun `user preferences repository persists new slot source metadata fields`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/local/UserPreferencesRepository.kt")

        assertTrue(source.contains("SOUND_SOURCE_TYPE_1_KEY"))
        assertTrue(source.contains("SOUND_SOURCE_TYPE_2_KEY"))
        assertTrue(source.contains("SOUND_LABEL_1_KEY"))
        assertTrue(source.contains("SOUND_LABEL_2_KEY"))
        assertTrue(source.contains("SOUND_GENERATION_KEY"))
        assertTrue(source.contains("SOUND_1_ACCEPTED_GENERATION_KEY"))
        assertTrue(source.contains("SOUND_2_ACCEPTED_GENERATION_KEY"))
        assertTrue(source.contains("CLEANUP_IN_PROGRESS_KEY"))
        assertTrue(source.contains("AUTOPLAY_SUPPRESSED_1_KEY"))
        assertTrue(source.contains("AUTOPLAY_SUPPRESSED_2_KEY"))
        assertTrue(source.contains("GOODBYE_SERVER_AVAILABILITY_KEY"))
    }

    @Test
    fun `clear user session also resets cleanup gate generation and autoplay suppression`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/local/UserPreferencesRepository.kt")

        assertTrue(source.contains("settings.remove(CLEANUP_IN_PROGRESS_KEY)"))
        assertTrue(source.contains("settings.remove(SOUND_GENERATION_KEY)"))
        assertTrue(source.contains("settings.remove(SOUND_1_ACCEPTED_GENERATION_KEY)"))
        assertTrue(source.contains("settings.remove(SOUND_2_ACCEPTED_GENERATION_KEY)"))
        assertTrue(source.contains("settings.remove(AUTOPLAY_SUPPRESSED_1_KEY)"))
        assertTrue(source.contains("settings.remove(AUTOPLAY_SUPPRESSED_2_KEY)"))
        assertTrue(source.contains("settings.remove(GOODBYE_SERVER_AVAILABILITY_KEY)"))
    }

    @Test
    fun `repository persists startup mode as a string preference with legacy boolean fallback`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/local/UserPreferencesRepository.kt")

        assertTrue(source.contains("import com.example.carchatbot.boot.StartupMode"))
        assertTrue(source.contains("val startupMode: Flow<StartupMode>"))
        assertTrue(source.contains("preferences[STARTUP_MODE_KEY]"))
        assertTrue(source.contains("preferences.contains(PLAY_ON_OPEN_ENABLED_KEY)"))
        assertTrue(source.contains("StartupMode.valueOf"))
        assertTrue(source.contains("StartupMode.DEFAULT"))
    }

    @Test
    fun `repository saves startup mode without removing legacy play on open storage`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/local/UserPreferencesRepository.kt")

        assertTrue(source.contains("suspend fun saveStartupMode(startupMode: StartupMode)"))
        assertTrue(source.contains("settings[STARTUP_MODE_KEY] = startupMode.name"))
        assertTrue(source.contains("val playOnOpenEnabled: Flow<Boolean>"))
        assertTrue(source.contains("suspend fun savePlayOnOpenEnabled(isEnabled: Boolean)"))
        assertTrue(source.contains("private val STARTUP_MODE_KEY = stringPreferencesKey(\"startup_mode\")"))
        assertTrue(source.contains("private val PLAY_ON_OPEN_ENABLED_KEY = booleanPreferencesKey(\"play_on_open_enabled\")"))
    }

    @Test
    fun `repository uses device protected datastore and keeps a migration path from legacy session storage`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/local/UserPreferencesRepository.kt")

        assertTrue(source.contains("BootStorageContextProvider"))
        assertTrue(source.contains("PreferenceDataStoreFactory.create"))
        assertTrue(source.contains("private val legacyDataStore"))
        assertTrue(source.contains("suspend fun ensureDeviceProtectedStoreReady()"))
        assertTrue(source.contains("UserManager"))
        assertTrue(source.contains("copyLegacyPreferenceIfPresent("))
        assertTrue(source.contains("clearLegacyDataStore()"))
        assertTrue(source.contains("IS_LOGGED_IN_KEY"))
        assertTrue(source.contains("ACCESS_TOKEN_KEY"))
        assertFalse(source.contains("preferencesDataStore(name = \"settings\")"))
    }

    @Test
    fun `repository gates datastore reads and writes behind session store readiness`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/local/UserPreferencesRepository.kt")

        assertTrue(source.contains("private val readyPreferences: Flow<Preferences> = flow {"))
        assertTrue(source.contains("emitAll(dataStore.data)"))
        assertTrue(source.contains("private suspend fun editDataStore("))
        assertTrue(source.contains("ensureDeviceProtectedStoreReady()"))
    }

    @Test
    fun `repository reuses datastore instances per file path to avoid duplicate active stores`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/local/UserPreferencesRepository.kt")

        assertTrue(source.contains("private val dataStoreCacheLock = Any()"))
        assertTrue(source.contains("private val sharedDataStores = mutableMapOf<String, DataStore<Preferences>>()"))
        assertTrue(source.contains("sharedDataStores.getOrPut(storePath)"))
        assertTrue(source.contains("synchronized(dataStoreCacheLock)"))
    }

    @Test
    fun `repository encrypts access token before storing it in datastore`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/local/UserPreferencesRepository.kt")

        assertTrue(source.contains("AndroidKeyStore"))
        assertTrue(source.contains("AES/GCM/NoPadding"))
        assertTrue(source.contains("private const val ENCRYPTED_TOKEN_PREFIX"))
        assertTrue(source.contains("decryptAccessToken(preferences[ACCESS_TOKEN_KEY])"))
        assertTrue(source.contains("val encryptedToken = encryptAccessToken(token)"))
        assertTrue(source.contains("settings[ACCESS_TOKEN_KEY] = encryptedToken"))
        assertTrue(source.contains("migratePlaintextAccessTokenIfNeeded()"))
        assertFalse(source.contains("settings[ACCESS_TOKEN_KEY] = token"))
    }
}
