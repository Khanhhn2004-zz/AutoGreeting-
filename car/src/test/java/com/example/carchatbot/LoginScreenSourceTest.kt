package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class LoginScreenSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `login screen requests focus and shows keyboard on first field`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/login/LoginScreen.kt")

        assertTrue(source.contains("FocusRequester"))
        assertTrue(source.contains("LocalSoftwareKeyboardController.current"))
        assertTrue(source.contains("phoneFocusRequester.requestFocus()"))
        assertTrue(source.contains("keyboardController?.show()"))
        assertTrue(source.contains("focusRequester = phoneFocusRequester"))
        assertTrue(source.contains("Modifier.focusRequester(focusRequester)"))
    }
}
