package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class RealDevicePostConditionSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `post boot real device test skips when reboot precondition is absent`() {
        val source = readSource(
            "src/androidTest/java/com/example/carchatbot/RealDevicePostBootStateInstrumentedTest.kt"
        )

        assertTrue(source.contains("assumeTrue("))
        assertTrue(source.contains("Skipping post-boot verification"))
    }

    @Test
    fun `post wake real device test skips when sleep wake precondition is absent`() {
        val source = readSource(
            "src/androidTest/java/com/example/carchatbot/RealDevicePostWakeRecoveryInstrumentedTest.kt"
        )

        assertTrue(source.contains("assumeTrue("))
        assertTrue(source.contains("Skipping post-wake verification"))
    }
}
