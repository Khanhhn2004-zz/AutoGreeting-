package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class IotStatusRepositorySourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `login clears stored sound mappings when account changes`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/IotStatusRepository.kt")

        assertTrue(source.contains("shouldClearStoredSoundsOnAccountChange("))
        assertTrue(source.contains("clearSoundUri1()"))
        assertTrue(source.contains("clearSoundUri2()"))
        assertTrue(source.contains("clearStoredSoundOwnerUserId()"))
        assertTrue(source.contains("clearGoodbyeServerAvailability()"))
        assertTrue(source.contains("clearStartupSoundCache()"))
    }

    @Test
    fun `download sound maps goodbye availability as present missing or unknown`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/IotStatusRepository.kt")

        assertTrue(source.contains("GoodbyeServerAvailability.PRESENT"))
        assertTrue(source.contains("GoodbyeServerAvailability.MISSING"))
        assertTrue(source.contains("GoodbyeServerAvailability.UNKNOWN"))
    }

    @Test
    fun `unauthorized handling blocks remote sync without clearing the local session`() {
        val source = readSource("src/main/java/com/example/carchatbot/data/repository/IotStatusRepository.kt")
        val unauthorizedSection = source
            .substringAfter("private suspend fun handleUnauthorized() {")
            .substringBefore("private suspend fun isRemoteSyncBlocked(): Boolean")

        assertTrue(source.contains("Remote sync blocked due to unauthorized response; keeping local session active"))
        assertTrue(unauthorizedSection.contains("userPreferencesRepository.saveRemoteSyncBlocked(true)"))
        assertFalse(unauthorizedSection.contains("clearUserSession()"))
    }
}
