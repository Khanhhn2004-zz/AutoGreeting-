package com.example.carchatbot

import com.example.carchatbot.boot.StartupCompatibilityProfile
import com.example.carchatbot.boot.StartupCompatibilityTrigger
import com.example.carchatbot.runtime.AppRuntimePolicies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupCompatibilityProfileTest {

    @Test
    fun `cold boot profile stays receiver first with the short startup window`() {
        val profile = StartupCompatibilityProfile.COLD_BOOT_ONLY

        assertTrue(profile.supports(StartupCompatibilityTrigger.DIRECT_BOOT_RECEIVER))
        assertTrue(profile.supports(StartupCompatibilityTrigger.POST_UNLOCK_RECEIVER))
        assertFalse(profile.supports(StartupCompatibilityTrigger.PROCESS_VISIBLE))
        assertFalse(profile.supports(StartupCompatibilityTrigger.HOST_VISIBLE_ATTACH))
        assertEquals(AppRuntimePolicies.bootStartupWindowMillis(), profile.startupWindowMillis)
        assertTrue(profile.attemptsDirectBootExecution)
        assertFalse(profile.requiresVisibleExecutionSignal)
    }

    @Test
    fun `sleep wake profile extends compatibility to process visible recovery`() {
        val profile = StartupCompatibilityProfile.HEAD_UNIT_SLEEP_WAKE

        assertTrue(profile.supports(StartupCompatibilityTrigger.DIRECT_BOOT_RECEIVER))
        assertTrue(profile.supports(StartupCompatibilityTrigger.POST_UNLOCK_RECEIVER))
        assertTrue(profile.supports(StartupCompatibilityTrigger.PROCESS_VISIBLE))
        assertFalse(profile.supports(StartupCompatibilityTrigger.HOST_VISIBLE_ATTACH))
        assertEquals(
            AppRuntimePolicies.headUnitSleepWakeStartupWindowMillis(),
            profile.startupWindowMillis
        )
        assertTrue(profile.attemptsDirectBootExecution)
        assertFalse(profile.requiresVisibleExecutionSignal)
    }

    @Test
    fun `usb host attach profile waits for a visible route before execution`() {
        val profile = StartupCompatibilityProfile.USB_BOX_HOST_ATTACH

        assertFalse(profile.supports(StartupCompatibilityTrigger.DIRECT_BOOT_RECEIVER))
        assertFalse(profile.supports(StartupCompatibilityTrigger.POST_UNLOCK_RECEIVER))
        assertTrue(profile.supports(StartupCompatibilityTrigger.PROCESS_VISIBLE))
        assertTrue(profile.supports(StartupCompatibilityTrigger.HOST_VISIBLE_ATTACH))
        assertEquals(
            AppRuntimePolicies.usbHostAttachStartupWindowMillis(),
            profile.startupWindowMillis
        )
        assertFalse(profile.attemptsDirectBootExecution)
        assertTrue(profile.requiresVisibleExecutionSignal)
    }

    @Test
    fun `unknown generic profile is conservative and remains the default`() {
        val profile = StartupCompatibilityProfile.DEFAULT

        assertEquals(StartupCompatibilityProfile.UNKNOWN_GENERIC, profile)
        assertTrue(profile.supports(StartupCompatibilityTrigger.DIRECT_BOOT_RECEIVER))
        assertTrue(profile.supports(StartupCompatibilityTrigger.POST_UNLOCK_RECEIVER))
        assertTrue(profile.supports(StartupCompatibilityTrigger.PROCESS_VISIBLE))
        assertFalse(profile.supports(StartupCompatibilityTrigger.HOST_VISIBLE_ATTACH))
        assertEquals(
            AppRuntimePolicies.genericCompatibilityStartupWindowMillis(),
            profile.startupWindowMillis
        )
        assertFalse(profile.attemptsDirectBootExecution)
        assertTrue(profile.requiresVisibleExecutionSignal)
    }

    @Test
    fun `profile windows widen for delayed Android box routes`() {
        assertTrue(
            StartupCompatibilityProfile.HEAD_UNIT_SLEEP_WAKE.startupWindowMillis >
                StartupCompatibilityProfile.COLD_BOOT_ONLY.startupWindowMillis
        )
        assertTrue(
            StartupCompatibilityProfile.USB_BOX_HOST_ATTACH.startupWindowMillis >=
                StartupCompatibilityProfile.HEAD_UNIT_SLEEP_WAKE.startupWindowMillis
        )
    }
}
