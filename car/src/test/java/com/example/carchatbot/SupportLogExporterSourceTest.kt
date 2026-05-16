package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class SupportLogExporterSourceTest {

    private fun readSource(relativePath: String): String {
        val path = Paths.get(relativePath)
        return if (Files.exists(path)) {
            String(Files.readAllBytes(path), UTF_8)
        } else {
            ""
        }
    }

    @Test
    fun `support log exporter writes one diagnostics report directly to the selected document uri`() {
        val source = readSource("src/main/java/com/example/carchatbot/support/SupportLogExporter.kt")

        assertTrue(source.contains("suggestReportFileName()"))
        assertTrue(source.contains("chao-xe-diagnostics-"))
        assertTrue(source.contains("suspend fun exportToUri(destination: Uri)"))
        assertTrue(source.contains("openOutputStream(destination, \"w\")"))
        assertTrue(source.contains("appLogger.log(\"SupportLogExporter\", \"Support log export write started\""))
        assertTrue(source.contains("appLogger.log(\"SupportLogExporter\", \"Support log export write completed\""))
        assertTrue(source.contains("appLogger.logException(\"SupportLogExporter\", error, \"Support log export write failed\")"))
        assertTrue(source.contains("write(report)"))
        assertFalse(source.contains("openOutputStream(destination, \"wt\")"))
        assertFalse(source.contains("startup-diagnostics.json"))
        assertFalse(source.contains("boot-playback-state.txt"))
        assertFalse(source.contains("device-info.json"))
        assertFalse(source.contains("support-summary.txt"))
    }

    @Test
    fun `support log exporter also preserves the google drive upload share flow`() {
        val source = readSource("src/main/java/com/example/carchatbot/support/SupportLogExporter.kt")

        assertTrue(source.contains("suspend fun buildShareIntent(): Intent"))
        assertTrue(source.contains("Intent(Intent.ACTION_SEND)"))
        assertTrue(source.contains("FileProvider.getUriForFile("))
        assertTrue(source.contains("ClipData.newUri("))
        assertTrue(source.contains("putExtra(Intent.EXTRA_STREAM, attachment)"))
        assertTrue(source.contains("grantUriPermission("))
        assertTrue(source.contains("context.filesDir"))
        assertFalse(source.contains("Intent.EXTRA_EMAIL"))
    }

    @Test
    fun `support log exporter includes device state startup triggers and build metadata in the summary`() {
        val source = readSource("src/main/java/com/example/carchatbot/support/SupportLogExporter.kt")

        assertTrue(source.contains("DeviceUtils.getDeviceInfo("))
        assertTrue(source.contains("BuildMetadata.displayVersion()"))
        assertTrue(source.contains("BuildMetadata.displayPublishedAt()"))
        assertTrue(source.contains("userPreferencesRepository.startupMode.first()"))
        assertTrue(source.contains("userPreferencesRepository.floatingButtonEnabled.first()"))
        assertTrue(source.contains("userPreferencesRepository.isLoggedIn.first()"))
        assertTrue(source.contains("userPreferencesRepository.isRemoteSyncBlocked.first()"))
        assertTrue(source.contains("diagnosticsReportRenderer.render("))
        assertTrue(source.contains("DiagnosticsReportInput("))
    }
}
