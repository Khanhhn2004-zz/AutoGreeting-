package com.example.carchatbot

import com.example.carchatbot.boot.StartupDiagnosticsRepository
import com.example.carchatbot.data.remote.model.AppLogRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class StartupDiagnosticsRepositoryDefaultRetentionTest {

    @Test
    fun `default retention preserves a longer timeline for diagnostics export`() {
        val storageDir = Files.createTempDirectory("startup-diagnostics-default-retention").toFile()
        val repository = StartupDiagnosticsRepository(storageDir = storageDir)

        repeat(480) { index ->
            repository.append(
                AppLogRequest(
                    type = "BOOT",
                    tag = "BootReceiver",
                    message = "entry-$index",
                    createdAt = index.toLong()
                )
            )
        }

        val recreated = StartupDiagnosticsRepository(storageDir = storageDir)
        val snapshot = recreated.snapshot()

        assertEquals(480, snapshot.size)
        assertEquals("entry-0", snapshot.first().message)
        assertEquals("entry-479", snapshot.last().message)
    }
}
