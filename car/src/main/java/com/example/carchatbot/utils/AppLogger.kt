package com.example.carchatbot.utils

import android.util.Log
import com.example.carchatbot.boot.StartupDiagnosticsRepository
import com.example.carchatbot.data.remote.model.AppLogRequest
import com.example.carchatbot.data.remote.model.LogType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLogger @Inject constructor(
    private val startupDiagnosticsRepository: StartupDiagnosticsRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val pendingLogs = MutableStateFlow(startupDiagnosticsRepository.snapshot())
    private val noisyLogLock = Any()
    private val noisyBootWindows = mutableMapOf<String, NoisyLogWindow>()
    
    companion object {
        private const val TAG = "AppLogger"
        private const val NOISY_LOG_WINDOW_MILLIS = 5_000L
    }

    init {
        scope.launch {
            if (startupDiagnosticsRepository.pruneExpiredIfDue()) {
                pendingLogs.update { startupDiagnosticsRepository.snapshot() }
            }
        }
    }
    
    fun logService(tag: String, message: String, extra: String? = null) {
        addLog(LogType.SERVICE, tag, message, extra)
    }
    
    fun logAudio(tag: String, message: String, extra: String? = null) {
        addLog(LogType.AUDIO, tag, message, extra)
    }
    
    fun logBoot(tag: String, message: String, extra: String? = null) {
        addLog(LogType.BOOT, tag, message, extra, persistSynchronously = true)
    }

    fun logBootNoisy(
        tag: String,
        message: String,
        extra: String? = null,
        key: String = "$tag:$message",
        windowMillis: Long = NOISY_LOG_WINDOW_MILLIS
    ) {
        val decision = nextNoisyLogDecision(
            key = key,
            extra = extra,
            nowMillis = System.currentTimeMillis(),
            windowMillis = windowMillis
        )
        if (decision.shouldLog) {
            addLog(LogType.BOOT, tag, message, decision.extra)
        }
    }
    
    fun logHeartbeat(message: String, extra: String? = null) {
        addLog(LogType.HEARTBEAT, "CoreService", message, extra)
    }
    
    fun logWorker(tag: String, message: String, extra: String? = null) {
        addLog(LogType.WORKER, tag, message, extra)
    }
    
    fun logWorkerError(tag: String, message: String, throwable: Throwable? = null) {
        val extra = throwable?.let {
            "${it.javaClass.simpleName}: ${it.message}\n${it.stackTraceToString()}"
        }
        addLog(LogType.WORKER_ERROR, tag, message, extra)
    }
    
    fun logCrash(tag: String, throwable: Throwable) {
        val message = "${throwable.javaClass.simpleName}: ${throwable.message}"
        val extra = throwable.stackTraceToString()
        addLog(LogType.CRASH, tag, message, extra, persistSynchronously = true)
    }
    
    fun logException(tag: String, throwable: Throwable, context: String? = null) {
        val message = context ?: throwable.message ?: "Exception occurred"
        val extra = "${throwable.javaClass.simpleName}\n${throwable.stackTraceToString()}"
        addLog(LogType.EXCEPTION, tag, message, extra)
    }
    
    fun log(tag: String, message: String, extra: String? = null) {
        addLog(LogType.LOG, tag, message, extra)
    }
    
    private fun addLog(
        type: LogType,
        tag: String,
        message: String,
        extra: String?,
        persistSynchronously: Boolean = false
    ) {
        val appendLog = {
            try {
                val logEntry = AppLogRequest(
                    type = type.value,
                    tag = tag,
                    message = message,
                    extra = extra,
                    createdAt = System.currentTimeMillis()
                )
                
                startupDiagnosticsRepository.append(logEntry)?.let { persistedLog ->
                    pendingLogs.update { current ->
                        (current + persistedLog).takeLast(StartupDiagnosticsRepository.MAX_ENTRIES)
                    }
                }
                
                Log.d(TAG, "[$type] $tag: $message")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding log", e)
            }
        }

        if (persistSynchronously) {
            appendLog()
            return
        }

        scope.launch {
            appendLog()
        }
    }
    
    fun peekPendingLogs(): List<AppLogRequest> {
        return startupDiagnosticsRepository.snapshot()
    }

    fun acknowledgePendingLogs(deliveredLogs: List<AppLogRequest>) {
        startupDiagnosticsRepository.acknowledge(deliveredLogs)
        pendingLogs.update { startupDiagnosticsRepository.snapshot() }
    }
    
    fun clearPendingLogs() {
        startupDiagnosticsRepository.clear()
        pendingLogs.update { startupDiagnosticsRepository.snapshot() }
    }

    private fun nextNoisyLogDecision(
        key: String,
        extra: String?,
        nowMillis: Long,
        windowMillis: Long
    ): NoisyLogDecision {
        return synchronized(noisyLogLock) {
            val current = noisyBootWindows[key]
            if (current == null || nowMillis - current.windowStartedAtMillis >= windowMillis) {
                val suppressedCount = current?.suppressedCount ?: 0
                noisyBootWindows[key] = NoisyLogWindow(windowStartedAtMillis = nowMillis)
                NoisyLogDecision(
                    shouldLog = true,
                    extra = appendSuppressedCount(extra, suppressedCount, windowMillis)
                )
            } else {
                current.suppressedCount += 1
                NoisyLogDecision(shouldLog = false, extra = null)
            }
        }
    }

    private fun appendSuppressedCount(
        extra: String?,
        suppressedCount: Int,
        windowMillis: Long
    ): String? {
        if (suppressedCount <= 0) {
            return extra
        }

        val summary = "suppressed_count=$suppressedCount windowMillis=$windowMillis"
        return listOfNotNull(extra, summary).joinToString(separator = " ")
    }

    private data class NoisyLogWindow(
        val windowStartedAtMillis: Long,
        var suppressedCount: Int = 0
    )

    private data class NoisyLogDecision(
        val shouldLog: Boolean,
        val extra: String?
    )
}
