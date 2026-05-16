package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class AppBrandingSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `manifest label uses string resource and app name is Chao Xe`() {
        val manifest = readSource("src/main/AndroidManifest.xml")
        val strings = readSource("src/main/res/values/strings.xml")
        val loginScreen = readSource("src/main/java/com/example/carchatbot/ui/login/LoginScreen.kt")
        val mainScreen = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
        assertTrue(strings.contains("<string name=\"app_name\">Chào Xe</string>"))
        assertTrue(strings.contains("<string name=\"main_screen_title\">Giọng Thương Gia</string>"))
        assertFalse(manifest.contains("android:label=\"Auto greeting\""))
        assertTrue(loginScreen.contains("stringResource(R.string.app_name)"))
        assertTrue(mainScreen.contains("stringResource(R.string.main_screen_title)"))
        assertFalse(loginScreen.contains("Giọng Thương Gia"))
    }
}
