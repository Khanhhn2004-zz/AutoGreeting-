package com.example.carchatbot.utils

import android.content.Context
import android.os.Build
import com.example.carchatbot.data.remote.model.DeviceInfoRequest
import com.example.carchatbot.data.remote.model.GpsLocation

object DeviceUtils {

    fun getDeviceInfo(context: Context, appVersion: String, gpsLocation: GpsLocation?): DeviceInfoRequest {
        val metrics = context.resources.displayMetrics
        val packageInfo = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }

        return DeviceInfoRequest(
            brand = Build.BRAND,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkVersion = Build.VERSION.SDK_INT,
            screenResolution = "${metrics.widthPixels}x${metrics.heightPixels}",
            dpi = metrics.densityDpi,
            batteryLevel = getBatteryLevel(context),
            isRooted = isRooted(),
            networkType = getNetworkType(context),
            internalIp = getIpAddress(),
            gpsLocation = gpsLocation,
            appVersion = appVersion,
            totalRam = getTotalRam(context),
            freeStorage = getFreeStorage(),
            cpuArch = getCpuArch(),
            timezone = java.util.TimeZone.getDefault().id,
            carrier = getCarrier(context),
            language = java.util.Locale.getDefault().toString(),
            appUpdateTime = packageInfo?.lastUpdateTime?.let { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(it)) },
            installTime = packageInfo?.firstInstallTime?.let { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(it)) },
            internetStatus = if (getNetworkType(context) != "UNKNOWN") "Online" else "Offline",
            overlay = android.provider.Settings.canDrawOverlays(context),
            notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            },
            batteryOptimization = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            },
            accessibility = false,
            location = false
        )
    }

    private fun getTotalRam(context: Context): String {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        return formatSize(memInfo.totalMem)
    }

    private fun getFreeStorage(): String {
        val path = android.os.Environment.getDataDirectory()
        val stat = android.os.StatFs(path.path)
        val blockSize = stat.blockSizeLong
        val availableBlocks = stat.availableBlocksLong
        return formatSize(availableBlocks * blockSize)
    }

    private fun formatSize(size: Long): String {
        val kb = size / 1024
        val mb = kb / 1024
        val gb = mb / 1024
        return if (gb > 0) "$gb GB" else if (mb > 0) "$mb MB" else "$kb KB"
    }

    private fun getCpuArch(): String {
        return Build.SUPPORTED_ABIS.joinToString(", ")
    }
    
    private fun getCarrier(context: Context): String {
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        return manager.networkOperatorName ?: "Unknown"
    }

    private fun getBatteryLevel(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isRooted(): Boolean {
        val buildTags = android.os.Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun getNetworkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo
        return when (activeNetwork?.type) {
            android.net.ConnectivityManager.TYPE_WIFI -> "WIFI"
            android.net.ConnectivityManager.TYPE_MOBILE -> "MOBILE"
            else -> "UNKNOWN"
        }
    }

    private fun getIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) { }
        return ""
    }
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = cm.activeNetworkInfo
        return activeNetwork != null && activeNetwork.isConnected
    }
}
