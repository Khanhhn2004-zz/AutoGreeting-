package com.example.carchatbot

import com.example.carchatbot.boot.BootReceiverProbeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files

class BootReceiverProbeStoreTest {

    @Test
    fun `probe store persists latest raw boot receiver evidence`() {
        val storageDir = Files.createTempDirectory("boot-receiver-probe").toFile()
        val store = BootReceiverProbeStore(storageDir)

        store.record(
            action = "android.intent.action.BOOT_COMPLETED",
            receivedAtMillis = 1_771_000_000_000L,
            elapsedRealtimeMillis = 45_000L,
            processId = 1234,
            threadName = "main"
        )

        val latest = BootReceiverProbeStore(storageDir).readLatest()

        assertNotNull(latest)
        assertEquals("android.intent.action.BOOT_COMPLETED", latest?.action)
        assertEquals(1_771_000_000_000L, latest?.receivedAtMillis)
        assertEquals(45_000L, latest?.elapsedRealtimeMillis)
        assertEquals(1234, latest?.processId)
        assertEquals("main", latest?.threadName)
    }
}
