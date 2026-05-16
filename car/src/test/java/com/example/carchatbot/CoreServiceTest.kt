package com.example.carchatbot

import com.example.carchatbot.service.CoreService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreServiceTest {

    @Test
    fun `floating button restart happens only when runtime wants the overlay and it is not already marked running`() {
        assertTrue(
            CoreService.shouldRestartFloatingButtonService(
                shouldStartFloatingButton = true,
                floatingButtonRunning = false
            )
        )
        assertFalse(
            CoreService.shouldRestartFloatingButtonService(
                shouldStartFloatingButton = true,
                floatingButtonRunning = true
            )
        )
        assertFalse(
            CoreService.shouldRestartFloatingButtonService(
                shouldStartFloatingButton = false,
                floatingButtonRunning = false
            )
        )
    }

    @Test
    fun `floating button stops only when runtime no longer wants overlay and it is still running`() {
        assertTrue(
            CoreService.shouldStopFloatingButtonService(
                shouldStartFloatingButton = false,
                floatingButtonRunning = true
            )
        )
        assertFalse(
            CoreService.shouldStopFloatingButtonService(
                shouldStartFloatingButton = true,
                floatingButtonRunning = true
            )
        )
        assertFalse(
            CoreService.shouldStopFloatingButtonService(
                shouldStartFloatingButton = true,
                floatingButtonRunning = false
            )
        )
    }

    @Test
    fun `generic power actions are ignored and do not become startup ownership signals`() {
        assertTrue(CoreService.shouldIgnoreExternalPowerAction("android.intent.action.ACTION_POWER_CONNECTED"))
        assertTrue(CoreService.shouldIgnoreExternalPowerAction("android.intent.action.ACTION_POWER_DISCONNECTED"))
        assertFalse(CoreService.shouldIgnoreExternalPowerAction(CoreService.ACTION_RECONCILE_RUNTIME_SERVICES))
        assertFalse(CoreService.shouldIgnoreExternalPowerAction(CoreService.ACTION_START_SOUND_DOWNLOAD))
        assertFalse(CoreService.shouldIgnoreExternalPowerAction(null))
    }
}
