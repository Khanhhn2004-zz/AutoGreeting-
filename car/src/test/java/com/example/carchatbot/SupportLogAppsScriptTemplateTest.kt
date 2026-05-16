package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class SupportLogAppsScriptTemplateTest {

    private fun readFile(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `apps script template accepts post validates secret and writes drive file`() {
        val source = readFile("../docs/support-log-apps-script/Code.gs")

        assertTrue(source.contains("function doPost(e)"))
        assertTrue(source.contains("DriveApp.getFolderById"))
        assertTrue(source.contains("createFile"))
        assertTrue(source.contains("SUPPORT_LOG_SECRET"))
        assertTrue(source.contains("payload.secret !== SUPPORT_LOG_SECRET"))
        assertTrue(source.contains("reportId"))
        assertTrue(source.contains("ContentService.createTextOutput"))
    }
}
