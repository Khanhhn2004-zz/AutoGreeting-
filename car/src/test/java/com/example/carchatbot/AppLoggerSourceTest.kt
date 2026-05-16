package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class AppLoggerSourceTest {

    @Test
    fun `app logger persists crash logs synchronously`() {
        val source = String(
            Files.readAllBytes(
                Paths.get("src/main/java/com/example/carchatbot/utils/AppLogger.kt")
            ),
            UTF_8
        )

        assertTrue(
            source.contains("addLog(LogType.CRASH, tag, message, extra, persistSynchronously = true)")
        )
    }

    @Test
    fun `addLog does not refresh pending logs by snapshotting repository after every append`() {
        val source = String(
            Files.readAllBytes(
                Paths.get("src/main/java/com/example/carchatbot/utils/AppLogger.kt")
            ),
            UTF_8
        )
        val addLogBody = source
            .substringAfter("private fun addLog(")
            .substringBefore("fun peekPendingLogs")

        assertFalse(addLogBody.contains("startupDiagnosticsRepository.snapshot()"))
    }

    @Test
    fun `app logger triggers opportunistic diagnostics cleanup in background`() {
        val source = String(
            Files.readAllBytes(
                Paths.get("src/main/java/com/example/carchatbot/utils/AppLogger.kt")
            ),
            UTF_8
        )

        assertTrue(source.contains("scope.launch"))
        assertTrue(source.contains("startupDiagnosticsRepository.pruneExpiredIfDue()"))
    }
}
