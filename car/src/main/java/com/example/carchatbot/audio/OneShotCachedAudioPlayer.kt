package com.example.carchatbot.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import com.example.carchatbot.utils.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Player local-file tối giản dùng cho startup playback của nhánh BOOT.
 *
 * Player này không resolve trạng thái sound từ network/server và không phụ
 * thuộc lifecycle của UI. Nó giữ partial wake lock, prepare trực tiếp file cache,
 * request audio focus như một phép lịch sự với hệ thống, nhưng vẫn phát khi
 * focus bị delayed hoặc unavailable để BOOT giữ hành vi giống MacroDroid trên
 * các đầu Android có audio policy không ổn định.
 */
class OneShotCachedAudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger
) {

    private val lock = Any()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> startPendingPreparedPlayback("audio_focus_gain")
        }
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaPlayer: MediaPlayer? = null
    private var pendingStart: PendingStart? = null

    /**
     * Phát một lần file startup cache đã được xác thực và báo lifecycle callback
     * về BOOT foreground service.
     *
     * Caller vẫn sở hữu session state; class này chỉ sở hữu MediaPlayer, wake
     * lock, audio focus và route diagnostics cho lượt one-shot playback đang chạy.
     */
    fun play(
        cachedFile: File,
        onStarted: () -> Unit = {},
        onCompleted: () -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val cachedUri = Uri.fromFile(cachedFile)

        if (!cachedFile.exists() || cachedFile.length() <= 0L) {
            onFailure(IllegalStateException("Cached file is missing: ${cachedUri}"))
            return
        }

        try {
            acquireWakeLock()

            val player = MediaPlayer()
            synchronized(lock) {
                releasePlayerLocked()
                mediaPlayer = player
            }

            player.setAudioAttributes(PlaybackAudioProfile.audioAttributes)

            player.setDataSource(cachedFile.absolutePath)

            player.setOnPreparedListener { preparedPlayer ->
                val durationMs = runCatching { preparedPlayer.duration }.getOrDefault(-1)
                extendWakeLockForPlayback(durationMs)
                synchronized(lock) {
                    pendingStart = PendingStart(
                        cachedFile = cachedFile,
                        durationMs = durationMs,
                        onStarted = onStarted,
                        onFailure = onFailure
                    )
                }
                appLogger.logBoot(
                    "OneShotCachedAudioPlayer",
                    "Boot audio prepared",
                    "file=${cachedFile.name} bytes=${cachedFile.length()} durationMs=$durationMs ${audioDiagnosticDetails()}"
                )
                val focusResult = requestAudioFocus()
                appLogger.logBoot(
                    "OneShotCachedAudioPlayer",
                    "Boot audio focus result",
                    "result=$focusResult file=${cachedFile.name} durationMs=$durationMs ${audioDiagnosticDetails()}"
                )
                when (focusResult) {
                    AudioFocusResult.GRANTED -> {
                        startPendingPreparedPlayback("audio_focus_granted")
                    }

                    AudioFocusResult.DELAYED -> {
                        appLogger.logBoot(
                            "OneShotCachedAudioPlayer",
                            "Boot audio focus delayed; starting anyway",
                            "file=${cachedFile.name} durationMs=$durationMs ${audioDiagnosticDetails()}"
                        )
                        startPendingPreparedPlayback("audio_focus_delayed_starting_anyway")
                    }

                    AudioFocusResult.FAILED -> {
                        appLogger.logBoot(
                            "OneShotCachedAudioPlayer",
                            "Boot audio focus unavailable; starting anyway",
                            "file=${cachedFile.name} durationMs=$durationMs ${audioDiagnosticDetails()}"
                        )
                        startPendingPreparedPlayback("audio_focus_failed_starting_anyway")
                    }
                }
            }

            player.setOnCompletionListener {
                cleanupPlayer()
                onCompleted()
            }

            player.setOnErrorListener { _, what, extra ->
                cleanupPlayer()
                onFailure(IOException("MediaPlayer error what=$what extra=$extra"))
                true
            }

            player.prepareAsync()
        } catch (throwable: Throwable) {
            cleanupPlayer()
            onFailure(throwable)
        }
    }

    /**
     * Release mọi tài nguyên audio đang giữ.
     *
     * Service gọi hàm này khi session kết thúc hoặc bị stop để không còn player,
     * wake lock hoặc audio focus rò rỉ sang lần boot sau.
     */
    fun release() {
        cleanupPlayer()
    }

    /**
     * Gom thông tin volume và route để support log biết thiết bị đang phát qua đâu.
     */
    private fun audioDiagnosticDetails(): String {
        val streamVolume = runCatching {
            audioManager.getStreamVolume(PlaybackAudioProfile.legacyStreamType)
        }.getOrDefault(-1)
        val streamMaxVolume = runCatching {
            audioManager.getStreamMaxVolume(PlaybackAudioProfile.legacyStreamType)
        }.getOrDefault(-1)

        return "volume=$streamVolume/$streamMaxVolume routes=${outputRouteSummary()}"
    }

    /**
     * Tóm tắt output route hiện tại của AudioManager.
     */
    private fun outputRouteSummary(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "unsupported"
        }

        val devices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())

        if (devices.isEmpty()) {
            return "none"
        }

        return devices.joinToString(separator = "|") { device ->
            "${device.type}:${device.productName}"
        }
    }

    /**
     * Start playback cho player đã prepare và còn đang pending.
     *
     * Hàm này idempotent theo `pendingStart`: chỉ một signal focus/granted/delayed
     * đầu tiên được phép consume pending start.
     */
    private fun startPendingPreparedPlayback(reason: String) {
        val player: MediaPlayer
        val start: PendingStart
        synchronized(lock) {
            player = mediaPlayer ?: return
            start = pendingStart ?: return
            pendingStart = null
        }

        runCatching {
            player.start()
            appLogger.logBoot(
                "OneShotCachedAudioPlayer",
                "Boot audio player started",
                "reason=$reason file=${start.cachedFile.name} positionMs=${player.currentPosition} durationMs=${start.durationMs} ${audioDiagnosticDetails()}"
            )
            start.onStarted()
        }.onFailure { throwable ->
            cleanupPlayer()
            start.onFailure(throwable)
        }
    }

    /**
     * Request audio focus theo SDK hiện tại và trả về kết quả đã chuẩn hóa.
     *
     * BOOT branch dùng kết quả này để log, không dùng nó làm blocker phát nhạc.
     */
    private fun requestAudioFocus(): AudioFocusResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(PlaybackAudioProfile.focusGain)
                .setAudioAttributes(PlaybackAudioProfile.audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = request
            when (audioManager.requestAudioFocus(request)) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> AudioFocusResult.GRANTED
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> AudioFocusResult.DELAYED
                else -> AudioFocusResult.FAILED
            }
        } else {
            @Suppress("DEPRECATION")
            when (
                audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    PlaybackAudioProfile.legacyStreamType,
                    PlaybackAudioProfile.focusGain
                )
            ) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> AudioFocusResult.GRANTED
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> AudioFocusResult.DELAYED
                else -> AudioFocusResult.FAILED
            }
        }
    }

    /**
     * Giữ CPU thức đủ lâu để prepare/start audio trong giai đoạn boot.
     */
    private fun acquireWakeLock() {
        synchronized(lock) {
            if (wakeLock?.isHeld == true) {
                return
            }

            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CarChatbot:OneShotCachedAudioPlayer"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
    }

    /**
     * Kéo dài wake lock theo duration audio sau khi MediaPlayer đã biết độ dài file.
     */
    private fun extendWakeLockForPlayback(durationMs: Int) {
        synchronized(lock) {
            val activeWakeLock = wakeLock ?: return
            val timeoutMs = if (durationMs > 0) {
                durationMs.toLong() + PLAYBACK_WAKE_LOCK_BUFFER_MS
            } else {
                WAKE_LOCK_TIMEOUT_MS
            }
            runCatching {
                activeWakeLock.acquire(timeoutMs.coerceAtLeast(WAKE_LOCK_TIMEOUT_MS))
            }
        }
    }

    /**
     * Dọn player, wake lock và audio focus theo đúng thứ tự.
     */
    private fun cleanupPlayer() {
        synchronized(lock) {
            releasePlayerLocked()
            releaseWakeLockLocked()
            releaseAudioFocusLocked()
        }
    }

    /**
     * Stop/reset/release MediaPlayer hiện tại và clear pending start.
     */
    private fun releasePlayerLocked() {
        runCatching {
            mediaPlayer?.stop()
        }
        runCatching {
            mediaPlayer?.reset()
        }
        runCatching {
            mediaPlayer?.release()
        }
        pendingStart = null
        mediaPlayer = null
    }

    /**
     * Release wake lock nếu đang held.
     */
    private fun releaseWakeLockLocked() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    /**
     * Bỏ audio focus đã request nếu có.
     */
    private fun releaseAudioFocusLocked() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
        }
        audioFocusRequest = null
    }

    /**
     * Kết quả audio focus đã chuẩn hóa để tách Android API detail khỏi playback policy.
     */
    private enum class AudioFocusResult {
        GRANTED,
        DELAYED,
        FAILED
    }

    /**
     * Thông tin cần giữ giữa lúc MediaPlayer prepared và lúc app quyết định start.
     */
    private data class PendingStart(
        val cachedFile: File,
        val durationMs: Int,
        val onStarted: () -> Unit,
        val onFailure: (Throwable) -> Unit
    )

    companion object {
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L
        private const val PLAYBACK_WAKE_LOCK_BUFFER_MS = 15_000L
    }
}
