package com.example.carchatbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class MainScreenSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `youtube shortcut ui and helper are removed from main screen`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertFalse(source.contains("YouTube"))
        assertFalse(source.contains("createYoutubeShortcut("))
        assertFalse(source.contains("youtubeLink"))
        assertFalse(source.contains("YtProxyActivity"))
    }

    @Test
    fun `floating button settings remain below startup mode and before sound controls`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertFalse(source.contains("Enable Assistive Touch"))
        assertFalse(source.contains("Disable Assistive Touch"))
        assertFalse(source.contains("Bluetooth disabled"))

        val autoPlayIndex = source.indexOf("viewModel.setStartupMode(")
        val floatingButtonIndex = source.indexOf("viewModel.setFloatingButtonEnabled(isEnabled)")
        val firstSoundControlIndex = source.indexOf("SoundControlSection(")

        assertTrue(autoPlayIndex >= 0)
        assertTrue(floatingButtonIndex > autoPlayIndex)
        assertTrue(firstSoundControlIndex > floatingButtonIndex)
    }

    @Test
    fun `main screen keeps operations then two sound sections then system actions`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        val operationsIndex = source.indexOf("viewModel.setStartupMode(")
        val firstSoundIndex = source.indexOf("SoundControlSection(")
        val secondSoundIndex = source.indexOf("SoundControlSection(", firstSoundIndex + 1)
        val systemIndex = source.indexOf("BuildMetadata.displayVersion()")

        assertTrue(operationsIndex >= 0)
        assertTrue(firstSoundIndex > operationsIndex)
        assertTrue(secondSoundIndex > firstSoundIndex)
        assertTrue(systemIndex > secondSoundIndex)
    }

    @Test
    fun `screen keeps switches for persistent settings and restores local sound selection`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("viewModel.startupMode.collectAsState"))
        assertTrue(source.contains("viewModel.setStartupMode("))
        assertTrue(source.contains("viewModel.setFloatingButtonEnabled(isEnabled)"))
        assertTrue(source.contains("currentSoundIndex == 1"))
        assertTrue(source.contains("currentSoundIndex == 2"))
        assertTrue(source.contains("ACTION_START_SOUND_DOWNLOAD"))
        assertTrue(source.contains("viewModel.logout()"))
        assertTrue(source.contains("ActivityResultContracts.OpenDocument()"))
        assertTrue(source.contains("viewModel.importLocalSound("))
    }

    @Test
    fun `main screen exposes startup mode choices through a compact chip row`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("import com.example.carchatbot.boot.StartupMode"))
        assertTrue(source.contains("StartupMode.BOOT_COMPLETED"))
        assertTrue(source.contains("StartupMode.APP_AUTO_OPEN"))
        assertTrue(source.contains("StartupMode.OFF"))
        assertTrue(source.contains("CompactStartupModeSelector("))
        assertTrue(source.contains("StartupModeChipRow("))
        assertTrue(source.contains("StartupModeSelectionSummary("))
        assertTrue(source.contains("selectedModeDescription("))
        assertFalse(source.contains("CompactModeOption("))
        assertFalse(source.contains("StartupMode.BOOT_WITH_APP_FALLBACK"))
        assertFalse(source.contains("viewModel.setPlayOnOpenEnabled("))
    }

    @Test
    fun `operations section uses a slim floating button settings row`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("CompactSettingToggleRow("))
        assertFalse(source.contains("PremiumToggleCard("))
    }

    @Test
    fun `startup mode selector keeps user facing labels instead of enum names`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("title = \"T\\u1eaft\""))
        assertTrue(source.contains("title = \"Kh\\u1edfi \\u0111\\u1ed9ng\""))
        assertTrue(source.contains("title = \"M\\u1edf app\""))
        assertFalse(source.contains("title = \"BOOT_COMPLETED\""))
        assertFalse(source.contains("title = \"APP_AUTO_OPEN\""))
    }

    @Test
    fun `startup mode selector renders one row of chips plus one selected summary line`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")
        val startupSelector = source.substring(source.indexOf("private fun CompactStartupModeSelector("))

        assertTrue(startupSelector.contains("StartupModeChipRow("))
        assertTrue(startupSelector.contains("StartupModeSelectionSummary("))
        assertTrue(startupSelector.contains("selectedModeLabel("))
        assertTrue(startupSelector.contains("selectedModeDescription("))
        assertFalse(startupSelector.contains("CompactModeOption("))
    }

    @Test
    fun `sound sections collapse into a status strip and one horizontal action row`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("SoundStatusStrip("))
        assertTrue(source.contains("CompactSoundActionRow("))
        assertTrue(source.contains("primaryLabel = previewButtonText"))
        assertTrue(source.contains("secondaryLabel = selectButtonText"))
    }

    @Test
    fun `hello sound section shows startup cache readiness separately from selected source`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("viewModel.startupCacheUiState.collectAsState()"))
        assertTrue(source.contains("startupCacheStatusLabel = startupCacheUiState.message"))
        assertTrue(source.contains("startupCacheReady = startupCacheUiState.ready"))
        assertTrue(source.contains("indicatorColor = if (startupCacheReady)"))
        assertTrue(source.contains("DarkThemeColors.gold"))
        assertTrue(source.contains("borderColor = if (startupCacheReady)"))
        assertTrue(source.contains("CoreService.ACTION_SOUND_DOWNLOADED"))
        assertTrue(source.contains("viewModel.refreshStartupCacheStatus()"))
    }

    @Test
    fun `reload action seeds bundled startup cache instead of server download during demo`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("val isDemoSessionActive"))
        assertTrue(source.contains("if (isDemoSessionActive)"))
        assertTrue(source.contains("viewModel.prepareDemoStartupSoundCache()"))
        assertTrue(source.contains("LaunchedEffect(isLoggedInState, isDemoSessionActive)"))
        assertTrue(source.contains("\\u0110ang chu\\u1ea9n b\\u1ecb \\u00e2m thanh d\\u00f9ng th\\u1eed cho kh\\u1edfi \\u0111\\u1ed9ng"))
        assertTrue(source.contains("CoreService.ACTION_START_SOUND_DOWNLOAD"))
    }

    @Test
    fun `sound action labels stay short enough for narrow cards`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("\"Nghe th\\u1eed\""))
        assertTrue(source.contains("\"Ch\\u1ecdn file\""))
        assertTrue(source.contains("\"\\u0110\\u1ed5i file\""))
        assertFalse(source.contains("\"Nghe thử âm thanh xin chào\""))
        assertFalse(source.contains("\"Nghe thử âm thanh tạm biệt\""))
    }

    @Test
    fun `system section groups utility actions and keeps logout as a dedicated danger action`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("CompactActionButton("))
        assertTrue(source.contains("text = \"T\\u1ea3i l\\u1ea1i\""))
        assertTrue(source.contains("DangerActionButton("))
        assertTrue(source.contains("viewModel.logout()"))
    }

    @Test
    fun `system section keeps only support upload action`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("viewModel.uploadSupportLog()"))
        assertTrue(source.contains("SupportLogUploadResult.Success"))
        assertTrue(source.contains("SupportLogUploadResult.NotConfigured"))
        assertTrue(source.contains("Gửi báo cáo hỗ trợ") || source.contains("G\\u1eedi b\\u00e1o c\\u00e1o h\\u1ed7 tr\\u1ee3"))
        assertFalse(source.contains("\"Xu\\u1ea5t log\""))
        assertFalse(source.contains("\"Xuất log\""))
        assertFalse(source.contains("\"L\\u01b0u file\""))
        assertFalse(source.contains("\"Lưu file\""))
        assertFalse(source.contains("ActivityResultContracts.CreateDocument(\"text/plain\")"))
        assertFalse(source.contains("saveLogLauncher.launch(viewModel.supportLogExportFileName())"))
        assertFalse(source.contains("viewModel.exportSupportLogToUri(uri)"))
        assertFalse(source.contains("viewModel.buildSupportLogShareIntent()"))
        assertFalse(source.contains("Intent.createChooser("))
        assertFalse(source.contains("clipData = shareIntent.clipData"))
        assertFalse(source.contains("addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)"))
    }

    @Test
    fun `system section keeps breathing room between support action and build metadata`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        val supportActionIndex = source.indexOf("text = \"G\\u1eedi b\\u00e1o c\\u00e1o h\\u1ed7 tr\\u1ee3\"")
        val buildInfoIndex = source.indexOf("title = \"Th\\u00f4ng tin b\\u1ea3n build\"")
        val spacerAfterSupportIndex = source.indexOf(
            "Spacer(modifier = Modifier.height(12.dp))",
            supportActionIndex
        )

        assertTrue(supportActionIndex >= 0)
        assertTrue(buildInfoIndex > supportActionIndex)
        assertTrue(spacerAfterSupportIndex in (supportActionIndex + 1) until buildInfoIndex)
    }


    @Test
    fun `system section uses compact copy for subtitle and utility buttons`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("\"Theo d\\u00f5i \\u0111\\u1ed3ng b\\u1ed9 v\\u00e0 thao t\\u00e1c thi\\u1ebft b\\u1ecb\""))
        assertTrue(source.contains("text = \"T\\u1ea3i l\\u1ea1i\""))
        assertFalse(source.contains("\"Tr\\u1ee3 n\\u0103ng\""))
        assertFalse(source.contains("ACTION_ACCESSIBILITY_SETTINGS"))
        assertFalse(source.contains("isAccessibilityServiceEnabled("))
        assertFalse(source.contains("\"Tải lại nhạc từ server\""))
        assertFalse(source.contains("\"Bật trợ năng\""))
    }

    @Test
    fun `main screen no longer starts floating overlay directly while compose ui is foreground`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("viewModel.setFloatingButtonEnabled(isEnabled)"))
        assertTrue(source.contains("requestCoreServiceRuntimeReconcile(context)"))
        assertFalse(source.contains("context.stopService(Intent(context, FloatingButtonService::class.java))"))
        assertFalse(source.contains("delay(2_000)"))
        assertFalse(source.contains("context.startService(Intent(context, FloatingButtonService::class.java))"))
    }

    @Test
    fun `main screen describes local and server sound readiness from stored metadata`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("SoundSourceType.USER_LOCAL"))
        assertTrue(source.contains("SoundSourceType.SERVER_MANAGED"))
        assertTrue(source.contains("SoundSourceType.DEFAULT_GOODBYE"))
        assertTrue(source.contains("sourceStatusLabel("))
    }

    @Test
    fun `operations controls allow tapping the whole row not just the switch thumb`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains(".toggleable("))
        assertTrue(source.contains("role = Role.Switch"))
        assertTrue(source.contains("onCheckedChange = null"))
    }

    @Test
    fun `explicit stop on main screen cancels auto return home before stopping playback`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("requestKeepScreenVisible(context)"))
        assertTrue(source.contains("sendPlaybackRequest("))
        assertTrue(source.contains("PlaybackRequestIntent.USER_STOP.name"))
        assertTrue(source.contains("MainActivity.ACTION_CANCEL_AUTO_RETURN_HOME"))
    }

    @Test
    fun `main screen preview start uses the playback request intent contract`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("PlaybackRequestIntent.USER_START.name"))
        assertTrue(source.contains("SoundPlayerService.EXTRA_PLAYBACK_REQUEST_INTENT"))
    }

    @Test
    fun `main screen preview controls follow queued playback state during slot handoff`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("SoundPlayerState.activeRequest.collectAsState"))
        assertTrue(source.contains("SoundPlayerState.playingRequest.collectAsState"))
        assertTrue(source.contains("activeRequest?.soundIndex == 1"))
        assertTrue(source.contains("activeRequest?.soundIndex == 2"))
    }

    @Test
    fun `main screen preview controls follow startup autoplay playback state`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("SoundPlayerState.startupPlaybackSoundIndex.collectAsState"))
        assertTrue(source.contains("viewModel.bootPlaybackState.collectAsState"))
        assertTrue(source.contains("bootPlaybackState.isActive()"))
        assertTrue(source.contains("BootSoundCacheRepository.STARTUP_SOUND_INDEX"))
        assertTrue(source.contains("startupPlaybackActiveSoundIndex == 1"))
        assertTrue(source.contains("startupPlaybackActiveSoundIndex == 2"))
    }

    @Test
    fun `main screen preview icon follows playback state instead of localized button text`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("isPreviewActive: Boolean"))
        assertTrue(source.contains("primaryIcon = if (isPreviewActive) Icons.Default.Close else Icons.Default.PlayArrow"))
        assertFalse(source.contains("previewButtonText.startsWith"))
    }

    @Test
    fun `reload copy explains local selections are cleared before server download`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(
            source.contains("xÃƒÆ’Ã‚Â³a nhÃƒÂ¡Ã‚ÂºÃ‚Â¡c Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â£ chÃƒÂ¡Ã‚Â»Ã‚Ân") ||
                source.contains("xÃ³a nháº¡c Ä‘Ã£ chá»n") ||
                source.contains("x\\u00f3a nh\\u1ea1c \\u0111\\u00e3 ch\\u1ecdn")
        )
        assertTrue(source.contains("ACTION_START_SOUND_DOWNLOAD"))
    }

    @Test
    fun `main screen shows explicit toasts for session expiry and generic playback unavailability`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("CoreService.SOUND_FAILURE_REASON_SESSION_EXPIRED"))
        assertTrue(source.contains("CoreService.SOUND_FAILURE_REASON_PLAYBACK_UNAVAILABLE"))
        assertTrue(source.contains("sessionExpiredMessage"))
    }

    @Test
    fun `main screen keeps visible copy in Vietnamese with diacritics for compact actions`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("T\u1eaft"))
        assertTrue(source.contains("\u1ee9ng d\u1ee5ng") || source.contains("M\u1edf app"))
        assertTrue(
            source.contains("\u0110\u0103ng xu\u1ea5t") ||
                source.contains("\\u0110\\u0103ng xu\\u1ea5t")
        )
        assertFalse(source.contains("\"Dang xuat\""))
        assertFalse(source.contains("\"Tat tu phat\""))
    }

    @Test
    fun `system section shows build metadata`() {
        val source = readSource("src/main/java/com/example/carchatbot/ui/main/MainScreen.kt")

        assertTrue(source.contains("BuildMetadata.displayVersion()"))
        assertTrue(source.contains("BuildMetadata.displayPublishedAt()"))
        assertTrue(source.contains("message = \""))
    }

    @Test
    fun `manifest and source no longer reference youtube proxy activity`() {
        val manifest = readSource("src/main/AndroidManifest.xml")
        val proxyFile = Paths.get("src/main/java/com/example/carchatbot/ui/proxy/YtProxyActivity.kt")

        assertFalse(manifest.contains("YtProxyActivity"))
        assertFalse(Files.exists(proxyFile))
    }
}
