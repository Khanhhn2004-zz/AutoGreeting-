package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class SupportLogAppsScriptUploaderSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `apps script uploader posts diagnostics report json with shared secret`() {
        val source = readSource("src/main/java/com/example/carchatbot/support/SupportLogAppsScriptUploader.kt")

        assertTrue(source.contains("class SupportLogAppsScriptUploader"))
        assertTrue(source.contains("BuildConfig.SUPPORT_LOG_APPS_SCRIPT_URL"))
        assertTrue(source.contains("BuildConfig.SUPPORT_LOG_APPS_SCRIPT_SECRET"))
        assertTrue(source.contains("SupportLogUploadResult.NotConfigured"))
        assertTrue(source.contains("supportLogExporter.buildReportText()"))
        assertTrue(source.contains("supportLogExporter.suggestReportFileName()"))
        assertTrue(source.contains("Request.Builder()"))
        assertTrue(source.contains(".post(requestBody)"))
        assertTrue(source.contains("uploadClient.newCall(currentRequest).execute()"))
        assertTrue(source.contains("SupportLogUploadResult.Success"))
        assertFalse(source.contains("Log.d(\"SupportLogAppsScriptUploader\", BuildConfig.SUPPORT_LOG_APPS_SCRIPT_SECRET"))
    }

    @Test
    fun `apps script upload models include report id and error result states`() {
        val source = readSource("src/main/java/com/example/carchatbot/support/SupportLogAppsScriptUploader.kt")

        assertTrue(source.contains("sealed interface SupportLogUploadResult"))
        assertTrue(source.contains("data class Success(val reportId: String)"))
        assertTrue(source.contains("data object NotConfigured"))
        assertTrue(source.contains("data class Failure(val message: String)"))
        assertTrue(source.contains("@Serializable"))
        assertTrue(source.contains("SupportLogAppsScriptRequest"))
        assertTrue(source.contains("SupportLogAppsScriptResponse"))
    }

    @Test
    fun `apps script uploader uses longer timeouts and retries transient upload failures`() {
        val source = readSource("src/main/java/com/example/carchatbot/support/SupportLogAppsScriptUploader.kt")

        assertTrue(source.contains("MAX_UPLOAD_ATTEMPTS"))
        assertTrue(source.contains("okHttpClient.newBuilder()"))
        assertTrue(source.contains("readTimeout(60, TimeUnit.SECONDS)"))
        assertTrue(source.contains("writeTimeout(60, TimeUnit.SECONDS)"))
        assertTrue(source.contains("callTimeout(90, TimeUnit.SECONDS)"))
        assertTrue(source.contains("retryOnConnectionFailure(true)"))
        assertTrue(source.contains("delay("))
        assertTrue(source.contains("attempt=\$attempt"))
    }

    @Test
    fun `apps script uploader has scoped dns fallback for google script hosts`() {
        val source = readSource("src/main/java/com/example/carchatbot/support/SupportLogAppsScriptUploader.kt")

        assertTrue(source.contains(".dns(GoogleAppsScriptFallbackDns)"))
        assertTrue(source.contains("object GoogleAppsScriptFallbackDns : Dns"))
        assertTrue(source.contains("\"script.google.com\""))
        assertTrue(source.contains("\"script.googleusercontent.com\""))
        assertTrue(source.contains("Dns.SYSTEM.lookup(hostname)"))
        assertTrue(source.contains("InetAddress.getByName"))
    }

    @Test
    fun `apps script uploader follows apps script redirects manually`() {
        val source = readSource("src/main/java/com/example/carchatbot/support/SupportLogAppsScriptUploader.kt")

        assertTrue(source.contains("followRedirects(false)"))
        assertTrue(source.contains("MAX_REDIRECTS"))
        assertTrue(source.contains("response.header(\"Location\")"))
        assertTrue(source.contains(".get()"))
        assertTrue(source.contains("Support log upload redirect followed"))
    }
}
