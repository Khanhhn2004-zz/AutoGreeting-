package com.example.carchatbot

import android.app.Application
import android.os.Process
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.carchatbot.utils.AppLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltAndroidApp
class CarApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var appLogger: AppLogger

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                appLogger.logCrash("CarApplication", throwable)
            }

            previousHandler?.uncaughtException(thread, throwable) ?: run {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
