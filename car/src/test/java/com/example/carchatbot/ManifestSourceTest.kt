package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.text.Charsets.UTF_8

class ManifestSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `manifest disables app backup restore`() {
        val manifest = readSource("src/main/AndroidManifest.xml")

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun `manifest registers boot playback service as media playback foreground service`() {
        val manifest = readSource("src/main/AndroidManifest.xml")

        assertTrue(manifest.contains("com.example.carchatbot.boot.BootPlaybackService"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"mediaPlayback\""))
    }

    @Test
    fun `manifest boot receiver keeps only approved startup and quickboot actions`() {
        val manifest = readSource("src/main/AndroidManifest.xml")

        assertTrue(manifest.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(manifest.contains("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertTrue(manifest.contains("android.intent.action.QUICKBOOT_POWERON"))
        assertTrue(manifest.contains("com.htc.intent.action.QUICKBOOT_POWERON"))

        assertFalse(manifest.contains("com.example.carchatbot.DEBUG_ACC_ON"))
        assertFalse(manifest.contains("com.example.carchatbot.DEBUG_ACC_OFF"))
        assertFalse(manifest.contains("android.intent.action.ACTION_POWER_CONNECTED"))
        assertFalse(manifest.contains("android.intent.action.ACTION_POWER_DISCONNECTED"))
        assertFalse(manifest.contains("android.intent.action.QUICKBOOT_POWEROFF"))
        assertFalse(manifest.contains("android.intent.action.ACC_ON"))
        assertFalse(manifest.contains("android.intent.action.ACC_OFF"))
        assertFalse(manifest.contains("android.intent.action.REBOOT"))
        assertFalse(manifest.contains("com.microntek.bootcheck"))
    }

    @Test
    fun `manifest no longer declares accessibility service`() {
        val manifest = readSource("src/main/AndroidManifest.xml")

        assertFalse(manifest.contains("MyAccessibilityService"))
        assertFalse(manifest.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertFalse(manifest.contains("android.accessibilityservice"))
        assertFalse(manifest.contains("@xml/accessibility_service_config"))
    }

    @Test
    fun `manifest allows cleartext traffic for current backend transport`() {
        val manifest = readSource("src/main/AndroidManifest.xml")

        assertTrue(manifest.contains("android:usesCleartextTraffic=\"true\""))
    }

    @Test
    fun `network module uses http backend base url until server supports https`() {
        val networkModule = readSource("src/main/java/com/example/carchatbot/app/di/NetworkModule.kt")

        assertTrue(networkModule.contains(".baseUrl(\"http://103.118.28.117/api/\")"))
        assertFalse(networkModule.contains(".baseUrl(\"https://103.118.28.117/api/\")"))
    }

    @Test
    fun `manifest uses unified launcher mipmaps and legacy icon assets are removed`() {
        val manifest = readSource("src/main/AndroidManifest.xml")

        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
        assertFalse(manifest.contains("android:icon=\"@drawable/logo\""))
        assertFalse(Paths.get("src/main/res/drawable/logo.jpg").exists())
        assertFalse(Paths.get("src/main/res/drawable/ic_launcher_foreground.xml").exists())
        assertFalse(Paths.get("src/main/res/mipmap-mdpi/ic_launcher.webp").exists())
        assertFalse(Paths.get("src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp").exists())
    }
}
