package com.example.carchatbot

import com.example.carchatbot.boot.StartupCompatibilityProfile
import com.example.carchatbot.boot.StartupExecutionGate
import com.example.carchatbot.boot.StartupExecutionStrategy
import com.example.carchatbot.boot.StartupSignalContract
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupExecutionGateTest {

    private val gate = StartupExecutionGate()

    @Test
    fun `api 34 cold boot receiver may execute startup immediately`() {
        val plan = gate.planExecution(
            signal = StartupSignalContract.receiverStartupSignal(startupWindowId = 42L),
            profile = StartupCompatibilityProfile.COLD_BOOT_ONLY,
            sdkInt = 34
        )

        assertEquals(StartupExecutionStrategy.START_FOREGROUND_SERVICE_NOW, plan.strategy)
    }

    @Test
    fun `api 35 receiver boot still attempts direct startup for head unit sleep wake profile`() {
        val plan = gate.planExecution(
            signal = StartupSignalContract.receiverStartupSignal(startupWindowId = 42L),
            profile = StartupCompatibilityProfile.HEAD_UNIT_SLEEP_WAKE,
            sdkInt = 35
        )

        assertEquals(StartupExecutionStrategy.START_FOREGROUND_SERVICE_NOW, plan.strategy)
    }

    @Test
    fun `process visible recovery executes immediately on newer android once startup was armed`() {
        val plan = gate.planExecution(
            signal = StartupSignalContract.processVisibleRecoverySignal(startupWindowId = 42L),
            profile = StartupCompatibilityProfile.HEAD_UNIT_SLEEP_WAKE,
            sdkInt = 36
        )

        assertEquals(StartupExecutionStrategy.START_FOREGROUND_SERVICE_NOW, plan.strategy)
    }

    @Test
    fun `explicit app auto open remains a direct execution path`() {
        val plan = gate.planExecution(
            signal = StartupSignalContract.appOpenExplicitSignal(),
            profile = StartupCompatibilityProfile.UNKNOWN_GENERIC,
            sdkInt = 36
        )

        assertEquals(StartupExecutionStrategy.START_FOREGROUND_SERVICE_NOW, plan.strategy)
    }
}
