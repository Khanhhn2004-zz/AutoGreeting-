package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class BootStorageContextProviderSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `boot storage context provider uses device protected storage for startup assets`() {
        val path = Paths.get("src/main/java/com/example/carchatbot/boot/BootStorageContextProvider.kt")
        assertTrue(Files.exists(path))

        val source = readSource(path.toString())

        assertTrue(source.contains("createDeviceProtectedStorageContext"))
        assertTrue(source.contains("isDeviceProtectedStorage"))
        assertTrue(source.contains("startupStorageDir("))
    }
}
