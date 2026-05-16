package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class NavViewModelSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `nav view model waits for session store readiness before exposing login state`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/nav/NavViewModel.kt")

        assertTrue(source.contains("MutableStateFlow<Boolean?>(null)"))
        assertTrue(source.contains("userPreferencesRepository.ensureDeviceProtectedStoreReady()"))
        assertTrue(source.contains("userPreferencesRepository.isLoggedIn.collect"))
        assertTrue(source.contains("val isLoggedIn: StateFlow<Boolean?>"))
        assertFalse(source.contains("userPreferencesRepository.isLoggedIn.map<Boolean, Boolean?> { it }"))
    }
}
