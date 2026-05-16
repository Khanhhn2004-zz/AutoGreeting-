package com.example.carchatbot.data.local

import android.content.Context
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.example.carchatbot.boot.BootStorageContextProvider
import com.example.carchatbot.boot.StartupMode
import com.example.carchatbot.data.model.GoodbyeServerAvailability
import com.example.carchatbot.data.model.SoundSourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val bootStorageContextProvider: BootStorageContextProvider
) {

    private val deviceProtectedStoreFile = dataStoreFile(bootStorageContextProvider.startupContext())
    private val legacyStoreFile = dataStoreFile(appContext)
    private val dataStore = createDataStore(deviceProtectedStoreFile)
    private val legacyDataStore =
        if (deviceProtectedStoreFile.absolutePath != legacyStoreFile.absolutePath) {
            createDataStore(legacyStoreFile)
        } else {
            null
        }
    private val sessionStoreReadyMutex = Mutex()
    @Volatile
    private var sessionStoreReady = false

    suspend fun ensureDeviceProtectedStoreReady() {
        sessionStoreReadyMutex.withLock {
            if (sessionStoreReady) {
                return
            }

            migrateLegacyPreferencesIfNeeded()
            migratePlaintextAccessTokenIfNeeded()
            sessionStoreReady = true
        }
    }

    private val readyPreferences: Flow<Preferences> = flow {
        ensureDeviceProtectedStoreReady()
        emitAll(dataStore.data)
    }

    private suspend fun editDataStore(transform: (MutablePreferences) -> Unit) {
        ensureDeviceProtectedStoreReady()
        dataStore.edit { settings ->
            transform(settings)
        }
    }

    val soundUri1: Flow<String?> = readyPreferences.map { preferences ->
        preferences[SOUND_URI_1_KEY]
    }

    val soundUri2: Flow<String?> = readyPreferences.map { preferences ->
        preferences[SOUND_URI_2_KEY]
    }

    val storedSoundOwnerUserId: Flow<String?> = readyPreferences.map { preferences ->
        preferences[STORED_SOUND_OWNER_USER_ID_KEY]
    }

    val hasServerGoodbyeSound: Flow<Boolean> = readyPreferences.map { preferences ->
        preferences[HAS_SERVER_GOODBYE_SOUND_KEY] ?: false
    }

    val soundSourceType1: Flow<SoundSourceType> = readyPreferences.map { preferences ->
        preferences[SOUND_SOURCE_TYPE_1_KEY].toSoundSourceType()
    }

    val soundSourceType2: Flow<SoundSourceType> = readyPreferences.map { preferences ->
        preferences[SOUND_SOURCE_TYPE_2_KEY].toSoundSourceType()
    }

    val soundLabel1: Flow<String?> = readyPreferences.map { preferences ->
        preferences[SOUND_LABEL_1_KEY]
    }

    val soundLabel2: Flow<String?> = readyPreferences.map { preferences ->
        preferences[SOUND_LABEL_2_KEY]
    }

    val soundGeneration: Flow<Long> = readyPreferences.map { preferences ->
        preferences[SOUND_GENERATION_KEY] ?: 0L
    }

    val sound1AcceptedGeneration: Flow<Long?> = readyPreferences.map { preferences ->
        preferences[SOUND_1_ACCEPTED_GENERATION_KEY]
    }

    val sound2AcceptedGeneration: Flow<Long?> = readyPreferences.map { preferences ->
        preferences[SOUND_2_ACCEPTED_GENERATION_KEY]
    }

    val cleanupInProgress: Flow<Boolean> = readyPreferences.map { preferences ->
        preferences[CLEANUP_IN_PROGRESS_KEY] ?: false
    }

    val autoplaySuppressed1: Flow<Boolean> = readyPreferences.map { preferences ->
        preferences[AUTOPLAY_SUPPRESSED_1_KEY] ?: false
    }

    val autoplaySuppressed2: Flow<Boolean> = readyPreferences.map { preferences ->
        preferences[AUTOPLAY_SUPPRESSED_2_KEY] ?: false
    }

    val goodbyeServerAvailability: Flow<GoodbyeServerAvailability> = readyPreferences.map { preferences ->
        preferences[GOODBYE_SERVER_AVAILABILITY_KEY].toGoodbyeServerAvailability()
    }

    suspend fun saveSoundUri1(uri: String) {
        editDataStore { settings ->
            settings[SOUND_URI_1_KEY] = uri
        }
    }

    suspend fun saveSoundUri2(uri: String) {
        editDataStore { settings ->
            settings[SOUND_URI_2_KEY] = uri
        }
    }

    suspend fun clearSoundUri1() {
        editDataStore { settings ->
            settings.remove(SOUND_URI_1_KEY)
        }
    }

    suspend fun clearSoundUri2() {
        editDataStore { settings ->
            settings.remove(SOUND_URI_2_KEY)
        }
    }

    suspend fun saveStoredSoundOwnerUserId(userId: String) {
        editDataStore { settings ->
            settings[STORED_SOUND_OWNER_USER_ID_KEY] = userId
        }
    }

    suspend fun clearStoredSoundOwnerUserId() {
        editDataStore { settings ->
            settings.remove(STORED_SOUND_OWNER_USER_ID_KEY)
        }
    }

    suspend fun saveHasServerGoodbyeSound(value: Boolean) {
        editDataStore { settings ->
            settings[HAS_SERVER_GOODBYE_SOUND_KEY] = value
        }
    }

    suspend fun clearHasServerGoodbyeSound() {
        editDataStore { settings ->
            settings.remove(HAS_SERVER_GOODBYE_SOUND_KEY)
        }
    }

    suspend fun saveSoundSourceType(soundIndex: Int, sourceType: SoundSourceType) {
        editDataStore { settings ->
            settings[sourceTypeKey(soundIndex)] = sourceType.name
        }
    }

    suspend fun clearSoundSourceType(soundIndex: Int) {
        editDataStore { settings ->
            settings.remove(sourceTypeKey(soundIndex))
        }
    }

    suspend fun saveSoundLabel(soundIndex: Int, label: String) {
        editDataStore { settings ->
            settings[soundLabelKey(soundIndex)] = label
        }
    }

    suspend fun clearSoundLabel(soundIndex: Int) {
        editDataStore { settings ->
            settings.remove(soundLabelKey(soundIndex))
        }
    }

    suspend fun saveSoundGeneration(generation: Long) {
        editDataStore { settings ->
            settings[SOUND_GENERATION_KEY] = generation
        }
    }

    suspend fun saveSoundAcceptedGeneration(soundIndex: Int, generation: Long) {
        editDataStore { settings ->
            settings[soundAcceptedGenerationKey(soundIndex)] = generation
        }
    }

    suspend fun clearSoundAcceptedGeneration(soundIndex: Int) {
        editDataStore { settings ->
            settings.remove(soundAcceptedGenerationKey(soundIndex))
        }
    }

    suspend fun saveCleanupInProgress(inProgress: Boolean) {
        editDataStore { settings ->
            settings[CLEANUP_IN_PROGRESS_KEY] = inProgress
        }
    }

    suspend fun saveAutoplaySuppressed(soundIndex: Int, suppressed: Boolean) {
        editDataStore { settings ->
            settings[autoplaySuppressedKey(soundIndex)] = suppressed
        }
    }

    suspend fun clearAutoplaySuppressed(soundIndex: Int) {
        editDataStore { settings ->
            settings.remove(autoplaySuppressedKey(soundIndex))
        }
    }

    suspend fun saveGoodbyeServerAvailability(value: GoodbyeServerAvailability) {
        editDataStore { settings ->
            settings[GOODBYE_SERVER_AVAILABILITY_KEY] = value.name
        }
    }

    suspend fun clearGoodbyeServerAvailability() {
        editDataStore { settings ->
            settings.remove(GOODBYE_SERVER_AVAILABILITY_KEY)
        }
    }

    val iotStatus: Flow<Boolean?> = readyPreferences.map { preferences ->
        preferences[IOT_STATUS_KEY]
    }

    suspend fun saveIotStatus(status: Boolean) {
        editDataStore { settings ->
            settings[IOT_STATUS_KEY] = status
        }
    }

    val playOnOpenEnabled: Flow<Boolean> = readyPreferences.map { preferences ->
        preferences[PLAY_ON_OPEN_ENABLED_KEY] ?: false
    }

    suspend fun savePlayOnOpenEnabled(isEnabled: Boolean) {
        editDataStore { settings ->
            settings[PLAY_ON_OPEN_ENABLED_KEY] = isEnabled
        }
    }

    val startupMode: Flow<StartupMode> = readyPreferences.map { preferences ->
        preferences[STARTUP_MODE_KEY]?.let { storedValue ->
            runCatching { StartupMode.valueOf(storedValue) }.getOrDefault(StartupMode.DEFAULT)
        } ?: if (preferences.contains(PLAY_ON_OPEN_ENABLED_KEY)) {
            if (preferences[PLAY_ON_OPEN_ENABLED_KEY] == true) {
                StartupMode.APP_AUTO_OPEN
            } else {
                StartupMode.OFF
            }
        } else {
            StartupMode.DEFAULT
        }
    }

    suspend fun saveStartupMode(startupMode: StartupMode) {
        editDataStore { settings ->
            settings[STARTUP_MODE_KEY] = startupMode.name
        }
    }

    val floatingButtonEnabled: Flow<Boolean> = readyPreferences.map { preferences ->
        preferences[FLOATING_BUTTON_ENABLED_KEY] ?: false
    }

    suspend fun saveFloatingButtonEnabled(isEnabled: Boolean) {
        editDataStore { settings ->
            settings[FLOATING_BUTTON_ENABLED_KEY] = isEnabled
        }
    }

    val isLoggedIn: Flow<Boolean> = readyPreferences.map { preferences ->
        preferences[IS_LOGGED_IN_KEY] ?: false
    }

    suspend fun saveIsLoggedIn(isLoggedIn: Boolean) {
        editDataStore { settings ->
            settings[IS_LOGGED_IN_KEY] = isLoggedIn
        }
    }

    val phoneNumber: Flow<String?> = readyPreferences.map { preferences ->
        preferences[PHONE_NUMBER_KEY]
    }

    suspend fun savePhoneNumber(phoneNumber: String) {
        editDataStore { settings ->
            settings[PHONE_NUMBER_KEY] = phoneNumber
        }
    }

    suspend fun clearPhoneNumber() {
        editDataStore { settings ->
            settings.remove(PHONE_NUMBER_KEY)
        }
    }

    val accessToken: Flow<String?> = readyPreferences.map { preferences ->
        decryptAccessToken(preferences[ACCESS_TOKEN_KEY])
    }

    suspend fun saveAccessToken(token: String) {
        val encryptedToken = encryptAccessToken(token)
        editDataStore { settings ->
            settings[ACCESS_TOKEN_KEY] = encryptedToken
        }
    }

    val isRemoteSyncBlocked: Flow<Boolean> = readyPreferences.map { preferences ->
        preferences[REMOTE_SYNC_BLOCKED_KEY] ?: false
    }

    suspend fun saveRemoteSyncBlocked(isBlocked: Boolean) {
        editDataStore { settings ->
            settings[REMOTE_SYNC_BLOCKED_KEY] = isBlocked
        }
    }

    val userId: Flow<String?> = readyPreferences.map { preferences ->
        preferences[USER_ID_KEY]
    }

    suspend fun saveUserId(userId: String) {
        editDataStore { settings ->
            settings[USER_ID_KEY] = userId
        }
    }

    suspend fun clearUserId() {
        editDataStore { settings ->
            settings.remove(USER_ID_KEY)
        }
    }

    suspend fun clearUserSession() {
        editDataStore { settings ->
            settings.remove(IS_LOGGED_IN_KEY)
            settings.remove(USER_ID_KEY)
            settings.remove(PHONE_NUMBER_KEY)
            settings.remove(ACCESS_TOKEN_KEY)
            settings.remove(SOUND_URI_1_KEY)
            settings.remove(SOUND_URI_2_KEY)
            settings.remove(SOUND_SOURCE_TYPE_1_KEY)
            settings.remove(SOUND_SOURCE_TYPE_2_KEY)
            settings.remove(SOUND_LABEL_1_KEY)
            settings.remove(SOUND_LABEL_2_KEY)
            settings.remove(STORED_SOUND_OWNER_USER_ID_KEY)
            settings.remove(HAS_SERVER_GOODBYE_SOUND_KEY)
            settings.remove(GOODBYE_SERVER_AVAILABILITY_KEY)
            settings.remove(SOUND_GENERATION_KEY)
            settings.remove(SOUND_1_ACCEPTED_GENERATION_KEY)
            settings.remove(SOUND_2_ACCEPTED_GENERATION_KEY)
            settings.remove(CLEANUP_IN_PROGRESS_KEY)
            settings.remove(AUTOPLAY_SUPPRESSED_1_KEY)
            settings.remove(AUTOPLAY_SUPPRESSED_2_KEY)
            settings.remove(DEMO_EXPIRATION_TIME_KEY)
            settings.remove(REMOTE_SYNC_BLOCKED_KEY)
        }
        clearLegacyDataStore()
    }

    suspend fun clearUserSessionForTeardown(preservedGeneration: Long) {
        editDataStore { settings ->
            settings.remove(IS_LOGGED_IN_KEY)
            settings.remove(USER_ID_KEY)
            settings.remove(PHONE_NUMBER_KEY)
            settings.remove(ACCESS_TOKEN_KEY)
            settings.remove(SOUND_URI_1_KEY)
            settings.remove(SOUND_URI_2_KEY)
            settings.remove(SOUND_SOURCE_TYPE_1_KEY)
            settings.remove(SOUND_SOURCE_TYPE_2_KEY)
            settings.remove(SOUND_LABEL_1_KEY)
            settings.remove(SOUND_LABEL_2_KEY)
            settings.remove(STORED_SOUND_OWNER_USER_ID_KEY)
            settings.remove(HAS_SERVER_GOODBYE_SOUND_KEY)
            settings.remove(GOODBYE_SERVER_AVAILABILITY_KEY)
            settings.remove(SOUND_1_ACCEPTED_GENERATION_KEY)
            settings.remove(SOUND_2_ACCEPTED_GENERATION_KEY)
            settings.remove(AUTOPLAY_SUPPRESSED_1_KEY)
            settings.remove(AUTOPLAY_SUPPRESSED_2_KEY)
            settings.remove(DEMO_EXPIRATION_TIME_KEY)
            settings.remove(REMOTE_SYNC_BLOCKED_KEY)
            settings[SOUND_GENERATION_KEY] = preservedGeneration
            settings[CLEANUP_IN_PROGRESS_KEY] = true
        }
        clearLegacyDataStore()
    }

    val demoExpirationTime: Flow<Long?> = readyPreferences.map { preferences ->
        preferences[DEMO_EXPIRATION_TIME_KEY]
    }

    suspend fun saveDemoExpirationTime(time: Long) {
        editDataStore { settings ->
            settings[DEMO_EXPIRATION_TIME_KEY] = time
        }
    }

    suspend fun clearDemoExpirationTime() {
        editDataStore { settings ->
            settings.remove(DEMO_EXPIRATION_TIME_KEY)
        }
    }

    val isDemoConsumed: Flow<Boolean> = readyPreferences.map { preferences ->
        preferences[IS_DEMO_CONSUMED_KEY] ?: false
    }

    suspend fun saveIsDemoConsumed(isConsumed: Boolean) {
        editDataStore { settings ->
            settings[IS_DEMO_CONSUMED_KEY] = isConsumed
        }
    }

    private suspend fun migrateLegacyPreferencesIfNeeded() {
        val legacyStore = legacyDataStore ?: return
        if (!isUserUnlocked()) {
            return
        }

        val legacyPreferences = legacyStore.data.first()
        if (legacyPreferences.asMap().isEmpty()) {
            return
        }

        val currentPreferences = dataStore.data.first()
        if (!shouldMigrateLegacyPreferences(currentPreferences, legacyPreferences)) {
            return
        }

        dataStore.edit { settings ->
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, SOUND_URI_1_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, SOUND_URI_2_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, SOUND_SOURCE_TYPE_1_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, SOUND_SOURCE_TYPE_2_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, SOUND_LABEL_1_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, SOUND_LABEL_2_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, STORED_SOUND_OWNER_USER_ID_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, HAS_SERVER_GOODBYE_SOUND_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, GOODBYE_SERVER_AVAILABILITY_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, SOUND_GENERATION_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, SOUND_1_ACCEPTED_GENERATION_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, SOUND_2_ACCEPTED_GENERATION_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, CLEANUP_IN_PROGRESS_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, AUTOPLAY_SUPPRESSED_1_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, AUTOPLAY_SUPPRESSED_2_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, IOT_STATUS_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, FLOATING_BUTTON_ENABLED_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, PLAY_ON_OPEN_ENABLED_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, STARTUP_MODE_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, IS_LOGGED_IN_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, PHONE_NUMBER_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, ACCESS_TOKEN_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, REMOTE_SYNC_BLOCKED_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, USER_ID_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, DEMO_EXPIRATION_TIME_KEY)
            copyLegacyPreferenceIfPresent(legacyPreferences, settings, IS_DEMO_CONSUMED_KEY)
        }
        clearLegacyDataStore()
    }

    private suspend fun migratePlaintextAccessTokenIfNeeded() {
        val currentToken = dataStore.data.first()[ACCESS_TOKEN_KEY] ?: return
        if (isEncryptedAccessToken(currentToken)) {
            return
        }

        val encryptedToken = runCatching {
            encryptAccessToken(currentToken)
        }.getOrNull() ?: return

        dataStore.edit { settings ->
            if (settings[ACCESS_TOKEN_KEY] == currentToken) {
                settings[ACCESS_TOKEN_KEY] = encryptedToken
            }
        }
    }

    private fun shouldMigrateLegacyPreferences(
        currentPreferences: Preferences,
        legacyPreferences: Preferences
    ): Boolean {
        if (currentPreferences.asMap().isEmpty()) {
            return true
        }

        val currentHasSession = hasSessionIdentity(currentPreferences)
        val legacyHasSession = hasSessionIdentity(legacyPreferences)
        if (!currentHasSession && legacyHasSession) {
            return true
        }

        val currentHasStartupConfig = hasStartupConfig(currentPreferences)
        val legacyHasStartupConfig = hasStartupConfig(legacyPreferences)
        return !currentHasStartupConfig && legacyHasStartupConfig
    }

    private fun hasSessionIdentity(preferences: Preferences): Boolean {
        return preferences[IS_LOGGED_IN_KEY] == true ||
            !preferences[ACCESS_TOKEN_KEY].isNullOrBlank() ||
            !preferences[PHONE_NUMBER_KEY].isNullOrBlank() ||
            !preferences[USER_ID_KEY].isNullOrBlank()
    }

    private fun hasStartupConfig(preferences: Preferences): Boolean {
        return preferences[STARTUP_MODE_KEY] != null ||
            preferences.contains(PLAY_ON_OPEN_ENABLED_KEY) ||
            preferences.contains(FLOATING_BUTTON_ENABLED_KEY)
    }

    private fun isUserUnlocked(): Boolean {
        return appContext.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
    }

    private fun decryptAccessToken(storedToken: String?): String? {
        if (storedToken.isNullOrBlank()) {
            return null
        }
        if (!isEncryptedAccessToken(storedToken)) {
            return storedToken
        }

        return runCatching {
            val payload = storedToken.removePrefix(ENCRYPTED_TOKEN_PREFIX)
            val parts = payload.split(TOKEN_PARTS_SEPARATOR, limit = 2)
            if (parts.size != 2) {
                return@runCatching null
            }

            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encryptedBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TOKEN_CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateAccessTokenKey(),
                GCMParameterSpec(TOKEN_GCM_TAG_LENGTH_BITS, iv)
            )
            String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun encryptAccessToken(token: String): String {
        val cipher = Cipher.getInstance(TOKEN_CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAccessTokenKey())

        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encryptedToken = Base64.encodeToString(
            cipher.doFinal(token.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        return "$ENCRYPTED_TOKEN_PREFIX$iv$TOKEN_PARTS_SEPARATOR$encryptedToken"
    }

    private fun isEncryptedAccessToken(token: String): Boolean {
        return token.startsWith(ENCRYPTED_TOKEN_PREFIX)
    }

    private fun getOrCreateAccessTokenKey(): SecretKey {
        val keyStore = KeyStore.getInstance(TOKEN_KEYSTORE_PROVIDER).apply {
            load(null)
        }
        val existingKey = keyStore.getEntry(ACCESS_TOKEN_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) {
            return existingKey.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            TOKEN_KEYSTORE_PROVIDER
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                ACCESS_TOKEN_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun createDataStore(storeFile: File): DataStore<Preferences> {
        val storePath = storeFile.absolutePath
        return synchronized(dataStoreCacheLock) {
            sharedDataStores.getOrPut(storePath) {
                PreferenceDataStoreFactory.create(
                    produceFile = { storeFile }
                )
            }
        }
    }

    private fun dataStoreFile(context: Context): File {
        return File(context.filesDir, "datastore/settings.preferences_pb").apply {
            parentFile?.mkdirs()
        }
    }

    private fun <T> copyLegacyPreferenceIfPresent(
        source: Preferences,
        target: MutablePreferences,
        key: Preferences.Key<T>
    ) {
        source[key]?.let { value ->
            target[key] = value
        }
    }

    private suspend fun clearLegacyDataStore() {
        legacyDataStore?.edit { settings ->
            settings.clear()
        }
    }

    companion object {
        private val dataStoreCacheLock = Any()
        private val sharedDataStores = mutableMapOf<String, DataStore<Preferences>>()

        private val SOUND_URI_1_KEY = stringPreferencesKey("sound_uri_1")
        private val SOUND_URI_2_KEY = stringPreferencesKey("sound_uri_2")
        private val SOUND_SOURCE_TYPE_1_KEY = stringPreferencesKey("sound_source_type_1")
        private val SOUND_SOURCE_TYPE_2_KEY = stringPreferencesKey("sound_source_type_2")
        private val SOUND_LABEL_1_KEY = stringPreferencesKey("sound_label_1")
        private val SOUND_LABEL_2_KEY = stringPreferencesKey("sound_label_2")
        private val STORED_SOUND_OWNER_USER_ID_KEY = stringPreferencesKey("stored_sound_owner_user_id")
        private val HAS_SERVER_GOODBYE_SOUND_KEY = booleanPreferencesKey("has_server_goodbye_sound")
        private val GOODBYE_SERVER_AVAILABILITY_KEY = stringPreferencesKey("goodbye_server_availability")
        private val SOUND_GENERATION_KEY = longPreferencesKey("sound_generation")
        private val SOUND_1_ACCEPTED_GENERATION_KEY = longPreferencesKey("sound_1_accepted_generation")
        private val SOUND_2_ACCEPTED_GENERATION_KEY = longPreferencesKey("sound_2_accepted_generation")
        private val CLEANUP_IN_PROGRESS_KEY = booleanPreferencesKey("cleanup_in_progress")
        private val AUTOPLAY_SUPPRESSED_1_KEY = booleanPreferencesKey("autoplay_suppressed_1")
        private val AUTOPLAY_SUPPRESSED_2_KEY = booleanPreferencesKey("autoplay_suppressed_2")
        private val IOT_STATUS_KEY = booleanPreferencesKey("iot_status")
        private val FLOATING_BUTTON_ENABLED_KEY = booleanPreferencesKey("floating_button_enabled")
        private val PLAY_ON_OPEN_ENABLED_KEY = booleanPreferencesKey("play_on_open_enabled")
        private val STARTUP_MODE_KEY = stringPreferencesKey("startup_mode")
        private val IS_LOGGED_IN_KEY = booleanPreferencesKey("is_logged_in")
        private val PHONE_NUMBER_KEY = stringPreferencesKey("phone_number")
        private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
        private val REMOTE_SYNC_BLOCKED_KEY = booleanPreferencesKey("remote_sync_blocked")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val DEMO_EXPIRATION_TIME_KEY = longPreferencesKey("demo_expiration_time")
        private val IS_DEMO_CONSUMED_KEY = booleanPreferencesKey("is_demo_consumed")

        private const val TOKEN_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val ACCESS_TOKEN_KEY_ALIAS = "chao_xe_access_token_key"
        private const val TOKEN_CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TOKEN_GCM_TAG_LENGTH_BITS = 128
        private const val ENCRYPTED_TOKEN_PREFIX = "enc:v1:"
        private const val TOKEN_PARTS_SEPARATOR = ":"
    }

    private fun sourceTypeKey(soundIndex: Int) = if (soundIndex == 2) {
        SOUND_SOURCE_TYPE_2_KEY
    } else {
        SOUND_SOURCE_TYPE_1_KEY
    }

    private fun soundLabelKey(soundIndex: Int) = if (soundIndex == 2) {
        SOUND_LABEL_2_KEY
    } else {
        SOUND_LABEL_1_KEY
    }

    private fun soundAcceptedGenerationKey(soundIndex: Int) = if (soundIndex == 2) {
        SOUND_2_ACCEPTED_GENERATION_KEY
    } else {
        SOUND_1_ACCEPTED_GENERATION_KEY
    }

    private fun autoplaySuppressedKey(soundIndex: Int) = if (soundIndex == 2) {
        AUTOPLAY_SUPPRESSED_2_KEY
    } else {
        AUTOPLAY_SUPPRESSED_1_KEY
    }

    private fun String?.toSoundSourceType(): SoundSourceType {
        return runCatching { SoundSourceType.valueOf(this.orEmpty()) }
            .getOrDefault(SoundSourceType.NONE)
    }

    private fun String?.toGoodbyeServerAvailability(): GoodbyeServerAvailability {
        return runCatching { GoodbyeServerAvailability.valueOf(this.orEmpty()) }
            .getOrDefault(GoodbyeServerAvailability.UNKNOWN)
    }
}
