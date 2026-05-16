package com.example.carchatbot.runtime

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.MotionEvent
import com.example.carchatbot.R
import com.example.carchatbot.boot.StartupMode
import com.example.carchatbot.service.PlaybackRequest
import com.example.carchatbot.service.PlaybackRequestIntent
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

enum class FloatingButtonPlaybackAction {
    START,
    STOP
}

enum class PlaybackTerminationReason {
    COMPLETED,
    EXPLICIT_STOP
}

enum class PlaybackRecoveryStep {
    REFETCH_FROM_SERVER,
    STOP_PLAYBACK
}

enum class AppOpenAutoplayAction {
    START_NEW_REQUEST,
    WAIT_FOR_PENDING_AUTOPLAY,
    REUSE_EXISTING_AUTOPLAY
}

enum class BootLikeVisibleCompatReason {
    SMALL_UPTIME_NO_RECEIVER,
    MODE_NOT_BOOT_COMPLETED,
    MISSING_PERMISSIONS,
    NOT_LOGGED_IN,
    LAUNCH_FROM_HISTORY,
    RESTORED_ACTIVITY,
    RECEIVER_ALREADY_SEEN,
    STARTUP_WINDOW_ALREADY_ARMED,
    BOOT_PLAYBACK_OWNERSHIP_PRESENT,
    UPTIME_TOO_OLD,
    PLAYABLE_SOURCE_UNAVAILABLE
}

data class BootLikeVisibleCompatEvaluation(
    val matches: Boolean,
    val reason: BootLikeVisibleCompatReason,
    val visibleLaunchElapsedRealtimeMillis: Long,
    val visibleLaunchFresh: Boolean,
    val bootReceiverSeenInCurrentCandidateWindow: Boolean
)

object AppRuntimePolicies {
    private const val FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY = 0x00100000
    private const val HEARTBEAT_NORMAL_DELAY_MS = 2 * 60 * 1000L
    private const val HEARTBEAT_RETRY_DELAY_MS = 5 * 60 * 1000L
    private const val HEARTBEAT_LONG_BACKOFF_MS = 15 * 60 * 1000L
    private const val FOREGROUND_UPDATE_THROTTLE_MS = 30 * 60 * 1000L
    private const val BOOT_INIT_DEDUPE_WINDOW_MS = 15_000L
    private const val BOOT_STARTUP_WINDOW_MS = 15_000L
    private const val BOOT_SESSION_STALE_TIMEOUT_MS = 5_000L
    private const val APP_OPEN_STARTUP_RECOVERY_WINDOW_MS = 60_000L
    private const val HEAD_UNIT_SLEEP_WAKE_STARTUP_WINDOW_MS = 90_000L
    private const val USB_HOST_ATTACH_STARTUP_WINDOW_MS = 120_000L
    private const val GENERIC_COMPATIBILITY_STARTUP_WINDOW_MS = 75_000L
    private const val BOOT_LIKE_VISIBLE_COMPATIBILITY_WINDOW_MS = 5 * 60 * 1000L
    private const val BOOT_PREVIEW_DURATION_MS = 1_000L
    private const val SERVER_SOUND_FILE_MARKER = "server_sound_"
    private const val HELLO_LOCAL_SOUND_FILE_NAME = "hello_custom.audio"
    private const val GOODBYE_LOCAL_SOUND_FILE_NAME = "goodbye_custom.audio"
    private const val HELLO_SERVER_SOUND_FILE_NAME = "server_hello.audio"
    private const val GOODBYE_SERVER_SOUND_FILE_NAME = "server_goodbye.audio"

    fun resolveStartDestination(hasPermissions: Boolean, isLoggedIn: Boolean?): String? {
        if (!hasPermissions) {
            return "permission"
        }

        return when (isLoggedIn) {
            null -> null
            true -> "main"
            false -> "login"
        }
    }

    fun shouldAutoPlayOnActivityOpen(
        hasPermissions: Boolean,
        isLoggedIn: Boolean?,
        playOnOpenEnabled: Boolean,
        alreadyPlayedInProcess: Boolean,
        launchIntentFlags: Int,
        hasSavedInstanceState: Boolean,
        deviceBootAgeMillis: Long = 0L
    ): Boolean {
        if (!hasPermissions || isLoggedIn != true || !playOnOpenEnabled || alreadyPlayedInProcess) {
            return false
        }

        if (hasSavedInstanceState) {
            return false
        }

        return launchIntentFlags and FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
    }

    fun shouldAttemptBootModeVisibleStartupRecovery(
        hasPermissions: Boolean,
        isLoggedIn: Boolean?,
        bootModeEnabled: Boolean,
        alreadyPlayedInProcess: Boolean,
        launchIntentFlags: Int,
        hasSavedInstanceState: Boolean,
        deviceBootAgeMillis: Long
    ): Boolean {
        if (!hasPermissions || isLoggedIn != true || !bootModeEnabled || alreadyPlayedInProcess) {
            return false
        }

        if (hasSavedInstanceState) {
            return false
        }

        if (launchIntentFlags and FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
            return false
        }

        return deviceBootAgeMillis <= BOOT_LIKE_VISIBLE_COMPATIBILITY_WINDOW_MS
    }

    fun shouldSuppressProblematicComposeHoverEvent(
        sdkInt: Int,
        actionMasked: Int,
        isMouseLikePointer: Boolean
    ): Boolean {
        // Mi A3 / Android 11 still surfaces the async Compose hover-exit crash, so keep
        // suppressing mouse-like hover input through R where the workaround is required.
        if (sdkInt > Build.VERSION_CODES.R || !isMouseLikePointer) {
            return false
        }

        return actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
            actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
            actionMasked == MotionEvent.ACTION_HOVER_EXIT
    }

    fun isProblematicComposeHoverCrash(throwable: Throwable): Boolean {
        return throwable is IllegalStateException &&
            throwable.message?.contains("ACTION_HOVER_EXIT event was not cleared") == true
    }

    fun resolveAppOpenAutoplayAction(
        activeRequest: PlaybackRequest?,
        isPlaying: Boolean,
        targetSoundIndex: Int
    ): AppOpenAutoplayAction {
        if (
            activeRequest?.intent != PlaybackRequestIntent.AUTOPLAY_START ||
            activeRequest.soundIndex != targetSoundIndex
        ) {
            return AppOpenAutoplayAction.START_NEW_REQUEST
        }

        if (isPlaying) {
            return AppOpenAutoplayAction.REUSE_EXISTING_AUTOPLAY
        }

        return AppOpenAutoplayAction.WAIT_FOR_PENDING_AUTOPLAY
    }

    fun evaluateBootLikeVisibleCompatStartup(
        startupMode: StartupMode,
        hasPermissions: Boolean,
        isLoggedIn: Boolean?,
        launchIntentFlags: Int,
        hasSavedInstanceState: Boolean,
        deviceBootAgeMillis: Long,
        startupWindowArmed: Boolean,
        bootReceiverSeenInCurrentCandidateWindow: Boolean,
        bootPlaybackOwnershipPresent: Boolean,
        hasPlayableSource: Boolean
    ): BootLikeVisibleCompatEvaluation {
        val visibleLaunchFresh =
            !hasSavedInstanceState && launchIntentFlags and FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0

        fun result(
            matches: Boolean,
            reason: BootLikeVisibleCompatReason
        ): BootLikeVisibleCompatEvaluation {
            return BootLikeVisibleCompatEvaluation(
                matches = matches,
                reason = reason,
                visibleLaunchElapsedRealtimeMillis = deviceBootAgeMillis,
                visibleLaunchFresh = visibleLaunchFresh,
                bootReceiverSeenInCurrentCandidateWindow = bootReceiverSeenInCurrentCandidateWindow
            )
        }

        if (startupMode != StartupMode.BOOT_COMPLETED) {
            return result(false, BootLikeVisibleCompatReason.MODE_NOT_BOOT_COMPLETED)
        }
        if (!hasPermissions) {
            return result(false, BootLikeVisibleCompatReason.MISSING_PERMISSIONS)
        }
        if (isLoggedIn != true) {
            return result(false, BootLikeVisibleCompatReason.NOT_LOGGED_IN)
        }
        if (launchIntentFlags and FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
            return result(false, BootLikeVisibleCompatReason.LAUNCH_FROM_HISTORY)
        }
        if (hasSavedInstanceState) {
            return result(false, BootLikeVisibleCompatReason.RESTORED_ACTIVITY)
        }
        if (bootReceiverSeenInCurrentCandidateWindow) {
            return result(false, BootLikeVisibleCompatReason.RECEIVER_ALREADY_SEEN)
        }
        if (startupWindowArmed) {
            return result(false, BootLikeVisibleCompatReason.STARTUP_WINDOW_ALREADY_ARMED)
        }
        if (bootPlaybackOwnershipPresent) {
            return result(false, BootLikeVisibleCompatReason.BOOT_PLAYBACK_OWNERSHIP_PRESENT)
        }
        if (deviceBootAgeMillis > BOOT_LIKE_VISIBLE_COMPATIBILITY_WINDOW_MS) {
            return result(false, BootLikeVisibleCompatReason.UPTIME_TOO_OLD)
        }
        if (!hasPlayableSource) {
            return result(false, BootLikeVisibleCompatReason.PLAYABLE_SOURCE_UNAVAILABLE)
        }

        return result(true, BootLikeVisibleCompatReason.SMALL_UPTIME_NO_RECEIVER)
    }

    fun resolveInitialIotStatus(cachedStatus: Boolean?): Boolean {
        return cachedStatus ?: true
    }

    fun shouldLaunchBootPreview(
        isUsageAllowed: Boolean,
        playOnOpenEnabled: Boolean
    ): Boolean {
        return isUsageAllowed && playOnOpenEnabled
    }

    fun bootPreviewDurationMillis(): Long = BOOT_PREVIEW_DURATION_MS

    fun calculateBootStartupWindowId(
        nowMillis: Long,
        windowMillis: Long = BOOT_STARTUP_WINDOW_MS
    ): Long {
        require(windowMillis > 0) { "windowMillis must be positive" }
        return nowMillis / windowMillis
    }

    fun isSameBootStartupWindow(
        firstSignalAtMillis: Long,
        secondSignalAtMillis: Long,
        windowMillis: Long = BOOT_STARTUP_WINDOW_MS
    ): Boolean {
        return calculateBootStartupWindowId(firstSignalAtMillis, windowMillis) ==
            calculateBootStartupWindowId(secondSignalAtMillis, windowMillis)
    }

    fun bootStartupWindowMillis(): Long = BOOT_STARTUP_WINDOW_MS

    fun headUnitSleepWakeStartupWindowMillis(): Long = HEAD_UNIT_SLEEP_WAKE_STARTUP_WINDOW_MS

    fun usbHostAttachStartupWindowMillis(): Long = USB_HOST_ATTACH_STARTUP_WINDOW_MS

    fun genericCompatibilityStartupWindowMillis(): Long = GENERIC_COMPATIBILITY_STARTUP_WINDOW_MS

    fun bootLikeVisibleCompatibilityWindowMillis(): Long = BOOT_LIKE_VISIBLE_COMPATIBILITY_WINDOW_MS

    fun appOpenStartupRecoveryWindowMillis(): Long = APP_OPEN_STARTUP_RECOVERY_WINDOW_MS

    fun isWithinCompatibilityStartupGraceWindow(
        armedAtMillis: Long,
        nowMillis: Long,
        windowMillis: Long
    ): Boolean {
        require(windowMillis > 0) { "windowMillis must be positive" }
        if (nowMillis < armedAtMillis) {
            return false
        }

        return nowMillis - armedAtMillis <= windowMillis
    }

    fun bootSessionStaleTimeoutMillis(): Long = BOOT_SESSION_STALE_TIMEOUT_MS

    fun isUsageAllowed(
        isLoggedIn: Boolean,
        demoExpirationTime: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        return isLoggedIn || (demoExpirationTime != null && nowMillis < demoExpirationTime)
    }

    fun shouldStartFloatingButton(
        isLoggedIn: Boolean,
        floatingButtonEnabled: Boolean,
        canDrawOverlays: Boolean
    ): Boolean {
        return isLoggedIn && floatingButtonEnabled && canDrawOverlays
    }

    fun fixedLocalSoundFileName(soundIndex: Int): String {
        return if (soundIndex == 2) {
            GOODBYE_LOCAL_SOUND_FILE_NAME
        } else {
            HELLO_LOCAL_SOUND_FILE_NAME
        }
    }

    fun fixedServerSoundFileName(soundIndex: Int): String {
        return if (soundIndex == 2) {
            GOODBYE_SERVER_SOUND_FILE_NAME
        } else {
            HELLO_SERVER_SOUND_FILE_NAME
        }
    }

    fun canDrawOverlaysCompat(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }

        if (Settings.canDrawOverlays(context)) {
            return true
        }

        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    Process.myUid(),
                    context.packageName
                )
            }

            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    fun isSoundMissing(soundUri: String?): Boolean {
        if (soundUri.isNullOrBlank()) {
            return true
        }

        return try {
            val uri = URI(soundUri)
            when (uri.scheme?.lowercase()) {
                "file" -> {
                    val file = File(uri.path ?: return true)
                    !file.exists() || file.length() < 2048
                }
                "content", "android.resource" -> false
                else -> false
            }
        } catch (_: Exception) {
            true
        }
    }

    fun shouldAutoDownloadSounds(soundUri1: String?, soundUri2: String?): Boolean {
        return isSoundMissing(soundUri1)
    }

    fun shouldReuseStoredSoundForUser(
        storedUri: String?,
        storedOwnerUserId: String?,
        currentUserId: String?,
        managedSoundDirectoryPath: String? = null
    ): Boolean {
        if (storedUri.isNullOrBlank()) {
            return false
        }

        if (isSoundMissing(storedUri)) {
            return false
        }

        if (!managedSoundDirectoryPath.isNullOrBlank() &&
            !isStoredSoundInsideManagedDirectory(storedUri, managedSoundDirectoryPath)
        ) {
            return false
        }

        if (!isServerManagedStoredSound(storedUri)) {
            return false
        }

        if (storedOwnerUserId.isNullOrBlank()) {
            return false
        }

        if (currentUserId.isNullOrBlank()) {
            return true
        }

        return storedOwnerUserId == currentUserId
    }

    fun shouldShowServerManagedSoundLabel(
        storedUri: String?,
        managedSoundDirectoryPath: String?
    ): Boolean {
        if (storedUri.isNullOrBlank() || managedSoundDirectoryPath.isNullOrBlank()) {
            return false
        }

        if (isSoundMissing(storedUri)) {
            return false
        }

        if (!isStoredSoundInsideManagedDirectory(storedUri, managedSoundDirectoryPath)) {
            return false
        }

        return isServerManagedStoredSound(storedUri)
    }

    private fun isStoredSoundInsideManagedDirectory(
        storedUri: String,
        managedSoundDirectoryPath: String
    ): Boolean {
        return try {
            val uri = URI(storedUri)
            if (uri.scheme?.lowercase() != "file") {
                return false
            }

            val managedDirectory = File(managedSoundDirectoryPath).canonicalFile
            val storedFile = File(uri).canonicalFile
            storedFile.path.startsWith(managedDirectory.path + File.separator) ||
                storedFile == managedDirectory
        } catch (_: Exception) {
            false
        }
    }

    private fun isServerManagedStoredSound(storedUri: String): Boolean {
        return try {
            val uri = URI(storedUri)
            if (uri.scheme?.lowercase() != "file") {
                return false
            }

            File(uri).name.contains(SERVER_SOUND_FILE_MARKER)
        } catch (_: Exception) {
            false
        }
    }

    fun shouldInvalidateStoredSoundsForUser(
        soundUri1: String?,
        soundUri2: String?,
        storedOwnerUserId: String?,
        currentUserId: String?
    ): Boolean {
        val hasStoredSound = !soundUri1.isNullOrBlank() || !soundUri2.isNullOrBlank()
        if (!hasStoredSound || currentUserId.isNullOrBlank()) {
            return false
        }

        if (storedOwnerUserId.isNullOrBlank()) {
            return false
        }

        return storedOwnerUserId != currentUserId
    }

    fun shouldClearStoredSoundsOnAccountChange(
        previousUserId: String?,
        nextUserId: String?,
        previousPhoneNumber: String?,
        nextPhoneNumber: String?
    ): Boolean {
        val hadPreviousIdentity = !previousUserId.isNullOrBlank() || !previousPhoneNumber.isNullOrBlank()
        if (!hadPreviousIdentity) {
            return false
        }

        val userChanged = !previousUserId.isNullOrBlank() &&
            !nextUserId.isNullOrBlank() &&
            previousUserId != nextUserId
        val phoneChanged = !previousPhoneNumber.isNullOrBlank() &&
            !nextPhoneNumber.isNullOrBlank() &&
            previousPhoneNumber != nextPhoneNumber

        return userChanged || phoneChanged
    }

    fun shouldIgnorePlaybackRequest(
        isPlaying: Boolean,
        isStartPending: Boolean,
        currentUri: String?,
        requestedUri: String?,
        forceReplay: Boolean = false
    ): Boolean {
        if (forceReplay) {
            return false
        }

        return (isPlaying || isStartPending) &&
            !requestedUri.isNullOrEmpty() &&
            currentUri == requestedUri
    }

    fun resolveFloatingButtonPlaybackAction(
        tappedSoundIndex: Int,
        isPlaying: Boolean,
        currentSoundIndex: Int?
    ): FloatingButtonPlaybackAction {
        return if (isPlaying && currentSoundIndex == tappedSoundIndex) {
            FloatingButtonPlaybackAction.STOP
        } else {
            FloatingButtonPlaybackAction.START
        }
    }

    fun shouldBroadcastPlaybackFinished(
        finishActivityOnCompletion: Boolean,
        terminationReason: PlaybackTerminationReason
    ): Boolean {
        return finishActivityOnCompletion && terminationReason == PlaybackTerminationReason.COMPLETED
    }

    fun defaultSoundResIdForIndex(soundIndex: Int?): Int {
        return if (soundIndex == 2) {
            R.raw.default_goodbye_sound
        } else {
            R.raw.default_startup_sound
        }
    }

    fun shouldPersistSoundLocally(sourceUri: String?): Boolean {
        if (sourceUri.isNullOrBlank()) {
            return false
        }

        return try {
            val scheme = URI(sourceUri.trim()).scheme?.lowercase()
            scheme !in setOf("file", "android.resource")
        } catch (_: Exception) {
            false
        }
    }

    fun nextPlaybackRecoveryStep(
        soundIndex: Int?,
        hasAttemptedRemoteRefresh: Boolean
    ): PlaybackRecoveryStep {
        if (soundIndex != null && !hasAttemptedRemoteRefresh) {
            return PlaybackRecoveryStep.REFETCH_FROM_SERVER
        }

        return PlaybackRecoveryStep.STOP_PLAYBACK
    }

    fun nextHeartbeatDelayMillis(consecutiveFailures: Int): Long {
        return when {
            consecutiveFailures <= 0 -> HEARTBEAT_NORMAL_DELAY_MS
            consecutiveFailures >= 3 -> HEARTBEAT_LONG_BACKOFF_MS
            else -> HEARTBEAT_RETRY_DELAY_MS
        }
    }

    fun shouldRunUpdateCheck(
        lastAttemptAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis(),
        minIntervalMillis: Long = FOREGROUND_UPDATE_THROTTLE_MS
    ): Boolean {
        return lastAttemptAtMillis == null || nowMillis - lastAttemptAtMillis >= minIntervalMillis
    }

    fun shouldShowRemoteSyncWarning(isRemoteSyncBlocked: Boolean): Boolean {
        return isRemoteSyncBlocked
    }

    fun displayNameFromStoredUri(storedUri: String?): String? {
        if (storedUri.isNullOrBlank()) {
            return null
        }

        return try {
            val uri = URI(storedUri.trim())
            val rawCandidate = when (uri.scheme?.lowercase()) {
                "file", "content", "android.resource" -> uri.path
                else -> storedUri
            } ?: storedUri

            rawCandidate
                .substringBefore('?')
                .substringBefore('#')
                .let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
                .substringAfterLast('/')
                .substringAfterLast(':')
                .trim()
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            storedUri
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/')
                .substringAfterLast(':')
                .trim()
                .takeIf { it.isNotEmpty() }
        }
    }

    fun shouldHandleBootInitAction(
        action: String,
        lastHandledAction: String?,
        lastHandledAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis(),
        dedupeWindowMillis: Long = BOOT_INIT_DEDUPE_WINDOW_MS
    ): Boolean {
        if (lastHandledAtMillis == null) {
            return true
        }

        if (nowMillis - lastHandledAtMillis > dedupeWindowMillis) {
            return true
        }

        if (action == lastHandledAction) {
            return false
        }

        return true
    }

    fun shouldRetryFloatingRestoreOnUnlockedBootCompleted(
        action: String,
        lastHandledAction: String?,
        lastHandledAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (
            action != "android.intent.action.BOOT_COMPLETED" ||
            lastHandledAction != "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {
            return false
        }

        return lastHandledAtMillis != null && nowMillis >= lastHandledAtMillis
    }

    fun normalizeTrustedDownloadUrl(downloadUrl: String?, apiBaseUrl: String): String? {
        if (downloadUrl.isNullOrBlank()) {
            return null
        }

        return try {
            val baseUri = URI(apiBaseUrl)
            val resolvedUri = baseUri.resolve(downloadUrl.trim()).normalize()
            val scheme = resolvedUri.scheme?.lowercase()

            if (scheme !in setOf("http", "https")) {
                return null
            }

            val baseHost = baseUri.host?.lowercase()
            val resolvedHost = resolvedUri.host?.lowercase()
            if (baseHost.isNullOrBlank() || resolvedHost.isNullOrBlank() || baseHost != resolvedHost) {
                return null
            }

            if (effectivePort(baseUri) != effectivePort(resolvedUri)) {
                return null
            }

            if (resolvedUri.path.isNullOrBlank()) {
                return null
            }

            resolvedUri.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun effectivePort(uri: URI): Int {
        if (uri.port != -1) {
            return uri.port
        }

        return when (uri.scheme?.lowercase()) {
            "https" -> 443
            else -> 80
        }
    }
}
