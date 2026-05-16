package com.example.carchatbot.boot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trạng thái metadata của boot cache.
 */
enum class BootSoundCacheStatus {
    WRITING,
    READY,
    INVALID
}

/**
 * Lý do cụ thể giải thích vì sao boot cache có hoặc chưa có thể phát.
 */
enum class BootSoundCacheReadinessReason {
    READY,
    MISSING_METADATA,
    INVALID_METADATA,
    METADATA_NOT_READY,
    MISSING_AUDIO_FILE,
    AUDIO_TOO_SMALL
}

/**
 * File audio trong boot cache đã resolve và an toàn để phát local ngay lập tức.
 */
data class BootSoundCacheEntry(
    val soundIndex: Int,
    val cacheFile: File,
    val status: BootSoundCacheStatus,
    val byteCount: Long,
    val updatedAtMillis: Long
)

/**
 * Snapshot dành cho support để biết vì sao BOOT cache có hoặc không thể phát.
 *
 * Diagnostics dùng object này để tránh báo cáo mơ hồ kiểu "main screen báo
 * cache ready nhưng boot không phát"; mỗi terminal `NO_CACHE` phải map về một
 * readiness reason cụ thể.
 */
data class BootSoundCacheReadiness(
    val reason: BootSoundCacheReadinessReason,
    val metadataExists: Boolean,
    val metadataStatus: BootSoundCacheStatus?,
    val audioExists: Boolean,
    val audioByteCount: Long,
    val metadataByteCount: Long?,
    val soundIndex: Int?,
    val updatedAtMillis: Long?
) {
    val isReady: Boolean
        get() = reason == BootSoundCacheReadinessReason.READY

    /**
     * Format snapshot thành chuỗi key-value để support log đọc được ngay.
     */
    fun toLogDetails(): String {
        return listOf(
            "reason=${reason.name.lowercase()}",
            "metadataExists=$metadataExists",
            "metadataStatus=${metadataStatus?.name ?: "none"}",
            "audioExists=$audioExists",
            "audioBytes=$audioByteCount",
            "metadataBytes=${metadataByteCount ?: 0L}",
            "soundIndex=${soundIndex ?: -1}",
            "updatedAt=${updatedAtMillis ?: 0L}"
        ).joinToString(separator = " ")
    }
}

/**
 * Repository direct-boot-safe cho audio cache startup của nhánh BOOT.
 *
 * Nhánh BOOT chỉ đọc từ `startup_sound.audio`. Server download, account sync và
 * file preview thủ công không nằm trong đường này vì chúng quá muộn và quá dễ
 * lỗi trong giai đoạn Android box đang khởi động. Metadata hỏng nhưng còn phục
 * hồi được sẽ được sửa từ file audio local hiện có trước khi kết luận terminal
 * `NO_CACHE`.
 */
@Singleton
class BootSoundCacheRepository @Inject constructor(
    private val bootStorageContextProvider: BootStorageContextProvider
) {
    /**
     * Trả về boot-cache entry có thể phát hoặc `null` sau khi repair/retry có giới hạn.
     *
     * Method này cố ý chờ rất thận trọng: chịu được một race ngắn khi metadata
     * đang được ghi, nhưng không bao giờ download hoặc block theo server trong
     * lúc thiết bị đang boot.
     */
    suspend fun getReadyBootSound(): BootSoundCacheEntry? {
        return withContext(Dispatchers.IO) {
            var readiness = repairRecoverableMetadataBlocking(inspectReadinessBlocking())
            var retryCount = 0
            while (
                !readiness.isReady &&
                readiness.reason == BootSoundCacheReadinessReason.METADATA_NOT_READY &&
                retryCount < CACHE_READY_RETRY_COUNT
            ) {
                delay(CACHE_READY_RETRY_DELAY_MS)
                readiness = repairRecoverableMetadataBlocking(inspectReadinessBlocking())
                retryCount++
            }

            if (!readiness.isReady) {
                return@withContext null
            }

            readyEntryFromReadiness(readiness)
        }
    }

    /**
     * Đọc snapshot readiness hiện tại mà không bắt buộc cache phải ready.
     *
     * Dùng cho diagnostics và no-cache logging để biết chính xác blocker.
     */
    suspend fun inspectReadiness(): BootSoundCacheReadiness {
        return withContext(Dispatchers.IO) {
            inspectReadinessBlocking()
        }
    }

    /**
     * Thay thế BOOT cache theo kiểu atomic sau khi sound đã được tải bởi luồng
     * app/network bình thường.
     *
     * Thứ tự ghi bảo vệ boot reader khỏi file ghi dở: đánh dấu metadata là
     * `WRITING`, move file vào vị trí chính thức, rồi publish trạng thái `READY`.
     */
    suspend fun replaceStartupSoundCache(
        soundIndex: Int,
        sourceFile: File
    ): BootSoundCacheEntry? {
        return withContext(Dispatchers.IO) {
            val storageDir = storageDir()
            val cacheFile = cacheFile()
            val stageFile = File(
                storageDir,
                ".boot_sound.stage-${System.nanoTime()}"
            )
            val backupFile = if (cacheFile.exists()) {
                File(storageDir, ".boot_sound.backup-${System.nanoTime()}")
            } else {
                null
            }

            try {
                writeStage(stageFile, sourceFile)
                writeMetadata(
                    CacheMetadata(
                        soundIndex = soundIndex,
                        status = BootSoundCacheStatus.WRITING,
                        byteCount = stageFile.length(),
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )

                if (!stageFile.exists() || stageFile.length() < MIN_VALID_SOUND_BYTES) {
                    writeMetadata(
                        CacheMetadata(
                            soundIndex = soundIndex,
                            status = BootSoundCacheStatus.INVALID,
                            byteCount = stageFile.length(),
                            updatedAtMillis = System.currentTimeMillis()
                        )
                    )
                    return@withContext null
                }

                if (backupFile != null) {
                    cacheFile.copyTo(backupFile, overwrite = true)
                }

                moveIntoPlace(stageFile, cacheFile)
                val readyMetadata = CacheMetadata(
                    soundIndex = soundIndex,
                    status = BootSoundCacheStatus.READY,
                    byteCount = cacheFile.length(),
                    updatedAtMillis = System.currentTimeMillis()
                )
                writeMetadata(readyMetadata)
                BootSoundCacheEntry(
                    soundIndex = soundIndex,
                    cacheFile = cacheFile,
                    status = BootSoundCacheStatus.READY,
                    byteCount = cacheFile.length(),
                    updatedAtMillis = readyMetadata.updatedAtMillis
                )
            } catch (_: Exception) {
                if (backupFile != null && backupFile.exists()) {
                    runCatching {
                        moveIntoPlace(backupFile, cacheFile)
                    }
                }

                writeMetadata(
                    CacheMetadata(
                        soundIndex = soundIndex,
                        status = BootSoundCacheStatus.INVALID,
                        byteCount = 0L,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
                null
            } finally {
                deleteIfExists(stageFile)
                deleteIfExists(backupFile)
            }
        }
    }

    /**
     * Xóa boot cache và metadata, thường dùng khi reset hoặc test.
     */
    suspend fun clearStartupSoundCache() {
        withContext(Dispatchers.IO) {
            deleteIfExists(cacheFile())
            deleteIfExists(metadataFile())
        }
    }

    /**
     * Trả về thư mục lưu boot cache/state trong direct-boot-safe storage.
     */
    private fun storageDir(): File {
        return bootStorageContextProvider.startupStorageDir(CACHE_DIRECTORY_NAME)
    }

    /**
     * Trả về file audio chính thức mà BOOT playback sẽ phát.
     */
    private fun cacheFile(): File {
        return File(storageDir(), CACHE_FILE_NAME)
    }

    /**
     * Trả về file metadata mô tả trạng thái của boot cache.
     */
    private fun metadataFile(): File {
        return File(storageDir(), METADATA_FILE_NAME)
    }

    /**
     * Copy source audio vào stage file trước khi publish thành boot cache chính thức.
     */
    private fun writeStage(stageFile: File, sourceFile: File) {
        sourceFile.inputStream().use { input ->
            stageFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * Move file vào vị trí cuối cùng, ưu tiên atomic move để tránh reader thấy file ghi dở.
     */
    private fun moveIntoPlace(sourceFile: File, targetFile: File) {
        try {
            Files.move(
                sourceFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                sourceFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    /**
     * Ghi metadata qua stage file để metadata không bị publish nửa chừng.
     */
    private fun writeMetadata(metadata: CacheMetadata) {
        val json = JSONObject().apply {
            put("soundIndex", metadata.soundIndex)
            put("status", metadata.status.name)
            put("byteCount", metadata.byteCount)
            put("updatedAtMillis", metadata.updatedAtMillis)
            put("directory", CACHE_DIRECTORY_NAME)
            put("fileName", CACHE_FILE_NAME)
        }

        val metadataFile = metadataFile()
        val stageFile = File(
            metadataFile.parentFile,
            ".startup_sound.metadata-${System.nanoTime()}"
        )

        try {
            stageFile.writeText(json.toString())
            moveIntoPlace(stageFile, metadataFile)
        } finally {
            deleteIfExists(stageFile)
        }
    }

    /**
     * Xóa file nếu tồn tại và bỏ qua khi file null.
     */
    private fun deleteIfExists(file: File?) {
        if (file == null) return

        runCatching {
            if (file.exists() && !file.delete()) {
                throw IOException("Unable to delete ${file.absolutePath}")
            }
        }
    }

    /**
     * Kiểm tra readiness bằng thao tác blocking trong IO dispatcher của caller.
     */
    private fun inspectReadinessBlocking(): BootSoundCacheReadiness {
        val cacheFile = cacheFile()
        val audioExists = cacheFile.exists()
        val audioByteCount = if (audioExists) cacheFile.length() else 0L

        return when (val metadataResult = readMetadataResult()) {
            MetadataReadResult.Missing -> BootSoundCacheReadiness(
                reason = BootSoundCacheReadinessReason.MISSING_METADATA,
                metadataExists = false,
                metadataStatus = null,
                audioExists = audioExists,
                audioByteCount = audioByteCount,
                metadataByteCount = null,
                soundIndex = null,
                updatedAtMillis = null
            )

            MetadataReadResult.Invalid -> BootSoundCacheReadiness(
                reason = BootSoundCacheReadinessReason.INVALID_METADATA,
                metadataExists = true,
                metadataStatus = null,
                audioExists = audioExists,
                audioByteCount = audioByteCount,
                metadataByteCount = null,
                soundIndex = null,
                updatedAtMillis = null
            )

            is MetadataReadResult.Present -> {
                val metadata = metadataResult.metadata
                val reason = when {
                    metadata.status != BootSoundCacheStatus.READY -> BootSoundCacheReadinessReason.METADATA_NOT_READY
                    !audioExists -> BootSoundCacheReadinessReason.MISSING_AUDIO_FILE
                    audioByteCount < MIN_VALID_SOUND_BYTES -> BootSoundCacheReadinessReason.AUDIO_TOO_SMALL
                    else -> BootSoundCacheReadinessReason.READY
                }
                BootSoundCacheReadiness(
                    reason = reason,
                    metadataExists = true,
                    metadataStatus = metadata.status,
                    audioExists = audioExists,
                    audioByteCount = audioByteCount,
                    metadataByteCount = metadata.byteCount,
                    soundIndex = metadata.soundIndex,
                    updatedAtMillis = metadata.updatedAtMillis
                )
            }
        }
    }

    /**
     * Repair metadata khi audio local vẫn tồn tại và đủ lớn để phát.
     *
     * Đây là fix cho case UI báo cache đã có nhưng boot path đọc metadata hỏng.
     */
    private fun repairRecoverableMetadataBlocking(
        readiness: BootSoundCacheReadiness
    ): BootSoundCacheReadiness {
        val recoverableMetadataReason =
            readiness.reason == BootSoundCacheReadinessReason.MISSING_METADATA ||
                readiness.reason == BootSoundCacheReadinessReason.INVALID_METADATA
        if (
            recoverableMetadataReason &&
            readiness.audioExists &&
            readiness.audioByteCount >= MIN_VALID_SOUND_BYTES
        ) {
            writeMetadata(
                CacheMetadata(
                    soundIndex = STARTUP_SOUND_INDEX,
                    status = BootSoundCacheStatus.READY,
                    byteCount = readiness.audioByteCount,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
            return inspectReadinessBlocking()
        }

        return readiness
    }

    /**
     * Chuyển readiness READY thành entry phát được.
     */
    private fun readyEntryFromReadiness(readiness: BootSoundCacheReadiness): BootSoundCacheEntry {
        return BootSoundCacheEntry(
            soundIndex = readiness.soundIndex ?: STARTUP_SOUND_INDEX,
            cacheFile = cacheFile(),
            status = BootSoundCacheStatus.READY,
            byteCount = readiness.audioByteCount,
            updatedAtMillis = readiness.updatedAtMillis ?: 0L
        )
    }

    /**
     * Đọc metadata và phân loại rõ: missing, invalid hoặc present.
     */
    private fun readMetadataResult(): MetadataReadResult {
        val file = metadataFile()
        if (!file.exists()) {
            return MetadataReadResult.Missing
        }

        return runCatching {
            val json = JSONObject(file.readText())
            MetadataReadResult.Present(
                CacheMetadata(
                    soundIndex = json.getInt("soundIndex"),
                    status = BootSoundCacheStatus.valueOf(json.getString("status")),
                    byteCount = json.optLong("byteCount", 0L),
                    updatedAtMillis = json.optLong("updatedAtMillis", 0L)
                )
            )
        }.getOrElse {
            MetadataReadResult.Invalid
        }
    }

    /**
     * Metadata nhỏ được lưu cạnh file audio để boot path biết file nào đã ready.
     */
    private data class CacheMetadata(
        val soundIndex: Int,
        val status: BootSoundCacheStatus,
        val byteCount: Long,
        val updatedAtMillis: Long
    )

    /**
     * Kết quả parse metadata nội bộ để tách lỗi file không tồn tại khỏi file hỏng.
     */
    private sealed interface MetadataReadResult {
        data object Missing : MetadataReadResult
        data object Invalid : MetadataReadResult
        /**
         * Metadata parse thành công và có thể dùng để đánh giá readiness.
         */
        data class Present(val metadata: CacheMetadata) : MetadataReadResult
    }

    companion object {
        const val STARTUP_SOUND_INDEX = 1
        private const val CACHE_DIRECTORY_NAME = "boot_sound"
        private const val CACHE_FILE_NAME = "startup_sound.audio"
        private const val METADATA_FILE_NAME = "startup_sound.json"
        private const val MIN_VALID_SOUND_BYTES = 2048L
        private const val CACHE_READY_RETRY_COUNT = 3
        private const val CACHE_READY_RETRY_DELAY_MS = 150L
    }
}
