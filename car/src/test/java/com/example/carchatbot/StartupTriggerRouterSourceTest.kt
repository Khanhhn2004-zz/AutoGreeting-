package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class StartupTriggerRouterSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `startup trigger router allows boot mode only for boot completed`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/StartupTriggerRouter.kt")

        assertTrue(source.contains("fun allowsBootCompleted"))
        assertTrue(source.contains("startupMode == StartupMode.BOOT_COMPLETED"))
        assertFalse(source.contains("BOOT_WITH_APP_FALLBACK"))
    }

    @Test
    fun `startup trigger router allows app open mode only for app auto open`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/StartupTriggerRouter.kt")

        assertTrue(source.contains("fun allowsAppAutoOpen"))
        assertTrue(source.contains("startupMode == StartupMode.APP_AUTO_OPEN"))
        assertFalse(source.contains("BOOT_WITH_APP_FALLBACK"))
    }
}
