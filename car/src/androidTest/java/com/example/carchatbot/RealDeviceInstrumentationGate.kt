package com.example.carchatbot

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

private val MANUAL_REAL_DEVICE_TEST_CLASSES = listOf(
    RealDeviceBootPreparationInstrumentedTest::class.java.name,
    RealDevicePostBootStateInstrumentedTest::class.java.name,
    RealDeviceSleepWakePreparationInstrumentedTest::class.java.name,
    RealDevicePostWakeRecoveryInstrumentedTest::class.java.name
)

fun assumeManualRealDeviceFlowEnabled() {
    val arguments = InstrumentationRegistry.getArguments()
    val classArgument = arguments.getString("class").orEmpty()
    val manualFlagEnabled = listOf(
        "manualRealDeviceFlow",
        "manual_real_device_flow",
        "realDeviceFlow"
    ).any { key ->
        arguments.getString(key).isTruthyArgument()
    }

    // These helpers are intended for direct adb/manual runs, not broad instrumentation suites.
    val explicitManualClassSelection = MANUAL_REAL_DEVICE_TEST_CLASSES.any { testClass ->
        classArgument.contains(testClass)
    }

    assumeTrue(
        "Skipping manual startup helper. Run it directly with " +
            "`adb shell am instrument -w -e class <test-class> ...` " +
            "or pass `-e manualRealDeviceFlow true`.",
        manualFlagEnabled || explicitManualClassSelection
    )
}

private fun String?.isTruthyArgument(): Boolean {
    return when (this?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        else -> false
    }
}
