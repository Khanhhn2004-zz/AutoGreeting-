package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class BuildMetadataSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `gradle defines build metadata and apk naming`() {
        val gradle = readSource("build.gradle.kts")

        assertTrue(gradle.contains("buildConfigField(\"String\", \"APP_DISPLAY_NAME\""))
        assertTrue(gradle.contains("buildConfigField(\"String\", \"BUILD_PUBLISHED_AT\""))
        assertTrue(gradle.contains("buildConfigField(\"String\", \"BUILD_VERSION_LABEL\""))
        assertTrue(gradle.contains("buildConfigField(\"String\", \"SUPPORT_LOG_APPS_SCRIPT_URL\""))
        assertTrue(gradle.contains("buildConfigField(\"String\", \"SUPPORT_LOG_APPS_SCRIPT_SECRET\""))
        assertTrue(gradle.contains("supportLogAppsScriptUrl"))
        assertTrue(gradle.contains("supportLogAppsScriptSecret"))
        assertTrue(gradle.contains("local.properties"))
        assertTrue(gradle.contains("localProperty(\"supportLogAppsScriptUrl\")"))
        assertTrue(gradle.contains("localProperty(\"supportLogAppsScriptSecret\")"))
        assertTrue(gradle.contains("outputFileName"))
        assertTrue(gradle.contains("chao-xe"))
        assertTrue(gradle.contains("SimpleDateFormat(\"ddMMyy-HHmm\", Locale.US)"))
        assertTrue(gradle.contains("\"chao-xe-\$buildApkPublishedLabel.apk\""))
        assertFalse(gradle.contains("substringBefore(\" GMT+7\")"))
        assertFalse(gradle.contains("\$variantName-v\$appVersionName-\$appVersionCode"))
    }
}
