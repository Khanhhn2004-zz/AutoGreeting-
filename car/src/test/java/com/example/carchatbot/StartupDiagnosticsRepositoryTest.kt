package com.example.carchatbot

import com.example.carchatbot.boot.StartupDiagnosticsRepository
import com.example.carchatbot.data.remote.model.AppLogRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StartupDiagnosticsRepositoryTest {

    @Test
    fun `diagnostics survive repository recreation until acknowledged`() {
        val storageDir = Files.createTempDirectory("startup-diagnostics").toFile()
        val first = AppLogRequest(type = "BOOT", tag = "BootReceiver", message = "accepted", createdAt = 1L)
        val second = AppLogRequest(type = "BOOT", tag = "BootPlayback", message = "started", createdAt = 2L)

        val repository = StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 10)
        repository.append(first)
        repository.append(second)

        val recreated = StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 10)
        assertEquals(listOf(first, second), recreated.snapshot())

        recreated.acknowledge(listOf(first))

        val afterAck = StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 10)
        assertEquals(listOf(second), afterAck.snapshot())
    }

    @Test
    fun `snapshot observes entries appended by another repository instance`() {
        val storageDir = Files.createTempDirectory("startup-diagnostics-cross-instance").toFile()
        val writer = StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 10)
        val reader = StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 10)
        val first = AppLogRequest(type = "BOOT", tag = "BootReceiver", message = "accepted", createdAt = 1L)
        val second = AppLogRequest(type = "BOOT", tag = "BootPlayback", message = "started", createdAt = 2L)

        writer.append(first)
        assertEquals(listOf(first), reader.snapshot())

        writer.append(second)

        assertEquals(listOf(first, second), reader.snapshot())
    }

    @Test
    fun `diagnostics drop the oldest entries when ring buffer is exceeded`() {
        val storageDir = Files.createTempDirectory("startup-diagnostics-truncate").toFile()
        val first = AppLogRequest(type = "BOOT", tag = "BootReceiver", message = "first", createdAt = 1L)
        val second = AppLogRequest(type = "BOOT", tag = "BootReceiver", message = "second", createdAt = 2L)
        val third = AppLogRequest(type = "BOOT", tag = "BootReceiver", message = "third", createdAt = 3L)

        val repository = StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 2)
        repository.append(first)
        repository.append(second)
        repository.append(third)

        val recreated = StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 2)
        assertEquals(listOf(second, third), recreated.snapshot())
        assertTrue(recreated.snapshot().none { it == first })
    }

    @Test
    fun `diagnostics truncate oversized message and extra before persistence`() {
        val storageDir = Files.createTempDirectory("startup-diagnostics-bounds").toFile()
        val repository = StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 10)
        val longMessage = "m".repeat(2_000)
        val longExtra = "e".repeat(12_000)

        repository.append(
            AppLogRequest(
                type = "EXCEPTION",
                tag = "Test",
                message = longMessage,
                extra = longExtra,
                createdAt = 1L
            )
        )

        val stored = StartupDiagnosticsRepository(storageDir = storageDir, maxEntries = 10)
            .snapshot()
            .single()

        assertTrue(stored.message.length < longMessage.length)
        assertTrue(stored.extra!!.length < longExtra.length)
    }

    @Test
    fun `cleanup removes entries older than retention window`() {
        val storageDir = Files.createTempDirectory("startup-diagnostics-retention").toFile()
        val now = 10_000L
        val retentionMillis = 3_000L
        val repository = StartupDiagnosticsRepository(
            storageDir = storageDir,
            maxEntries = 10,
            retentionMillis = retentionMillis
        )

        repository.append(
            AppLogRequest(
                type = "BOOT",
                tag = "Old",
                message = "old",
                createdAt = now - retentionMillis - 1
            )
        )
        repository.append(
            AppLogRequest(
                type = "BOOT",
                tag = "New",
                message = "new",
                createdAt = now
            )
        )

        repository.pruneExpired(nowMillis = now)

        assertEquals(listOf("new"), repository.snapshot().map { it.message })
    }

    @Test
    fun `opportunistic cleanup does not run more than once per retention window`() {
        val storageDir = Files.createTempDirectory("startup-diagnostics-cleanup-cadence").toFile()
        val retentionMillis = 3_000L
        val repository = StartupDiagnosticsRepository(
            storageDir = storageDir,
            maxEntries = 10,
            retentionMillis = retentionMillis
        )

        repository.append(AppLogRequest(type = "BOOT", tag = "Old", message = "old", createdAt = 0L))

        assertTrue(repository.pruneExpiredIfDue(nowMillis = 4_000L))
        assertEquals(emptyList<String>(), repository.snapshot().map { it.message })

        repository.append(AppLogRequest(type = "BOOT", tag = "Old", message = "old-again", createdAt = 0L))

        assertEquals(false, repository.pruneExpiredIfDue(nowMillis = 5_000L))
        assertEquals(listOf("old-again"), repository.snapshot().map { it.message })

        assertTrue(repository.pruneExpiredIfDue(nowMillis = 7_001L))
        assertEquals(emptyList<String>(), repository.snapshot().map { it.message })
    }
}
