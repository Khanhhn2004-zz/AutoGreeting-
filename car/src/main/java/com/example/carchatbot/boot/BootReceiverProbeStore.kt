package com.example.carchatbot.boot

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import javax.inject.Inject

data class BootReceiverProbe(
    val action: String,
    val receivedAtMillis: Long,
    val elapsedRealtimeMillis: Long,
    val processId: Int,
    val threadName: String
)

class BootReceiverProbeStore(private val storageDir: File) {

    @Inject
    constructor(bootStorageContextProvider: BootStorageContextProvider) :
        this(bootStorageContextProvider.startupStorageDir(STORAGE_DIRECTORY_NAME))

    private val stateFile = File(storageDir, STATE_FILE_NAME)
    private val lock = Any()

    fun record(
        action: String?,
        receivedAtMillis: Long = System.currentTimeMillis(),
        elapsedRealtimeMillis: Long = SystemClock.elapsedRealtime(),
        processId: Int = Process.myPid(),
        threadName: String = Thread.currentThread().name
    ) {
        synchronized(lock) {
            Files.createDirectories(storageDir.toPath())
            val properties = Properties().apply {
                setProperty("action", action ?: "null")
                setProperty("receivedAtMillis", receivedAtMillis.toString())
                setProperty("elapsedRealtimeMillis", elapsedRealtimeMillis.toString())
                setProperty("processId", processId.toString())
                setProperty("threadName", threadName)
            }
            val tempFile = Files.createTempFile(storageDir.toPath(), "boot_receiver_probe", ".tmp").toFile()
            FileOutputStream(tempFile).use { output ->
                properties.store(output, "boot receiver raw probe")
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    stateFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    stateFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
    }

    fun readLatest(): BootReceiverProbe? = synchronized(lock) {
        if (!stateFile.exists()) {
            return@synchronized null
        }
        runCatching {
            val properties = Properties()
            FileInputStream(stateFile).use { input ->
                properties.load(input)
            }
            BootReceiverProbe(
                action = properties.getProperty("action") ?: "unknown",
                receivedAtMillis = properties.getProperty("receivedAtMillis")?.toLongOrNull() ?: return@runCatching null,
                elapsedRealtimeMillis = properties.getProperty("elapsedRealtimeMillis")?.toLongOrNull() ?: return@runCatching null,
                processId = properties.getProperty("processId")?.toIntOrNull() ?: return@runCatching null,
                threadName = properties.getProperty("threadName") ?: "unknown"
            )
        }.getOrElse { error ->
            Log.w(TAG, "Failed to read boot receiver raw probe", error)
            null
        }
    }

    companion object {
        private const val TAG = "BootReceiverProbeStore"
        private const val STORAGE_DIRECTORY_NAME = "boot_receiver_probe"
        private const val STATE_FILE_NAME = "boot_receiver_probe.properties"

        fun recordRawReceive(context: Context, action: String?) {
            runCatching {
                BootReceiverProbeStore(rawStorageDir(context)).record(action)
            }.onFailure { error ->
                Log.w(TAG, "Failed to persist boot receiver raw probe", error)
            }
        }

        private fun rawStorageDir(context: Context): File {
            val appContext = context.applicationContext
            val deviceProtectedContext = appContext.createDeviceProtectedStorageContext()
            val startupContext = if (deviceProtectedContext.isDeviceProtectedStorage) {
                deviceProtectedContext
            } else {
                appContext
            }
            return File(startupContext.filesDir, STORAGE_DIRECTORY_NAME).apply { mkdirs() }
        }
    }
}
