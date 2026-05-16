package com.example.carchatbot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.carchatbot.boot.StartupDiagnosticsRepository
import com.example.carchatbot.data.remote.model.AppLogRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StartupDiagnosticsRepositoryInstrumentedTest {

    @Test
    fun diagnostics_survive_repository_recreation_on_android_filesystem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageDir = freshStorageDir(context.cacheDir, "startup-diagnostics-android")
        val first = AppLogRequest(type = "BOOT", tag = "BootReceiver", message = "accepted", createdAt = 1L)
        val second = AppLogRequest(type = "BOOT", tag = "BootPlayback", message = "started", createdAt = 2L)

        StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 10).apply {
            append(first)
            append(second)
        }

        val recreated = StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 10)
        assertEquals(listOf(first, second), recreated.snapshot())

        recreated.acknowledge(listOf(first))

        val afterAck = StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 10)
        assertEquals(listOf(second), afterAck.snapshot())
    }

    @Test
    fun diagnostics_drop_oldest_entries_when_android_ring_buffer_is_exceeded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageDir = freshStorageDir(context.cacheDir, "startup-diagnostics-android-truncate")
        val first = AppLogRequest(type = "BOOT", tag = "BootReceiver", message = "first", createdAt = 1L)
        val second = AppLogRequest(type = "BOOT", tag = "BootReceiver", message = "second", createdAt = 2L)
        val third = AppLogRequest(type = "BOOT", tag = "BootReceiver", message = "third", createdAt = 3L)

        StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 2).apply {
            append(first)
            append(second)
            append(third)
        }

        val recreated = StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 2)
        assertEquals(listOf(second, third), recreated.snapshot())
        assertTrue(recreated.snapshot().none { it == first })
    }

    private fun freshStorageDir(baseDir: File, name: String): File {
        return File(baseDir, name).apply {
            deleteRecursively()
            mkdirs()
        }
    }
}
