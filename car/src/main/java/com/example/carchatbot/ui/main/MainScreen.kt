package com.example.carchatbot.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.carchatbot.R
import com.example.carchatbot.boot.BootSoundCacheRepository
import com.example.carchatbot.boot.StartupMode
import com.example.carchatbot.data.model.SoundSourceType
import com.example.carchatbot.runtime.AppRuntimePolicies
import com.example.carchatbot.runtime.BuildMetadata
import com.example.carchatbot.service.CoreService
import com.example.carchatbot.service.PlaybackRequestIntent
import com.example.carchatbot.service.SoundPlayerService
import com.example.carchatbot.service.SoundPlayerState
import com.example.carchatbot.support.SupportLogUploadResult
import java.lang.Exception
import kotlinx.coroutines.launch

object DarkThemeColors {
    val background = Color(0xFF0D1117)
    val surface = Color(0xFF161B22)
    val surfaceCard = Color(0xFF21262D)
    val primary = Color(0xFF4CAF50)
    val textPrimary = Color(0xFFE6EDF3)
    val textSecondary = Color(0xFF8B949E)
    val border = Color(0xFF30363D)
    val borderLight = Color(0xFF3D444D)
    val gold = Color(0xFFFFB000)
    val error = Color(0xFFEF5350)
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val soundDisplayName1 by viewModel.soundLabel1.collectAsState(initial = null)
    val soundDisplayName2 by viewModel.soundLabel2.collectAsState(initial = null)
    val soundSourceType1 by viewModel.soundSourceType1.collectAsState(initial = SoundSourceType.NONE)
    val soundSourceType2 by viewModel.soundSourceType2.collectAsState(initial = SoundSourceType.NONE)
    val iotStatus by viewModel.iotStatus.collectAsState()
    val isRemoteSyncBlocked by viewModel.isRemoteSyncBlocked.collectAsState(initial = false)
    val activeRequest by SoundPlayerState.activeRequest.collectAsState()
    val isPlaying by SoundPlayerState.isPlaying.collectAsState()
    val currentSoundIndex by SoundPlayerState.currentSoundIndex.collectAsState()
    val playingRequest by SoundPlayerState.playingRequest.collectAsState()
    val startupPlaybackSoundIndex by SoundPlayerState.startupPlaybackSoundIndex.collectAsState()
    val bootPlaybackState by viewModel.bootPlaybackState.collectAsState()
    val startupMode by viewModel.startupMode.collectAsState(initial = StartupMode.DEFAULT)
    val startupCacheUiState by viewModel.startupCacheUiState.collectAsState()
    val isLoggedInState by viewModel.isLoggedIn.collectAsState(initial = null)
    val demoExpirationTime by viewModel.demoExpirationTime.collectAsState(initial = null)
    val isFloatingButtonEnabled by viewModel.floatingButtonEnabled.collectAsState(initial = false)
    val startupPlaybackActiveSoundIndex =
        startupPlaybackSoundIndex ?: if (bootPlaybackState.isActive()) {
            BootSoundCacheRepository.STARTUP_SOUND_INDEX
        } else {
            null
        }
    val isHelloPreviewActive =
        startupPlaybackActiveSoundIndex == 1 ||
            activeRequest?.soundIndex == 1 ||
            playingRequest?.soundIndex == 1 ||
            (isPlaying && currentSoundIndex == 1)
    val isGoodbyePreviewActive =
        startupPlaybackActiveSoundIndex == 2 ||
            activeRequest?.soundIndex == 2 ||
            playingRequest?.soundIndex == 2 ||
            (isPlaying && currentSoundIndex == 2)
    val isAutoPlayAllowed = if (isLoggedInState != null) {
        isLoggedInState == true || (demoExpirationTime != null && System.currentTimeMillis() < demoExpirationTime!!)
    } else {
        true
    }
    val hasOverlayPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    val isDemoSessionActive =
        isLoggedInState == true && demoExpirationTime?.let { System.currentTimeMillis() < it } == true
    val sessionExpiredMessage = "Phiên hết hạn, hãy đăng nhập lại"
    val playbackUnavailableMessage = "Nhạc chưa sẵn sàng hoặc mạng không ổn định. Hãy thử tải lại nhạc từ server."
    var soundToImport by remember { mutableIntStateOf(1) }
    val coroutineScope = rememberCoroutineScope()
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.e("MainScreen", "Failed to take persistable URI permission", e)
                Toast.makeText(context, "Không thể cấp quyền cho tệp đã chọn.", Toast.LENGTH_SHORT).show()
            }
            viewModel.importLocalSound(soundToImport, it)
        }
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(context)) {
                viewModel.setFloatingButtonEnabled(true)
            } else {
                Toast.makeText(context, "Bạn chưa cấp quyền hiển thị trên ứng dụng khác.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(isAutoPlayAllowed, startupMode, isLoggedInState) {
        if (isLoggedInState != null && startupMode != StartupMode.OFF && !isAutoPlayAllowed) {
            viewModel.setStartupMode(StartupMode.OFF)
            stopPlayback(context, 1)
            Toast.makeText(context, "Tự động phát đã bị tắt do hết thời gian dùng thử", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(isLoggedInState, isDemoSessionActive) {
        if (isLoggedInState == true && isDemoSessionActive) {
            viewModel.prepareDemoStartupSoundCache()
        } else if (isLoggedInState == true) {
            viewModel.refreshStartupCacheStatus()
        }
    }

    LaunchedEffect(isFloatingButtonEnabled) {
        if (isLoggedInState == true) {
            requestCoreServiceRuntimeReconcile(context)
        }
    }

    DisposableEffect(context) {
        val soundStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    CoreService.ACTION_SOUND_DOWNLOADED -> {
                        viewModel.refreshStartupCacheStatus()
                    }

                    CoreService.ACTION_SOUND_FAILED -> {
                        when (intent.getStringExtra(CoreService.EXTRA_SOUND_FAILURE_REASON)) {
                            CoreService.SOUND_FAILURE_REASON_SESSION_EXPIRED -> {
                                Toast.makeText(context, sessionExpiredMessage, Toast.LENGTH_LONG).show()
                            }

                            CoreService.SOUND_FAILURE_REASON_PLAYBACK_UNAVAILABLE -> {
                                Toast.makeText(context, playbackUnavailableMessage, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }

        androidx.core.content.ContextCompat.registerReceiver(
            context,
            soundStatusReceiver,
            IntentFilter().apply {
                addAction(CoreService.ACTION_SOUND_DOWNLOADED)
                addAction(CoreService.ACTION_SOUND_FAILED)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            runCatching {
                context.unregisterReceiver(soundStatusReceiver)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        DarkThemeColors.background,
                        Color(0xFF0A0E13)
                    )
                )
            )
    ) {
        when (iotStatus) {
            null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = DarkThemeColors.primary,
                        strokeWidth = 3.dp
                    )
                }
            }

            true -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    PremiumHeader()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 24.dp)
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))

                        PremiumSectionCard(
                            title = "V\u1eadn h\u00e0nh",
                            subtitle = "Thi\u1ebft l\u1eadp h\u00e0nh vi ch\u1ea1y h\u1eb1ng ng\u00e0y c\u1ee7a \u1ee9ng d\u1ee5ng"
                        ) {
                            CompactStartupModeSelector(
                                title = "T\u1ef1 \u0111\u1ed9ng ph\u00e1t",
                                subtitle = if (isAutoPlayAllowed) {
                                    "T\u1ef1 \u0111\u1ed9ng ph\u00e1t \u00e2m thanh khi m\u1edf \u1ee9ng d\u1ee5ng ho\u1eb7c khi thi\u1ebft b\u1ecb kh\u1edfi \u0111\u1ed9ng"
                                } else {
                                    "C\u1ea7n \u0111\u0103ng nh\u1eadp ho\u1eb7c d\u00f9ng th\u1eed \u0111\u1ec3 b\u1eadt"
                                },
                                selectedMode = startupMode,
                                isSelectionAllowed = isAutoPlayAllowed,
                                onModeSelected = { selectedMode ->
                                    if (selectedMode != StartupMode.OFF && !isAutoPlayAllowed) {
                                        Toast.makeText(context, "Vui l\u00f2ng \u0111\u0103ng nh\u1eadp ho\u1eb7c b\u1eaft \u0111\u1ea7u d\u00f9ng th\u1eed \u0111\u1ec3 b\u1eadt t\u00ednh n\u0103ng n\u00e0y", Toast.LENGTH_LONG).show()
                                        return@CompactStartupModeSelector
                                    }
                                    viewModel.setStartupMode(selectedMode)
                                    if (selectedMode == StartupMode.OFF) {
                                        requestKeepScreenVisible(context)
                                        stopPlayback(context, currentSoundIndex)
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            CompactSettingToggleRow(
                                title = "Ph\u00edm n\u1ed5i",
                                subtitle = when {
                                    isFloatingButtonEnabled -> "T\u1eaft ph\u00edm n\u1ed5i \u0111\u1ec3 \u1ea9n n\u00fat ph\u00e1t nhanh tr\u00ean m\u00e0n h\u00ecnh"
                                    !hasOverlayPermission -> "B\u1eadt ph\u00edm n\u1ed5i sau khi c\u1ea5p quy\u1ec1n hi\u1ec3n th\u1ecb tr\u00ean \u1ee9ng d\u1ee5ng kh\u00e1c"
                                    else -> "B\u1eadt ph\u00edm n\u1ed5i \u0111\u1ec3 ph\u00e1t nhanh t\u1eeb m\u1ecdi \u1ee9ng d\u1ee5ng"
                                },
                                icon = Icons.Default.Info,
                                checked = isFloatingButtonEnabled,
                                onCheckedChange = { isEnabled ->
                                    if (isEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                        overlayPermissionLauncher.launch(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    } else {
                                        viewModel.setFloatingButtonEnabled(isEnabled)
                                    }
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        SoundControlSection(
                            title = "\u00c2m thanh xin ch\u00e0o",
                            currentSourceType = soundSourceType1,
                            currentSoundLabel = soundDisplayName1,
                            emptyStateLabel = "Ch\u01b0a c\u00f3 file \u00e2m thanh t\u1eeb server tr\u00ean thi\u1ebft b\u1ecb",
                            startupCacheStatusLabel = startupCacheUiState.message,
                            startupCacheReady = startupCacheUiState.ready,
                            previewButtonText = if (isHelloPreviewActive) {
                                "D\u1eebng th\u1eed"
                            } else {
                                "Nghe th\u1eed"
                            },
                            isPreviewActive = isHelloPreviewActive,
                            selectButtonText = if (soundSourceType1 == SoundSourceType.USER_LOCAL) {
                                "\u0110\u1ed5i file"
                            } else {
                                "Ch\u1ecdn file"
                            },
                            onPreviewClick = {
                                if (isHelloPreviewActive) {
                                    requestKeepScreenVisible(context)
                                    stopPlayback(context, 1)
                                } else {
                                    sendPlaybackRequest(context, 1, PlaybackRequestIntent.USER_START.name)
                                }
                            },
                            onSelectClick = {
                                soundToImport = 1
                                audioPickerLauncher.launch(arrayOf("audio/*"))
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        SoundControlSection(
                            title = "\u00c2m thanh t\u1ea1m bi\u1ec7t",
                            currentSourceType = soundSourceType2,
                            currentSoundLabel = soundDisplayName2,
                            emptyStateLabel = "Ch\u01b0a c\u00f3 file t\u1eeb server, s\u1ebd d\u00f9ng \u00e2m t\u1ea1m bi\u1ec7t m\u1eb7c \u0111\u1ecbnh",
                            previewButtonText = if (isGoodbyePreviewActive) {
                                "D\u1eebng th\u1eed"
                            } else {
                                "Nghe th\u1eed"
                            },
                            isPreviewActive = isGoodbyePreviewActive,
                            selectButtonText = if (soundSourceType2 == SoundSourceType.USER_LOCAL) {
                                "\u0110\u1ed5i file"
                            } else {
                                "Ch\u1ecdn file"
                            },
                            onPreviewClick = {
                                if (isGoodbyePreviewActive) {
                                    requestKeepScreenVisible(context)
                                    stopPlayback(context, 2)
                                } else {
                                    sendPlaybackRequest(context, 2, PlaybackRequestIntent.USER_START.name)
                                }
                            },
                            onSelectClick = {
                                soundToImport = 2
                                audioPickerLauncher.launch(arrayOf("audio/*"))
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        PremiumSectionCard(
                            title = "\u0110\u1ed3ng b\u1ed9 & H\u1ec7 th\u1ed1ng",
                            subtitle = "Theo d\u00f5i \u0111\u1ed3ng b\u1ed9 v\u00e0 thao t\u00e1c thi\u1ebft b\u1ecb"
                        ) {
                            PremiumStatusCard(
                                title = if (AppRuntimePolicies.shouldShowRemoteSyncWarning(isRemoteSyncBlocked)) {
                                    "C\u1ea7n \u0111\u0103ng nh\u1eadp l\u1ea1i \u0111\u1ec3 \u0111\u1ed3ng b\u1ed9"
                                } else {
                                    "Tr\u1ea1ng th\u00e1i \u0111\u1ed3ng b\u1ed9 b\u00ecnh th\u01b0\u1eddng"
                                },
                                message = if (AppRuntimePolicies.shouldShowRemoteSyncWarning(isRemoteSyncBlocked)) {
                                    "Thi\u1ebft b\u1ecb v\u1eabn d\u00f9ng \u0111\u01b0\u1ee3c v\u1edbi d\u1eef li\u1ec7u c\u1ee5c b\u1ed9, nh\u01b0ng t\u1ea3i nh\u1ea1c, heartbeat v\u00e0 c\u1eadp nh\u1eadt t\u1eeb server \u0111ang t\u1ea1m d\u1eebng."
                                } else {
                                    "Thi\u1ebft b\u1ecb \u0111ang d\u00f9ng d\u1eef li\u1ec7u c\u1ee5c b\u1ed9 v\u00e0 s\u1eb5n s\u00e0ng \u0111\u1ed3ng b\u1ed9 v\u1edbi server."
                                },
                                color = if (AppRuntimePolicies.shouldShowRemoteSyncWarning(isRemoteSyncBlocked)) {
                                    DarkThemeColors.gold
                                } else {
                                    DarkThemeColors.primary
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            CompactActionButton(
                                text = "T\u1ea3i l\u1ea1i",
                                icon = Icons.Default.Refresh,
                                onClick = {
                                    if (isDemoSessionActive) {
                                        Toast.makeText(
                                            context,
                                            "\u0110ang chu\u1ea9n b\u1ecb \u00e2m thanh d\u00f9ng th\u1eed cho kh\u1edfi \u0111\u1ed9ng...",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        viewModel.prepareDemoStartupSoundCache()
                                    } else if (isRemoteSyncBlocked) {
                                        Toast.makeText(context, sessionExpiredMessage, Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "\u0110ang t\u1ea3i l\u1ea1i nh\u1ea1c t\u1eeb server v\u00e0 x\u00f3a nh\u1ea1c \u0111\u00e3 ch\u1ecdn...",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        val intent = Intent(context, CoreService::class.java).apply {
                                            action = CoreService.ACTION_START_SOUND_DOWNLOAD
                                        }
                                        androidx.core.content.ContextCompat.startForegroundService(context, intent)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            CompactActionButton(
                                text = "G\u1eedi b\u00e1o c\u00e1o h\u1ed7 tr\u1ee3",
                                icon = Icons.Default.Info,
                                onClick = {
                                    coroutineScope.launch {
                                        when (val result = viewModel.uploadSupportLog()) {
                                            is SupportLogUploadResult.Success -> {
                                                Toast.makeText(
                                                    context,
                                                    "\u0110\u00e3 g\u1eedi log h\u1ed7 tr\u1ee3: ${result.reportId}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }

                                            SupportLogUploadResult.NotConfigured -> {
                                                Toast.makeText(
                                                    context,
                                                    "Ch\u01b0a c\u1ea5u h\u00ecnh Apps Script nh\u1eadn log.",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }

                                            is SupportLogUploadResult.Failure -> {
                                                Toast.makeText(
                                                    context,
                                                    "Kh\u00f4ng g\u1eedi \u0111\u01b0\u1ee3c log: ${result.message}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                isPrimary = false
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            PremiumStatusCard(
                                title = "Th\u00f4ng tin b\u1ea3n build",
                                message = "Phi\u00ean b\u1ea3n: ${BuildMetadata.displayVersion()}\nNg\u00e0y build: ${BuildMetadata.displayPublishedAt()}",
                                color = DarkThemeColors.borderLight
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            DangerActionButton(
                                text = "\u0110\u0103ng xu\u1ea5t",
                                icon = Icons.Default.Close,
                                onClick = { viewModel.logout() }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            false -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = DarkThemeColors.error,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Ứng dụng đã bị quản trị viên vô hiệu hóa.",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = DarkThemeColors.error,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumSectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        color = DarkThemeColors.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = DarkThemeColors.borderLight.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = DarkThemeColors.textPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = DarkThemeColors.textSecondary
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SoundControlSection(
    title: String,
    currentSourceType: SoundSourceType,
    currentSoundLabel: String?,
    emptyStateLabel: String,
    startupCacheStatusLabel: String? = null,
    startupCacheReady: Boolean = true,
    previewButtonText: String,
    isPreviewActive: Boolean,
    selectButtonText: String,
    onPreviewClick: () -> Unit,
    onSelectClick: () -> Unit
) {
    PremiumSectionCard(
        title = title,
        subtitle = null
    ) {
        SoundStatusStrip(
            label = sourceStatusLabel(
                sourceType = currentSourceType,
                currentSoundLabel = currentSoundLabel,
                emptyStateLabel = emptyStateLabel
            )
        )

        if (startupCacheStatusLabel != null) {
            Spacer(modifier = Modifier.height(8.dp))
            SoundStatusStrip(
                label = startupCacheStatusLabel,
                indicatorColor = if (startupCacheReady) {
                    DarkThemeColors.primary
                } else {
                    DarkThemeColors.gold
                },
                borderColor = if (startupCacheReady) {
                    DarkThemeColors.borderLight.copy(alpha = 0.45f)
                } else {
                    DarkThemeColors.gold.copy(alpha = 0.32f)
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        CompactSoundActionRow(
            primaryLabel = previewButtonText,
            primaryIcon = if (isPreviewActive) Icons.Default.Close else Icons.Default.PlayArrow,
            onPrimaryClick = onPreviewClick,
            secondaryLabel = selectButtonText,
            secondaryIcon = Icons.Default.Info,
            onSecondaryClick = onSelectClick
        )
    }
}

@Composable
private fun PremiumHeader() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = DarkThemeColors.surface,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Text(
                text = stringResource(R.string.main_screen_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = DarkThemeColors.textPrimary,
                letterSpacing = (-0.5).sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Điều khiển âm thanh chào nhanh gọn hơn",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = DarkThemeColors.textSecondary
            )
        }
    }
}

@Composable
private fun PremiumStatusCard(
    title: String,
    message: String,
    color: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = DarkThemeColors.surfaceCard,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = color.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(14.dp)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                fontSize = 12.sp,
                color = DarkThemeColors.textSecondary
            )
        }
    }
}

@Composable
private fun CompactStartupModeSelector(
    title: String,
    subtitle: String,
    selectedMode: StartupMode,
    isSelectionAllowed: Boolean,
    onModeSelected: (StartupMode) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = DarkThemeColors.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = DarkThemeColors.textPrimary
            )
        }
        StartupModeChipRow(
            selectedMode = selectedMode,
            isSelectionAllowed = isSelectionAllowed,
            onModeSelected = onModeSelected
        )

        StartupModeSelectionSummary(
            label = selectedModeLabel(selectedMode),
            description = if (isSelectionAllowed || selectedMode == StartupMode.OFF) {
                selectedModeDescription(selectedMode)
            } else {
                subtitle
            },
            isSelectionAllowed = isSelectionAllowed || selectedMode == StartupMode.OFF
        )

        if (false) {
            LegacyStartupModeCard(
            title = "Tắt tự phát",
            subtitle = "Không tự phát",
            selected = selectedMode == StartupMode.OFF,
            enabled = true,
            isRecommended = false,
            onClick = { onModeSelected(StartupMode.OFF) }
        )
        LegacyStartupModeCard(
            title = "Khi thi\u1ebft b\u1ecb kh\u1edfi \u0111\u1ed9ng",
            subtitle = "Gần nhất với MacroDroid",
            selected = selectedMode == StartupMode.BOOT_COMPLETED,
            enabled = isSelectionAllowed,
            isRecommended = true,
            onClick = { onModeSelected(StartupMode.BOOT_COMPLETED) }
        )
        LegacyStartupModeCard(
            title = "Khi m\u1edf \u1ee9ng d\u1ee5ng",
            subtitle = "Chỉ khi app tự mở",
            selected = selectedMode == StartupMode.APP_AUTO_OPEN,
            enabled = isSelectionAllowed,
            isRecommended = false,
            onClick = { onModeSelected(StartupMode.APP_AUTO_OPEN) }
        )
        }
    }
}

@Composable
private fun LegacyStartupModeCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    isRecommended: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = selected,
                enabled = enabled,
                onValueChange = { onClick() },
                role = Role.Switch
            ),
        shape = RoundedCornerShape(16.dp),
        color = DarkThemeColors.surfaceCard,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = if (selected) DarkThemeColors.primary.copy(alpha = 0.45f) else DarkThemeColors.borderLight.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (enabled) DarkThemeColors.textPrimary else DarkThemeColors.textSecondary
                    )
                    if (isRecommended) {
                        LegacyRecommendationBadge()
                    }
                }
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = DarkThemeColors.textSecondary
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = DarkThemeColors.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun LegacyRecommendationBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(DarkThemeColors.primary.copy(alpha = 0.14f))
            .border(
                width = 1.dp,
                color = DarkThemeColors.primary.copy(alpha = 0.25f),
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = "Khuyến nghị",
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = DarkThemeColors.primary
        )
    }
}

@Composable
private fun StartupModeChipRow(
    selectedMode: StartupMode,
    isSelectionAllowed: Boolean,
    onModeSelected: (StartupMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StartupModeChip(
            modifier = Modifier.weight(1f),
            title = "T\u1eaft",
            selected = selectedMode == StartupMode.OFF,
            enabled = true,
            onClick = { onModeSelected(StartupMode.OFF) }
        )
        StartupModeChip(
            modifier = Modifier.weight(1f),
            title = "Kh\u1edfi \u0111\u1ed9ng",
            selected = selectedMode == StartupMode.BOOT_COMPLETED,
            enabled = isSelectionAllowed,
            onClick = { onModeSelected(StartupMode.BOOT_COMPLETED) }
        )
        StartupModeChip(
            modifier = Modifier.weight(1f),
            title = "M\u1edf app",
            selected = selectedMode == StartupMode.APP_AUTO_OPEN,
            enabled = isSelectionAllowed,
            onClick = { onModeSelected(StartupMode.APP_AUTO_OPEN) }
        )
    }
}

@Composable
private fun StartupModeChip(
    modifier: Modifier = Modifier,
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .toggleable(
                value = selected,
                enabled = enabled,
                onValueChange = { onClick() },
                role = Role.RadioButton
            )
            .clip(RoundedCornerShape(999.dp)),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) DarkThemeColors.primary.copy(alpha = 0.16f) else DarkThemeColors.surfaceCard,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = when {
                        selected -> DarkThemeColors.primary.copy(alpha = 0.42f)
                        enabled -> DarkThemeColors.borderLight.copy(alpha = 0.55f)
                        else -> DarkThemeColors.border.copy(alpha = 0.65f)
                    },
                    shape = RoundedCornerShape(999.dp)
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    selected -> DarkThemeColors.primary
                    enabled -> DarkThemeColors.textPrimary
                    else -> DarkThemeColors.textSecondary
                }
            )
        }
    }
}

@Composable
private fun StartupModeSelectionSummary(
    label: String,
    description: String,
    isSelectionAllowed: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    color = if (isSelectionAllowed) DarkThemeColors.primary else DarkThemeColors.borderLight,
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label: $description",
            fontSize = 12.sp,
            color = DarkThemeColors.textSecondary
        )
    }
}

private fun selectedModeLabel(mode: StartupMode): String =
    when (mode) {
        StartupMode.OFF -> "T\u1eaft"
        StartupMode.BOOT_COMPLETED -> "Kh\u1edfi \u0111\u1ed9ng"
        StartupMode.APP_AUTO_OPEN -> "M\u1edf app"
    }

private fun selectedModeDescription(mode: StartupMode): String =
    when (mode) {
        StartupMode.OFF -> "Kh\u00f4ng t\u1ef1 ph\u00e1t \u00e2m thanh."
        StartupMode.BOOT_COMPLETED -> "T\u1ef1 ph\u00e1t khi thi\u1ebft b\u1ecb kh\u1edfi \u0111\u1ed9ng."
        StartupMode.APP_AUTO_OPEN -> "T\u1ef1 ph\u00e1t khi \u1ee9ng d\u1ee5ng t\u1ef1 m\u1edf."
    }

@Composable
private fun SoundStatusStrip(
    label: String,
    indicatorColor: Color = DarkThemeColors.primary,
    borderColor: Color = DarkThemeColors.borderLight.copy(alpha = 0.45f)
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = DarkThemeColors.surfaceCard,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(indicatorColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                color = DarkThemeColors.textSecondary
            )
        }
    }
}

@Composable
private fun CompactSoundActionRow(
    primaryLabel: String,
    primaryIcon: ImageVector,
    onPrimaryClick: () -> Unit,
    secondaryLabel: String,
    secondaryIcon: ImageVector,
    onSecondaryClick: () -> Unit
) {
    CompactSplitActionRow(
        primaryLabel = primaryLabel,
        primaryIcon = primaryIcon,
        onPrimaryClick = onPrimaryClick,
        secondaryLabel = secondaryLabel,
        secondaryIcon = secondaryIcon,
        onSecondaryClick = onSecondaryClick,
        secondaryIsPrimary = true
    )
}

@Composable
private fun CompactSplitActionRow(
    primaryLabel: String,
    primaryIcon: ImageVector,
    onPrimaryClick: () -> Unit,
    secondaryLabel: String,
    secondaryIcon: ImageVector,
    onSecondaryClick: () -> Unit,
    secondaryEnabled: Boolean = true,
    secondaryIsPrimary: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CompactActionButton(
            text = primaryLabel,
            icon = primaryIcon,
            onClick = onPrimaryClick,
            modifier = Modifier.weight(1f),
            isPrimary = false
        )
        CompactActionButton(
            text = secondaryLabel,
            icon = secondaryIcon,
            onClick = onSecondaryClick,
            modifier = Modifier.weight(1f),
            enabled = secondaryEnabled,
            isPrimary = secondaryIsPrimary
        )
    }
}

@Composable
private fun CompactActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPrimary: Boolean = false
) {
    if (isPrimary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (enabled) DarkThemeColors.primary else DarkThemeColors.surface,
                contentColor = if (enabled) Color.White else DarkThemeColors.textSecondary,
                disabledContainerColor = DarkThemeColors.surface,
                disabledContentColor = DarkThemeColors.textSecondary
            ),
            shape = RoundedCornerShape(14.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                disabledElevation = 0.dp
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = DarkThemeColors.textPrimary,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = DarkThemeColors.textSecondary
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.2.dp,
                if (enabled) DarkThemeColors.borderLight else DarkThemeColors.border
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DangerActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = DarkThemeColors.error.copy(alpha = 0.08f),
            contentColor = DarkThemeColors.error
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.2.dp,
            DarkThemeColors.error.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CompactSettingToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch
            ),
        shape = RoundedCornerShape(16.dp),
        color = DarkThemeColors.surfaceCard,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = DarkThemeColors.borderLight.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (checked) DarkThemeColors.primary.copy(alpha = 0.18f)
                        else DarkThemeColors.surface
                    )
                    .border(
                        width = 1.dp,
                        color = if (checked) DarkThemeColors.primary.copy(alpha = 0.3f) else DarkThemeColors.border,
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked) DarkThemeColors.primary else DarkThemeColors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkThemeColors.textPrimary,
                    letterSpacing = (-0.2).sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = DarkThemeColors.textSecondary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = DarkThemeColors.primary,
                    checkedBorderColor = DarkThemeColors.primary,
                    uncheckedThumbColor = DarkThemeColors.textSecondary,
                    uncheckedTrackColor = DarkThemeColors.surface,
                    uncheckedBorderColor = DarkThemeColors.borderLight
                )
            )
        }
    }
}

@Composable
private fun PremiumActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isPrimary: Boolean = true
) {
    if (isPrimary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(
                    elevation = if (enabled) 8.dp else 0.dp,
                    shape = RoundedCornerShape(14.dp),
                    ambientColor = if (enabled) DarkThemeColors.primary.copy(alpha = 0.3f) else Color.Transparent,
                    spotColor = if (enabled) DarkThemeColors.primary.copy(alpha = 0.3f) else Color.Transparent
                ),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (enabled) DarkThemeColors.primary else DarkThemeColors.surface,
                contentColor = if (enabled) Color.White else DarkThemeColors.textSecondary,
                disabledContainerColor = DarkThemeColors.surface,
                disabledContentColor = DarkThemeColors.textSecondary
            ),
            shape = RoundedCornerShape(14.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                disabledElevation = 0.dp
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = DarkThemeColors.textPrimary,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = DarkThemeColors.textSecondary
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                if (enabled) DarkThemeColors.borderLight else DarkThemeColors.border
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun sourceStatusLabel(
    sourceType: SoundSourceType,
    currentSoundLabel: String?,
    emptyStateLabel: String
): String {
    return when (sourceType) {
        SoundSourceType.USER_LOCAL -> currentSoundLabel?.let { "Âm thanh tự chọn: $it" }
            ?: "Âm thanh tự chọn"
        SoundSourceType.SERVER_MANAGED -> currentSoundLabel?.let { "Âm thanh từ server: $it" }
            ?: "Âm thanh từ server"
        SoundSourceType.DEFAULT_GOODBYE -> "Âm thanh mặc định tạm biệt"
        SoundSourceType.NONE -> emptyStateLabel
    }
}

private fun sendPlaybackRequest(
    context: Context,
    soundIndex: Int,
    requestIntent: String
) {
    val intent = Intent(context, SoundPlayerService::class.java).apply {
        putExtra(SoundPlayerService.EXTRA_SOUND_URI_INDEX, soundIndex)
        putExtra(SoundPlayerService.EXTRA_PLAYBACK_REQUEST_INTENT, requestIntent)
    }
    context.startService(intent)
}

private fun stopPlayback(context: Context, soundIndex: Int?) {
    sendPlaybackRequest(
        context = context,
        soundIndex = soundIndex ?: 1,
        requestIntent = PlaybackRequestIntent.USER_STOP.name
    )
}

private fun requestKeepScreenVisible(context: Context) {
    context.sendBroadcast(
        Intent(MainActivity.ACTION_CANCEL_AUTO_RETURN_HOME).setPackage(context.packageName)
    )
}

private fun requestCoreServiceRuntimeReconcile(context: Context) {
    val intent = Intent(context, CoreService::class.java).apply {
        action = CoreService.ACTION_RECONCILE_RUNTIME_SERVICES
    }
    androidx.core.content.ContextCompat.startForegroundService(context, intent)
}

