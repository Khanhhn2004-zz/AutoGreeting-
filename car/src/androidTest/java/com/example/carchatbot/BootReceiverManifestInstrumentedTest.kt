package com.example.carchatbot

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootReceiverManifestInstrumentedTest {

    @Test
    fun approved_boot_actions_resolve_to_boot_receiver_at_runtime() {
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName

        assertEquals(
            listOf("com.example.carchatbot.service.BootCompletedReceiver"),
            resolvedReceiverNames(Intent.ACTION_BOOT_COMPLETED, packageName)
        )
        assertEquals(
            listOf("com.example.carchatbot.service.BootCompletedReceiver"),
            resolvedReceiverNames(Intent.ACTION_LOCKED_BOOT_COMPLETED, packageName)
        )
    }

    @Test
    fun debug_and_charger_like_actions_do_not_resolve_to_boot_receiver_at_runtime() {
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val blockedActions = listOf(
            "com.example.carchatbot.DEBUG_ACC_ON",
            "com.example.carchatbot.DEBUG_ACC_OFF",
            "android.intent.action.ACTION_POWER_CONNECTED",
            "android.intent.action.ACTION_POWER_DISCONNECTED",
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.QUICKBOOT_POWEROFF",
            "android.intent.action.ACC_ON",
            "android.intent.action.ACC_OFF",
            "android.intent.action.REBOOT",
            "com.microntek.bootcheck"
        )

        blockedActions.forEach { action ->
            assertTrue(
                "Expected no boot receiver resolution for $action",
                resolvedReceiverNames(action, packageName).isEmpty()
            )
        }
    }

    private fun resolvedReceiverNames(action: String, packageName: String): List<String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(action).setPackage(packageName)

        return context.packageManager
            .queryBroadcastReceivers(intent, 0)
            .mapNotNull { it.activityInfo?.name }
            .sorted()
    }
}
