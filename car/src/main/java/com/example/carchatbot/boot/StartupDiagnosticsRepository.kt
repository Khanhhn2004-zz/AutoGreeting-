package com.example.carchatbot.boot

import android.util.Log
import com.example.carchatbot.data.remote.model.AppLogRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queue lưu diagnostics startup/BOOT khi network hoặc uploader chưa sẵn sàng.
 *
 * Repository này giữ log trong direct-boot-safe storage để các lỗi xảy ra rất
 * sớm trong boot vẫn có thể được gửi lên support report sau khi app online.
 */
@Singleton
class StartupDiagnosticsRepository(
    private val storageDir: File,
    private val maxEntries: Int = MAX_ENTRIES,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
    private val json: Json = defaultJson()
) {

    /**
     * Constructor Hilt dùng boot storage mặc định của app.
     */
    @Inject
    constructor(bootStorageContextProvider: BootStorageContextProvider) : this(
        storageDir = bootStorageContextProvider.startupStorageDir(STORAGE_DIRECTORY_NAME),
        maxEntries = MAX_ENTRIES,
        retentionMillis = DEFAULT_RETENTION_MILLIS,
        json = defaultJson()
    )

    private val lock = Any()
    private var loaded = false
    private var pending = ArrayDeque<AppLogRequest>()
    private var lastCleanupAt: Long? = null
    private val storageFile: File
        get() = File(storageDir, STORAGE_FILE_NAME)

    /**
     * Thêm một log vào queue sau khi giới hạn độ dài message/extra.
     *
     * Trả về log đã được lưu hoặc `null` nếu persist thất bại.
     */
    fun append(log: AppLogRequest): AppLogRequest? {
        return synchronized(lock) {
            runCatching {
                val boundedLog = log.bounded()
                val current = loadLocked()
                current.addLast(boundedLog)
                trimToMaxSize(current)
                persistLocked(current)
                boundedLog
            }.getOrElse { error ->
                Log.w(TAG, "Failed to persist startup diagnostics entry", error)
                null
            }
        }
    }

    /**
     * Đọc snapshot hiện tại của queue để uploader/support report lấy đi.
     */
    fun snapshot(): List<AppLogRequest> {
        synchronized(lock) {
            return runCatching {
                loadLocked(forceRefresh = true).toList()
            }.getOrElse { error ->
                Log.w(TAG, "Failed to read startup diagnostics queue", error)
                emptyList()
            }
        }
    }

    /**
     * Xóa các log đã được giao thành công khỏi queue.
     */
    fun acknowledge(delivered: List<AppLogRequest>) {
        if (delivered.isEmpty()) {
            return
        }

        synchronized(lock) {
            runCatching {
                val deliveredSet = delivered.toHashSet()
                val retained = loadLocked().filterNot { it in deliveredSet }
                pending = ArrayDeque(retained)
                persistLocked(pending)
            }.onFailure { error ->
                Log.w(TAG, "Failed to acknowledge startup diagnostics batch", error)
            }
        }
    }

    /**
     * Xóa các log quá hạn theo retention window.
     */
    fun pruneExpired(nowMillis: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            runCatching {
                pruneExpiredLocked(nowMillis)
            }.onFailure { error ->
                Log.w(TAG, "Failed to prune startup diagnostics queue", error)
            }
        }
    }

    /**
     * Chỉ prune khi đã tới thời điểm cleanup kế tiếp để tránh ghi disk liên tục.
     */
    fun pruneExpiredIfDue(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return synchronized(lock) {
            runCatching {
                loadLocked()
                val previousCleanupAt = lastCleanupAt
                if (previousCleanupAt != null && nowMillis - previousCleanupAt < retentionMillis) {
                    return@runCatching false
                }

                pruneExpiredLocked(nowMillis)
                true
            }.getOrElse { error ->
                Log.w(TAG, "Failed to run opportunistic startup diagnostics cleanup", error)
                false
            }
        }
    }

    /**
     * Xóa toàn bộ queue diagnostics.
     */
    fun clear() {
        synchronized(lock) {
            runCatching {
                pending = ArrayDeque()
                loaded = true
                persistLocked(pending)
            }.onFailure { error ->
                Log.w(TAG, "Failed to clear startup diagnostics queue", error)
            }
        }
    }

    /**
     * Load queue từ disk vào memory cache, hoặc refresh bắt buộc khi caller cần snapshot mới.
     */
    private fun loadLocked(forceRefresh: Boolean = false): ArrayDeque<AppLogRequest> {
        if (loaded && !forceRefresh) {
            return pending
        }

        pending = if (!storageFile.exists()) {
            ArrayDeque()
        } else {
            runCatching {
                val stored = json.decodeFromString<StoredStartupDiagnostics>(
                    storageFile.readText()
                )
                lastCleanupAt = stored.lastCleanupAt
                ArrayDeque(stored.logs)
            }.getOrElse { error ->
                Log.w(TAG, "Failed to decode startup diagnostics queue; starting fresh", error)
                ArrayDeque()
            }
        }

        trimToMaxSize(pending)
        loaded = true
        return pending
    }

    /**
     * Ghi queue hiện tại xuống disk bằng JSON.
     */
    private fun persistLocked(entries: ArrayDeque<AppLogRequest>) {
        storageFile.parentFile?.mkdirs()
        val payload = StoredStartupDiagnostics(logs = entries.toList(), lastCleanupAt = lastCleanupAt)
        storageFile.writeText(json.encodeToString(payload))
        pending = ArrayDeque(entries)
        loaded = true
    }

    /**
     * Loại bỏ entry cũ hơn retention window và persist lại queue.
     */
    private fun pruneExpiredLocked(nowMillis: Long) {
        val cutoff = nowMillis - retentionMillis
        val retained = loadLocked().filter { it.createdAt >= cutoff }
        pending = ArrayDeque(retained)
        lastCleanupAt = nowMillis
        persistLocked(pending)
    }

    /**
     * Giữ queue không vượt quá số entry tối đa.
     */
    private fun trimToMaxSize(entries: ArrayDeque<AppLogRequest>) {
        while (entries.size > maxEntries) {
            entries.removeFirst()
        }
    }

    /**
     * Cắt message/extra để diagnostics không làm phình storage hoặc report.
     */
    private fun AppLogRequest.bounded(): AppLogRequest {
        return copy(
            message = message.truncateTo(MAX_MESSAGE_LENGTH),
            extra = extra?.truncateTo(MAX_EXTRA_LENGTH)
        )
    }

    /**
     * Cắt chuỗi và thêm suffix để người đọc biết nội dung đã bị rút gọn.
     */
    private fun String.truncateTo(maxLength: Int): String {
        if (length <= maxLength) {
            return this
        }

        return take(maxLength - TRUNCATED_SUFFIX.length) + TRUNCATED_SUFFIX
    }

    /**
     * Payload JSON lưu trên disk.
     */
    @Serializable
    private data class StoredStartupDiagnostics(
        val logs: List<AppLogRequest> = emptyList(),
        val lastCleanupAt: Long? = null
    )

    companion object {
        private const val TAG = "StartupDiagnosticsRepo"
        private const val STORAGE_DIRECTORY_NAME = "boot_sound"
        private const val STORAGE_FILE_NAME = "startup-diagnostics.json"
        internal const val MAX_ENTRIES = 600
        private const val MAX_MESSAGE_LENGTH = 1024
        private const val MAX_EXTRA_LENGTH = 8192
        private const val TRUNCATED_SUFFIX = "[truncated]"
        private const val DEFAULT_RETENTION_MILLIS = 3L * 24L * 60L * 60L * 1000L

        /**
         * Cấu hình JSON chịu được field mới/cũ giữa các version app.
         */
        private fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
