package com.example.carchatbot

import com.example.carchatbot.boot.BootSignalOrigin
import com.example.carchatbot.boot.StartupArmStateStore
import com.example.carchatbot.boot.StartupCompatibilityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class StartupArmStateStoreTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `empty arm state starts with no pending startup window`() {
        val storageDir = Files.createTempDirectory("startup-arm-state").toFile()

        val store = StartupArmStateStore(storageDir)
        val state = store.readState()

        assertNull(state.startupWindowId)
        assertEquals(StartupCompatibilityProfile.DEFAULT, state.compatibilityProfile)
        assertTrue(state.observedOrigins.isEmpty())
        assertFalse(state.isPending())
        assertNull(state.armedAtMillis)
        assertNull(state.consumedAtMillis)
    }

    @Test
    fun `arming startup window persists profile and first observed origin`() {
        val storageDir = Files.createTempDirectory("startup-arm-state").toFile()

        val store = StartupArmStateStore(storageDir)
        val armed = store.armStartupWindow(
            startupWindowId = 42L,
            origin = BootSignalOrigin.RECEIVER,
            compatibilityProfile = StartupCompatibilityProfile.COLD_BOOT_ONLY,
            nowMillis = 1_000L
        )
        val reloaded = StartupArmStateStore(storageDir).readState()

        assertEquals(42L, armed.startupWindowId)
        assertEquals(StartupCompatibilityProfile.COLD_BOOT_ONLY, armed.compatibilityProfile)
        assertEquals(setOf(BootSignalOrigin.RECEIVER), armed.observedOrigins)
        assertTrue(armed.isPending())
        assertEquals(1_000L, armed.armedAtMillis)
        assertEquals(armed, reloaded)
    }

    @Test
    fun `recording additional observed origin merges startup signals without consuming the window`() {
        val storageDir = Files.createTempDirectory("startup-arm-state").toFile()

        val store = StartupArmStateStore(storageDir)
        store.armStartupWindow(
            startupWindowId = 42L,
            origin = BootSignalOrigin.RECEIVER,
            compatibilityProfile = StartupCompatibilityProfile.HEAD_UNIT_SLEEP_WAKE,
            nowMillis = 1_000L
        )

        val merged = store.recordObservedOrigin(
            startupWindowId = 42L,
            origin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 2_000L
        )
        val reloaded = StartupArmStateStore(storageDir).readState()

        assertEquals(
            setOf(BootSignalOrigin.RECEIVER, BootSignalOrigin.APP_AUTO_START),
            merged.observedOrigins
        )
        assertTrue(merged.isPending())
        assertNull(merged.consumedAtMillis)
        assertEquals(merged, reloaded)
    }

    @Test
    fun `mark consumed closes pending startup window but preserves its origins`() {
        val storageDir = Files.createTempDirectory("startup-arm-state").toFile()

        val store = StartupArmStateStore(storageDir)
        store.armStartupWindow(
            startupWindowId = 42L,
            origin = BootSignalOrigin.RECEIVER,
            compatibilityProfile = StartupCompatibilityProfile.USB_BOX_HOST_ATTACH,
            nowMillis = 1_000L
        )
        store.recordObservedOrigin(
            startupWindowId = 42L,
            origin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 1_500L
        )

        val consumed = store.markConsumed(
            startupWindowId = 42L,
            nowMillis = 3_000L
        )
        val reloaded = StartupArmStateStore(storageDir).readState()

        assertFalse(consumed.isPending())
        assertEquals(42L, consumed.startupWindowId)
        assertEquals(StartupCompatibilityProfile.USB_BOX_HOST_ATTACH, consumed.compatibilityProfile)
        assertEquals(
            setOf(BootSignalOrigin.RECEIVER, BootSignalOrigin.APP_AUTO_START),
            consumed.observedOrigins
        )
        assertEquals(3_000L, consumed.consumedAtMillis)
        assertEquals(consumed, reloaded)
    }

    @Test
    fun `arm state store uses boot storage context provider instead of credential protected files dir`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/StartupArmStateStore.kt")

        assertTrue(source.contains("BootStorageContextProvider"))
        assertTrue(source.contains("startupStorageDir(STORAGE_DIRECTORY_NAME)"))
        assertFalse(source.contains("context.filesDir"))
    }
}
