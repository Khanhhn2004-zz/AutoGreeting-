package com.example.carchatbot.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log

object AutoStartHelper {

    fun getAutoStartPermission(context: Context) {
        val buildInfo = Build.BRAND.lowercase()
        val intent = Intent()

        try {
            when {
                buildInfo.equals("xiaomi", ignoreCase = true) -> {
                    intent.component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                }
                buildInfo.equals("oppo", ignoreCase = true) -> {
                    intent.component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                }
                buildInfo.equals("vivo", ignoreCase = true) -> {
                    intent.component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                }
                buildInfo.equals("letv", ignoreCase = true) -> {
                    intent.component = ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
                }
                buildInfo.equals("honor", ignoreCase = true) -> {
                    intent.component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                }
                buildInfo.equals("huawei", ignoreCase = true) -> {
                     intent.component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                }
                buildInfo.equals("asus", ignoreCase = true) -> {
                    intent.component = ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")
                }
                else -> {
                    intent.action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    intent.data = android.net.Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                    return
                }
            }

            val list: List<ResolveInfo> = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            if (list.isNotEmpty()) {
                context.startActivity(intent)
            } else {
                val fallbackIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                fallbackIntent.data = android.net.Uri.parse("package:${context.packageName}")
                context.startActivity(fallbackIntent)
            }

        } catch (e: Exception) {
            Log.e("AutoStartHelper", "Failed to launch AutoStart settings", e)
             val fallbackIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            fallbackIntent.data = android.net.Uri.parse("package:${context.packageName}")
            context.startActivity(fallbackIntent)
        }
    }
}
