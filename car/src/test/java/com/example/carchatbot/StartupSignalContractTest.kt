package com.example.carchatbot

import com.example.carchatbot.boot.BootSignalOrigin
import com.example.carchatbot.boot.StartupSignalContract
import com.example.carchatbot.boot.StartupSignalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupSignalContractTest {

    @Test
    fun `receiver startup signal is primary and not recovery only`() {
        val signal = StartupSignalContract.receiverStartupSignal()

        assertEquals(BootSignalOrigin.RECEIVER, signal.origin)
        assertEquals(StartupSignalType.RECEIVER_BOOT, signal.type)
        assertFalse(signal.recoveryOnly)
    }

    @Test
    fun `process visible recovery signal stays recovery only and carries startup window identity`() {
        val signal = StartupSignalContract.processVisibleRecoverySignal(startupWindowId = 42L)

        assertEquals(BootSignalOrigin.RECEIVER, signal.origin)
        assertEquals(StartupSignalType.PROCESS_VISIBLE_RECOVERY, signal.type)
        assertTrue(signal.recoveryOnly)
        assertEquals(42L, signal.startupWindowIdOverride)
    }

    @Test
    fun `runtime reconcile recovery signal stays receiver owned and recovery only`() {
        val signal = StartupSignalContract.runtimeReconcileRecoverySignal(startupWindowId = 42L)

        assertEquals(BootSignalOrigin.RECEIVER, signal.origin)
        assertEquals(StartupSignalType.RUNTIME_RECONCILE_RECOVERY, signal.type)
        assertTrue(signal.recoveryOnly)
        assertEquals(42L, signal.startupWindowIdOverride)
    }

    @Test
    fun `explicit app auto open signal stays distinct from compatibility recovery`() {
        val signal = StartupSignalContract.appOpenExplicitSignal()

        assertEquals(BootSignalOrigin.APP_AUTO_START, signal.origin)
        assertEquals(StartupSignalType.APP_AUTO_OPEN_EXPLICIT, signal.type)
        assertFalse(signal.recoveryOnly)
    }

    @Test
    fun `process visible compat startup signal stays distinct from recovery and carries startup window identity`() {
        val signal = StartupSignalContract.processVisibleCompatStartupSignal(startupWindowId = 77L)

        assertEquals(BootSignalOrigin.BOOT_VISIBLE_RECOVERY, signal.origin)
        assertEquals(StartupSignalType.PROCESS_VISIBLE_COMPAT_STARTUP, signal.type)
        assertFalse(signal.recoveryOnly)
        assertEquals(77L, signal.startupWindowIdOverride)
    }
}
