package com.example.carchatbot.ui.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.carchatbot.runtime.AppRuntimePolicies
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionScreen(
    onPermissionsGranted: () -> Unit
) {
    val context = LocalContext.current

    val notificationPermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        null
    }

    var hasOverlayPermission by remember {
        mutableStateOf(AppRuntimePolicies.canDrawOverlaysCompat(context))
    }
    var overlayRefreshAttempt by remember { mutableStateOf(0) }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermission = AppRuntimePolicies.canDrawOverlaysCompat(context)
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = AppRuntimePolicies.canDrawOverlaysCompat(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isNotificationGranted = notificationPermissionState?.status?.isGranted ?: true

    LaunchedEffect(overlayRefreshAttempt) {
        if (overlayRefreshAttempt == 0 || hasOverlayPermission) {
            return@LaunchedEffect
        }

        repeat(12) {
            delay(250)
            val refreshed = AppRuntimePolicies.canDrawOverlaysCompat(context)
            if (refreshed != hasOverlayPermission) {
                hasOverlayPermission = refreshed
            }
            if (refreshed) {
                return@LaunchedEffect
            }
        }
    }

    LaunchedEffect(isNotificationGranted) {
        if (isNotificationGranted) {
            onPermissionsGranted()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Required Permissions",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        if (!isNotificationGranted && notificationPermissionState != null) {
            Text(
                text = "Ung dung can quyen thong bao de hien thi foreground service.",
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Button(
                onClick = { notificationPermissionState.launchPermissionRequest() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text("Grant Notification Permission")
            }
        }

        if (!hasOverlayPermission) {
            Text(
                text = "Ung dung can quyen hien thi tren cac ung dung khac de mo phim noi.",
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Button(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    overlayRefreshAttempt += 1
                    overlayPermissionLauncher.launch(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text("Grant Overlay Permission")
            }
        }

        if (isNotificationGranted) {
            Text("Tat ca quyen bat buoc da duoc cap.")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Tu khoi dong",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Neu nhac khong phat khi khoi dong, hay bat tu khoi dong tai day.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedButton(
            onClick = {
                com.example.carchatbot.utils.AutoStartHelper.getAutoStartPermission(context)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text("Bat tu chay khi khoi dong")
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }

        if (!isIgnoringBatteryOptimizations && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Text(
                text = "Cau hinh pin",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Tat toi uu hoa pin de ung dung ben hon khi chay nen.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text("Tat toi uu hoa pin")
            }
        }

    }
}
