package com.example.carchatbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class CarApplicationSourceTest {

    @Test
    fun `application installs a crash logger before delegating to the previous uncaught exception handler`() {
        val source = String(
            Files.readAllBytes(
                Paths.get("src/main/java/com/example/carchatbot/app/CarApplication.kt")
            ),
            UTF_8
        )

        assertTrue(source.contains("lateinit var appLogger: AppLogger"))
        assertTrue(source.contains("override fun onCreate()"))
        assertTrue(source.contains("Thread.getDefaultUncaughtExceptionHandler()"))
        assertTrue(source.contains("Thread.setDefaultUncaughtExceptionHandler"))
        assertTrue(source.contains("appLogger.logCrash(\"CarApplication\", throwable)"))
        assertTrue(source.contains("previousHandler?.uncaughtException(thread, throwable)"))
    }
}
