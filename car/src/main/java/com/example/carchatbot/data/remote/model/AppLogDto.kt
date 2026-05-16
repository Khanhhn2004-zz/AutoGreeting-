package com.example.carchatbot.data.remote.model

import kotlinx.serialization.Serializable

enum class LogType(val value: String) {
    LOG("LOG"),
    CRASH("CRASH"),
    WORKER("WORKER"),
    WORKER_ERROR("WORKER_ERROR"),
    SERVICE("SERVICE"),
    AUDIO("AUDIO"),
    BOOT("BOOT"),
    HEARTBEAT("HEARTBEAT"),
    EXCEPTION("EXCEPTION")
}

@Serializable
data class AppLogRequest(
    val type: String,
    val tag: String,
    val message: String,
    val extra: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class AppLogBatchRequest(
    val logs: List<AppLogRequest>
)

@Serializable
data class AppLogResponse(
    val success: Boolean,
    val message: String? = null,
    val receivedCount: Int? = null
)
