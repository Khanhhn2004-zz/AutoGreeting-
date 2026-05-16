package com.example.carchatbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class StartupModeSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `startup mode declares the explicit three mode startup playback contract`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/StartupMode.kt")

        assertTrue(source.contains("enum class StartupMode"))
        assertTrue(source.contains("OFF"))
        assertTrue(source.contains("BOOT_COMPLETED"))
        assertTrue(source.contains("APP_AUTO_OPEN"))
        assertFalse(source.contains("BOOT_WITH_APP_FALLBACK"))
    }

    @Test
    fun `boot completed remains the default startup mode for fresh installs`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/StartupMode.kt")

        assertTrue(source.contains("DEFAULT"))
        assertTrue(source.contains("StartupMode.BOOT_COMPLETED"))
    }

    @Test
    fun `startup mode source keeps only the three approved enum entries`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/StartupMode.kt")
        val entries = Regex("""\b(OFF|BOOT_COMPLETED|APP_AUTO_OPEN)\b""")
            .findAll(source.substringAfter("enum class StartupMode"))
            .map { it.value }
            .distinct()
            .toList()

        assertEquals(listOf("OFF", "BOOT_COMPLETED", "APP_AUTO_OPEN"), entries)
    }
}
