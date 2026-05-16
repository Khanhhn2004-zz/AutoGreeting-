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
 * Bằng chứng bền vững cho biết một BOOT candidate window đang tồn tại.
 *
 * State này được ghi trước khi phát nhạc để receiver/service bị kill vẫn có thể
 * được recovery sau đó bằng runtime reconcile hoặc process-visible BOOT recovery.
 */
data class StartupArmState(
    val startupWindowId: Long? = null,
    val compatibilityProfile: StartupCompatibilityProfile = StartupCompatibilityProfile.DEFAULT,
    val observedOrigins: Set<BootSignalOrigin> = emptySet(),
    val armedAtMillis: Long? = null,
    val consumedAtMillis: Long? = null
) {
    /**
     * Cho biết BOOT window đã được arm nhưng chưa bị consume bởi recovery/execution path.
     */
    fun isPending(): Boolean = startupWindowId != null && consumedAtMillis == null
}

/**
 * Store nhỏ, direct-boot-safe, dùng để lưu startup-arm state.
 *
 * Store dùng properties file đơn giản vì dữ liệu này phải đọc được rất sớm khi
 * Android box khởi động và phải sống sót qua process death giữa
 * `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED` và service reconcile.
 */
class StartupArmStateStore(private val storageDir: File) {

    constructor(bootStorageContextProvider: BootStorageContextProvider) :
        this(bootStorageContextProvider.startupStorageDir(STORAGE_DIRECTORY_NAME))

    private val stateFile = File(storageDir, STATE_FILE_NAME)
    private val lock = Any()
    private val _stateFlow = MutableStateFlow(readStateFromDisk())

    val stateFlow: StateFlow<StartupArmState> = _stateFlow.asStateFlow()

    /**
     * Đọc arm state mới nhất từ disk và đồng bộ lại [stateFlow].
     */
    fun readState(): StartupArmState = synchronized(lock) {
        val state = readStateLocked()
        if (_stateFlow.value != state) {
            _stateFlow.value = state
        }
        state
    }

    /**
     * Arm một startup window khi app thấy bằng chứng BOOT.
     *
     * Nếu cùng window đã tồn tại, origin mới được merge vào observed origins.
     */
    fun armStartupWindow(
        startupWindowId: Long,
        origin: BootSignalOrigin,
        compatibilityProfile: StartupCompatibilityProfile,
        nowMillis: Long
    ): StartupArmState = synchronized(lock) {
        val current = readStateLocked()
        val next = if (current.startupWindowId == startupWindowId) {
            current.copy(
                compatibilityProfile = compatibilityProfile,
                observedOrigins = current.observedOrigins + origin,
                armedAtMillis = current.armedAtMillis ?: nowMillis,
                consumedAtMillis = null
            )
        } else {
            StartupArmState(
                startupWindowId = startupWindowId,
                compatibilityProfile = compatibilityProfile,
                observedOrigins = setOf(origin),
                armedAtMillis = nowMillis,
                consumedAtMillis = null
            )
        }
        writeStateLocked(next)
        next
    }

    /**
     * Ghi thêm origin quan sát được cho startup window hiện tại.
     */
    fun recordObservedOrigin(
        startupWindowId: Long,
        origin: BootSignalOrigin,
        nowMillis: Long
    ): StartupArmState = synchronized(lock) {
        val current = readStateLocked()
        require(current.startupWindowId == startupWindowId) {
            "Cannot record origin for startup window $startupWindowId when current window is ${current.startupWindowId}"
        }
        val next = current.copy(
            observedOrigins = current.observedOrigins + origin,
            armedAtMillis = current.armedAtMillis ?: nowMillis
        )
        writeStateLocked(next)
        next
    }

    /**
     * Đánh dấu startup window đã được consume để các recovery path sau không phát lại.
     */
    fun markConsumed(
        startupWindowId: Long,
        nowMillis: Long
    ): StartupArmState = synchronized(lock) {
        val current = readStateLocked()
        require(current.startupWindowId == startupWindowId) {
            "Cannot consume startup window $startupWindowId when current window is ${current.startupWindowId}"
        }
        val next = current.copy(consumedAtMillis = nowMillis)
        writeStateLocked(next)
        next
    }

    /**
     * Đọc state trong lock để mọi caller đi qua cùng một điểm đọc disk.
     */
    private fun readStateLocked(): StartupArmState {
        return readStateFromDisk()
    }

    /**
     * Parse state từ properties file; nếu chưa có file thì trả về state rỗng.
     */
    private fun readStateFromDisk(): StartupArmState {
        if (!stateFile.exists()) {
            return StartupArmState()
        }

        val properties = Properties()
        FileInputStream(stateFile).use { input ->
            properties.load(input)
        }

        return StartupArmState(
            startupWindowId = properties.getLong("startupWindowId"),
            compatibilityProfile = properties.getCompatibilityProfile("compatibilityProfile")
                ?: StartupCompatibilityProfile.DEFAULT,
            observedOrigins = properties.getObservedOrigins("observedOrigins"),
            armedAtMillis = properties.getLong("armedAtMillis"),
            consumedAtMillis = properties.getLong("consumedAtMillis")
        )
    }

    /**
     * Ghi state qua temp file và atomic move để tránh state bị ghi dở khi boot.
     */
    private fun writeStateLocked(state: StartupArmState) {
        Files.createDirectories(storageDir.toPath())

        val properties = Properties().apply {
            setLong("startupWindowId", state.startupWindowId)
            setString("compatibilityProfile", state.compatibilityProfile.name)
            setString(
                "observedOrigins",
                state.observedOrigins
                    .map { it.name }
                    .sorted()
                    .joinToString(",")
            )
            setLong("armedAtMillis", state.armedAtMillis)
            setLong("consumedAtMillis", state.consumedAtMillis)
        }

        val tempFile = Files.createTempFile(storageDir.toPath(), "startup_arm_state", ".tmp").toFile()
        FileOutputStream(tempFile).use { output ->
            properties.store(output, "startup arm state")
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
     * Ghi hoặc xóa Long nullable trong properties.
     */
    private fun Properties.setLong(key: String, value: Long?) {
        if (value != null) {
            setProperty(key, value.toString())
        } else {
            remove(key)
        }
    }

    /**
     * Ghi hoặc xóa String nullable trong properties.
     */
    private fun Properties.setString(key: String, value: String?) {
        if (value != null) {
            setProperty(key, value)
        } else {
            remove(key)
        }
    }

    /**
     * Đọc Long nullable từ properties, trả null nếu không parse được.
     */
    private fun Properties.getLong(key: String): Long? {
        return getProperty(key)?.toLongOrNull()
    }

    /**
     * Đọc compatibility profile và bỏ qua giá trị lạ để tương thích file cũ.
     */
    private fun Properties.getCompatibilityProfile(key: String): StartupCompatibilityProfile? {
        return getProperty(key)?.let { raw ->
            runCatching { StartupCompatibilityProfile.valueOf(raw) }.getOrNull()
        }
    }

    /**
     * Đọc danh sách origin đã quan sát, bỏ qua entry lạ thay vì làm crash boot path.
     */
    private fun Properties.getObservedOrigins(key: String): Set<BootSignalOrigin> {
        return getProperty(key)
            ?.split(',')
            ?.mapNotNull { raw ->
                raw
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { runCatching { BootSignalOrigin.valueOf(it) }.getOrNull() }
            }
            ?.toSet()
            ?: emptySet()
    }

    companion object {
        private const val STORAGE_DIRECTORY_NAME = "startup_arm"
        private const val STATE_FILE_NAME = "startup_arm_state.properties"
    }
}
