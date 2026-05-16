package com.example.carchatbot

import android.content.Intent
import com.example.carchatbot.service.BootCompletedReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootCompletedReceiverTest {

    @Test
    fun `approved startup actions stay conservative`() {
        assertEquals(
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                "com.htc.intent.action.QUICKBOOT_POWERON"
            ),
            BootCompletedReceiver.approvedStartupActions()
        )
    }

    @Test
    fun `receiver rejects debug and charger-like actions`() {
        assertFalse(BootCompletedReceiver.isApprovedStartupAction("com.example.carchatbot.DEBUG_ACC_ON"))
        assertFalse(BootCompletedReceiver.isApprovedStartupAction("com.example.carchatbot.DEBUG_ACC_OFF"))
        assertFalse(BootCompletedReceiver.isApprovedStartupAction("android.intent.action.ACTION_POWER_CONNECTED"))
        assertFalse(BootCompletedReceiver.isApprovedStartupAction("android.intent.action.ACTION_POWER_DISCONNECTED"))
        assertFalse(BootCompletedReceiver.isApprovedStartupAction("android.intent.action.ACC_ON"))
        assertFalse(BootCompletedReceiver.isApprovedStartupAction("com.microntek.bootcheck"))
        assertFalse(BootCompletedReceiver.isApprovedStartupAction("com.example.carchatbot.CUSTOM_BOOT"))
    }

    @Test
    fun `receiver accepts canonical boot and macro-like quickboot actions`() {
        assertTrue(BootCompletedReceiver.isApprovedStartupAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(BootCompletedReceiver.isApprovedStartupAction(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertTrue(BootCompletedReceiver.isApprovedStartupAction("android.intent.action.QUICKBOOT_POWERON"))
        assertTrue(BootCompletedReceiver.isApprovedStartupAction("com.htc.intent.action.QUICKBOOT_POWERON"))
    }
}
