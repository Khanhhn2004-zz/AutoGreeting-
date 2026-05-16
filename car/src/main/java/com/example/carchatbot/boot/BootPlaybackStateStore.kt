package com.example.carchatbot.boot

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Các phase chính của một BOOT playback session.
 *
 * Phase này được ghi ra disk để receiver, service, recovery path và support log
 * cùng hiểu session đang ở trạng thái nào.
 */
enum class BootPlaybackPhase {
    IDLE,
    CLAIMED,
    STARTING,
    PLAYING,
    COMPLETED,
    MANUALLY_STOPPED,
    FAILED,
    NO_CACHE
}

/**
 * Ownership state bền vững cho một lần startup playback.
 *
 * Receiver, recovery path, foreground service, diagnostics và test đều đọc state
 * này để thống nhất rằng BOOT playback đang active, đã completed, bị manual
 * stop, failed hoặc bị chặn bởi cache readiness.
 */
data class BootPlaybackState(
    val startupWindowId: Long? = null,
    val sessionId: String? = null,
    val ownerOrigin: BootSignalOrigin? = null,
    val phase: BootPlaybackPhase = BootPlaybackPhase.IDLE,
    val lastProgressAt: Long? = null,
    val takeoverCount: Int = 0,
    val lastFailureReason: String? = null,
    val lastNotificationStartupWindowId: Long? = null
) {
    /**
     * Trả về `true` khi session vẫn cần được bảo vệ khỏi duplicate start.
     */
    fun isActive(): Boolean =
        phase == BootPlaybackPhase.CLAIMED ||
            phase == BootPlaybackPhase.STARTING ||
            phase == BootPlaybackPhase.PLAYING
}

/**
 * Store direct-boot-safe cho ownership của BOOT playback session.
 *
 * Việc lưu state này bên ngoài UI giúp nhánh BOOT hoạt động như một startup
 * engine: process death hoặc duplicate broadcast có thể được reconcile bằng
 * session đã lưu thay vì đoán dựa trên service hiện tại.
 */
class BootPlaybackStateStore(private val storageDir: File) {

    constructor(bootStorageContextProvider: BootStorageContextProvider) :
        this(bootStorageContextProvider.startupStorageDir(STORAGE_DIRECTORY_NAME))

    private val stateFile = File(storageDir, STATE_FILE_NAME)
    private val lock = Any()
    private val _stateFlow = MutableStateFlow(readStateFromDisk())

    val stateFlow: StateFlow<BootPlaybackState> = _stateFlow.asStateFlow()

    /**
     * Đọc state mới nhất từ disk và đồng bộ lại [stateFlow].
     */
    fun readState(): BootPlaybackState = synchronized(lock) {
        val state = readStateLocked()
        if (_stateFlow.value != state) {
            _stateFlow.value = state
        }
        state
    }

    /**
     * Claim ownership cho một startup window trước khi service được start.
     *
     * Đây là điểm bắt đầu chính thức của một BOOT playback session.
     */
    fun claimSession(
        startupWindowId: Long,
        sessionId: String,
        ownerOrigin: BootSignalOrigin,
        nowMillis: Long,
        takeoverCount: Int = 0
    ): BootPlaybackState = synchronized(lock) {
        val current = readStateLocked()
        val next = BootPlaybackState(
            startupWindowId = startupWindowId,
            sessionId = sessionId,
            ownerOrigin = ownerOrigin,
            phase = BootPlaybackPhase.CLAIMED,
            lastProgressAt = nowMillis,
            takeoverCount = takeoverCount,
            lastFailureReason = null,
            lastNotificationStartupWindowId = current.lastNotificationStartupWindowId
        )
        writeStateLocked(next)
        next
    }

    /**
     * Đánh dấu session đã bắt đầu chuẩn bị phát.
     */
    fun markStarting(sessionId: String, nowMillis: Long): BootPlaybackState =
        updateSessionState(sessionId, nowMillis, BootPlaybackPhase.STARTING)

    /**
     * Đánh dấu session đang phát audio.
     */
    fun markPlaying(sessionId: String, nowMillis: Long): BootPlaybackState =
        updateSessionState(sessionId, nowMillis, BootPlaybackPhase.PLAYING)

    /**
     * Đánh dấu session đã phát xong thành công.
     */
    fun markCompleted(sessionId: String, nowMillis: Long): BootPlaybackState =
        updateSessionState(sessionId, nowMillis, BootPlaybackPhase.COMPLETED)

    /**
     * Chỉ đánh dấu playing nếu callback vẫn thuộc session hiện tại.
     *
     * Guard này chống callback muộn từ player cũ ghi đè session mới.
     */
    fun markPlayingIfCurrent(sessionId: String, nowMillis: Long): Boolean =
        updateSessionStateIfCurrent(sessionId, nowMillis, BootPlaybackPhase.PLAYING)

    /**
     * Chỉ đánh dấu completed nếu callback vẫn thuộc session hiện tại.
     */
    fun markCompletedIfCurrent(sessionId: String, nowMillis: Long): Boolean =
        updateSessionStateIfCurrent(sessionId, nowMillis, BootPlaybackPhase.COMPLETED)

    /**
     * Chỉ đánh dấu failed nếu lỗi vẫn thuộc session hiện tại.
     */
    fun markFailedIfCurrent(
        sessionId: String,
        failureReason: String,
        nowMillis: Long
    ): Boolean = updateSessionStateIfCurrent(
        sessionId = sessionId,
        nowMillis = nowMillis,
        phase = BootPlaybackPhase.FAILED,
        failureReason = failureReason
    )

    /**
     * Ghi terminal state khi user hoặc manual playback chủ động dừng startup playback.
     */
    fun markManuallyStopped(
        sessionId: String,
        reason: String,
        nowMillis: Long
    ): BootPlaybackState = synchronized(lock) {
        val current = readStateLocked()
        require(current.sessionId == sessionId) {
            "Cannot update boot playback session $sessionId when current session is ${current.sessionId}"
        }
        val next = current.copy(
            phase = BootPlaybackPhase.MANUALLY_STOPPED,
            lastProgressAt = nowMillis,
            lastFailureReason = reason
        )
        writeStateLocked(next)
        next
    }

    /**
     * Ghi terminal state failed cho session hiện tại.
     */
    fun markFailed(
        sessionId: String,
        failureReason: String,
        nowMillis: Long
    ): BootPlaybackState = synchronized(lock) {
        val current = readStateLocked()
        require(current.sessionId == sessionId) {
            "Cannot update boot playback session $sessionId when current session is ${current.sessionId}"
        }
        val next = current.copy(
            phase = BootPlaybackPhase.FAILED,
            lastProgressAt = nowMillis,
            lastFailureReason = failureReason
        )
        writeStateLocked(next)
        next
    }

    /**
     * Ghi terminal state `NO_CACHE` khi BOOT cache không thể repair/retry được.
     */
    fun markNoCache(sessionId: String, nowMillis: Long): BootPlaybackState = synchronized(lock) {
        val current = readStateLocked()
        require(current.sessionId == sessionId) {
            "Cannot update boot playback session $sessionId when current session is ${current.sessionId}"
        }
        val next = current.copy(
            phase = BootPlaybackPhase.NO_CACHE,
            lastProgressAt = nowMillis,
            lastFailureReason = "no_cache"
        )
        writeStateLocked(next)
        next
    }

    /**
     * Ghi nhận startup window đã hiện no-cache notification để tránh spam notification.
     */
    fun recordNotification(
        startupWindowId: Long,
        nowMillis: Long
    ): BootPlaybackState = synchronized(lock) {
        val current = readStateLocked()
        val next = current.copy(
            lastNotificationStartupWindowId = startupWindowId,
            lastProgressAt = current.lastProgressAt ?: nowMillis
        )
        writeStateLocked(next)
        next
    }

    /**
     * Đánh dấu failed cho active session bị treo trước khi vào phase PLAYING.
     *
     * Session đang PLAYING không bị expire ở đây vì player callback mới là nguồn
     * quyết định terminal state cho audio đang phát.
     */
    fun expireStaleActiveSession(
        nowMillis: Long,
        staleTimeoutMillis: Long,
        failureReason: String
    ): BootPlaybackState = synchronized(lock) {
        val current = readStateLocked()
        if (!current.isActive()) {
            return@synchronized current
        }
        if (current.phase == BootPlaybackPhase.PLAYING) {
            return@synchronized current
        }

        val lastProgressAt = current.lastProgressAt
        val isStale = lastProgressAt == null || nowMillis - lastProgressAt >= staleTimeoutMillis
        if (!isStale) {
            return@synchronized current
        }

        val next = current.copy(
            phase = BootPlaybackPhase.FAILED,
            lastProgressAt = nowMillis,
            lastFailureReason = failureReason
        )
        writeStateLocked(next)
        next
    }

    /**
     * Cập nhật phase cho đúng session, throw nếu caller đưa nhầm session id.
     */
    private fun updateSessionState(
        sessionId: String,
        nowMillis: Long,
        phase: BootPlaybackPhase
    ): BootPlaybackState = synchronized(lock) {
        val current = readStateLocked()
        require(current.sessionId == sessionId) {
            "Cannot update boot playback session $sessionId when current session is ${current.sessionId}"
        }
        val next = current.copy(
            phase = phase,
            lastProgressAt = nowMillis
        )
        writeStateLocked(next)
        next
    }

    /**
     * Cập nhật phase chỉ khi session id vẫn là session hiện tại.
     */
    private fun updateSessionStateIfCurrent(
        sessionId: String,
        nowMillis: Long,
        phase: BootPlaybackPhase,
        failureReason: String? = null
    ): Boolean = synchronized(lock) {
        val current = readStateLocked()
        if (current.sessionId != sessionId) {
            return@synchronized false
        }

        val next = current.copy(
            phase = phase,
            lastProgressAt = nowMillis,
            lastFailureReason = failureReason ?: current.lastFailureReason
        )
        writeStateLocked(next)
        true
    }

    /**
     * Đọc state trong lock để mọi caller dùng cùng một đường đọc disk.
     */
    private fun readStateLocked(): BootPlaybackState {
        return readStateFromDisk()
    }

    /**
     * Parse state từ properties file; nếu chưa có file thì trả về state idle.
     */
    private fun readStateFromDisk(): BootPlaybackState {
        if (!stateFile.exists()) {
            return BootPlaybackState()
        }

        val properties = Properties()
        FileInputStream(stateFile).use { input ->
            properties.load(input)
        }

        return BootPlaybackState(
            startupWindowId = properties.getLong("startupWindowId"),
            sessionId = properties.getProperty("sessionId"),
            ownerOrigin = properties.getBootSignalOrigin("ownerOrigin"),
            phase = properties.getBootPlaybackPhase("phase"),
            lastProgressAt = properties.getLong("lastProgressAt"),
            takeoverCount = properties.getInt("takeoverCount") ?: 0,
            lastFailureReason = properties.getProperty("lastFailureReason"),
            lastNotificationStartupWindowId = properties.getLong("lastNotificationStartupWindowId")
        )
    }

    /**
     * Ghi state bằng temp file rồi atomic move để tránh file state bị ghi dở.
     */
    private fun writeStateLocked(state: BootPlaybackState) {
        Files.createDirectories(storageDir.toPath())

        val properties = Properties().apply {
            setLong("startupWindowId", state.startupWindowId)
            setString("sessionId", state.sessionId)
            setString("ownerOrigin", state.ownerOrigin?.name)
            setString("phase", state.phase.name)
            setLong("lastProgressAt", state.lastProgressAt)
            setInt("takeoverCount", state.takeoverCount)
            setString("lastFailureReason", state.lastFailureReason)
            setLong("lastNotificationStartupWindowId", state.lastNotificationStartupWindowId)
        }

        val tempFile = Files.createTempFile(storageDir.toPath(), "boot_playback_state", ".tmp").toFile()
        FileOutputStream(tempFile).use { output ->
            properties.store(output, "boot playback state")
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
        _stateFlow.value = state
    }

    /**
     * Ghi hoặc xóa một giá trị Long nullable trong properties.
     */
    private fun Properties.setLong(key: String, value: Long?) {
        if (value != null) {
            setProperty(key, value.toString())
        } else {
            remove(key)
        }
    }

    /**
     * Ghi một giá trị Int vào properties.
     */
    private fun Properties.setInt(key: String, value: Int) {
        setProperty(key, value.toString())
    }

    /**
     * Ghi hoặc xóa một giá trị String nullable trong properties.
     */
    private fun Properties.setString(key: String, value: String?) {
        if (value != null) {
            setProperty(key, value)
        } else {
            remove(key)
        }
    }

    /**
     * Đọc Long nullable từ properties, trả về null nếu dữ liệu không hợp lệ.
     */
    private fun Properties.getLong(key: String): Long? {
        return getProperty(key)?.toLongOrNull()
    }

    /**
     * Đọc Int nullable từ properties, trả về null nếu dữ liệu không hợp lệ.
     */
    private fun Properties.getInt(key: String): Int? {
        return getProperty(key)?.toIntOrNull()
    }

    /**
     * Đọc origin từ properties và bỏ qua giá trị lạ để tránh crash khi schema cũ.
     */
    private fun Properties.getBootSignalOrigin(key: String): BootSignalOrigin? {
        return getProperty(key)?.let { raw ->
            runCatching { BootSignalOrigin.valueOf(raw) }.getOrNull()
        }
    }

    /**
     * Đọc phase từ properties; fallback về IDLE khi file cũ hoặc dữ liệu lỗi.
     */
    private fun Properties.getBootPlaybackPhase(key: String): BootPlaybackPhase {
        return getProperty(key)?.let { raw ->
            runCatching { BootPlaybackPhase.valueOf(raw) }.getOrNull()
        } ?: BootPlaybackPhase.IDLE
    }

    companion object {
        private const val STORAGE_DIRECTORY_NAME = "boot_sound"
        private const val STATE_FILE_NAME = "boot_playback_state.properties"
    }
}
