package com.example.carchatbot

import android.view.MotionEvent
import com.example.carchatbot.R
import com.example.carchatbot.boot.StartupMode
import com.example.carchatbot.runtime.AppOpenAutoplayAction
import com.example.carchatbot.runtime.AppRuntimePolicies
import com.example.carchatbot.runtime.BootLikeVisibleCompatReason
import com.example.carchatbot.runtime.FloatingButtonPlaybackAction
import com.example.carchatbot.runtime.PlaybackRecoveryStep
import com.example.carchatbot.runtime.PlaybackTerminationReason
import com.example.carchatbot.service.PlaybackRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class AppRuntimePoliciesTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `resolveStartDestination waits for session when permissions are already granted`() {
        assertNull(
            AppRuntimePolicies.resolveStartDestination(
                hasPermissions = true,
                isLoggedIn = null
            )
        )
    }

    @Test
    fun `resolveStartDestination routes to login after session is known`() {
        assertEquals(
            "login",
            AppRuntimePolicies.resolveStartDestination(
                hasPermissions = true,
                isLoggedIn = false
            )
        )
    }

    @Test
    fun `autoplay is skipped when activity is restored from history`() {
        assertFalse(
            AppRuntimePolicies.shouldAutoPlayOnActivityOpen(
                hasPermissions = true,
                isLoggedIn = true,
                playOnOpenEnabled = true,
                alreadyPlayedInProcess = false,
                launchIntentFlags = 0x00100000,
                hasSavedInstanceState = true
            )
        )
    }

    @Test
    fun `autoplay runs only for a fresh launcher open`() {
        assertTrue(
            AppRuntimePolicies.shouldAutoPlayOnActivityOpen(
                hasPermissions = true,
                isLoggedIn = true,
                playOnOpenEnabled = true,
                alreadyPlayedInProcess = false,
                launchIntentFlags = 0,
                hasSavedInstanceState = false,
                deviceBootAgeMillis = 5_000L
            )
        )
    }

    @Test
    fun `explicit app open autoplay is still allowed long after device boot`() {
        assertTrue(
            AppRuntimePolicies.shouldAutoPlayOnActivityOpen(
                hasPermissions = true,
                isLoggedIn = true,
                playOnOpenEnabled = true,
                alreadyPlayedInProcess = false,
                launchIntentFlags = 0,
                hasSavedInstanceState = false,
                deviceBootAgeMillis = AppRuntimePolicies.appOpenStartupRecoveryWindowMillis() + 1L
            )
        )
    }

    @Test
    fun `boot mode visible startup recovery is allowed only for fresh launches near device boot`() {
        assertTrue(
            AppRuntimePolicies.shouldAttemptBootModeVisibleStartupRecovery(
                hasPermissions = true,
                isLoggedIn = true,
                bootModeEnabled = true,
                alreadyPlayedInProcess = false,
                launchIntentFlags = 0,
                hasSavedInstanceState = false,
                deviceBootAgeMillis = AppRuntimePolicies.bootLikeVisibleCompatibilityWindowMillis()
            )
        )

        assertFalse(
            AppRuntimePolicies.shouldAttemptBootModeVisibleStartupRecovery(
                hasPermissions = true,
                isLoggedIn = true,
                bootModeEnabled = true,
                alreadyPlayedInProcess = false,
                launchIntentFlags = 0,
                hasSavedInstanceState = false,
                deviceBootAgeMillis = AppRuntimePolicies.bootLikeVisibleCompatibilityWindowMillis() + 1L
            )
        )

        assertFalse(
            AppRuntimePolicies.shouldAttemptBootModeVisibleStartupRecovery(
                hasPermissions = true,
                isLoggedIn = true,
                bootModeEnabled = true,
                alreadyPlayedInProcess = false,
                launchIntentFlags = 0x00100000,
                hasSavedInstanceState = false,
                deviceBootAgeMillis = 5_000L
            )
        )
    }

    @Test
    fun `boot visible startup recovery tolerates slow automotive launcher startup`() {
        assertTrue(AppRuntimePolicies.bootLikeVisibleCompatibilityWindowMillis() >= 5 * 60 * 1000L)
        assertTrue(
            AppRuntimePolicies.shouldAttemptBootModeVisibleStartupRecovery(
                hasPermissions = true,
                isLoggedIn = true,
                bootModeEnabled = true,
                alreadyPlayedInProcess = false,
                launchIntentFlags = 0,
                hasSavedInstanceState = false,
                deviceBootAgeMillis = 5 * 60 * 1000L
            )
        )
    }

    @Test
    fun `app open autoplay waits while boot autoplay is still pending for the same slot`() {
        assertEquals(
            AppOpenAutoplayAction.WAIT_FOR_PENDING_AUTOPLAY,
            AppRuntimePolicies.resolveAppOpenAutoplayAction(
                activeRequest = PlaybackRequest.autoplayStart(soundIndex = 1),
                isPlaying = false,
                targetSoundIndex = 1
            )
        )
    }

    @Test
    fun `app open autoplay reuses an autoplay already playing for the same slot`() {
        assertEquals(
            AppOpenAutoplayAction.REUSE_EXISTING_AUTOPLAY,
            AppRuntimePolicies.resolveAppOpenAutoplayAction(
                activeRequest = PlaybackRequest.autoplayStart(soundIndex = 1),
                isPlaying = true,
                targetSoundIndex = 1
            )
        )
    }

    @Test
    fun `app open autoplay starts a fresh request when pending playback targets another slot`() {
        assertEquals(
            AppOpenAutoplayAction.START_NEW_REQUEST,
            AppRuntimePolicies.resolveAppOpenAutoplayAction(
                activeRequest = PlaybackRequest.autoplayStart(soundIndex = 2),
                isPlaying = false,
                targetSoundIndex = 1
            )
        )
    }

    @Test
    fun `initial iot status uses cached value when available`() {
        assertFalse(AppRuntimePolicies.resolveInitialIotStatus(cachedStatus = false))
    }

    @Test
    fun `initial iot status defaults to true when cache is empty`() {
        assertTrue(AppRuntimePolicies.resolveInitialIotStatus(cachedStatus = null))
    }

    @Test
    fun `boot preview is enabled only when usage allowed and autoplay enabled`() {
        assertTrue(
            AppRuntimePolicies.shouldLaunchBootPreview(
                isUsageAllowed = true,
                playOnOpenEnabled = true
            )
        )
    }

    @Test
    fun `boot preview is disabled when autoplay is off`() {
        assertFalse(
            AppRuntimePolicies.shouldLaunchBootPreview(
                isUsageAllowed = true,
                playOnOpenEnabled = false
            )
        )
    }

    @Test
    fun `boot preview is disabled when usage is not allowed`() {
        assertFalse(
            AppRuntimePolicies.shouldLaunchBootPreview(
                isUsageAllowed = false,
                playOnOpenEnabled = true
            )
        )
    }

    @Test
    fun `boot preview duration remains one second`() {
        assertEquals(1_000L, AppRuntimePolicies.bootPreviewDurationMillis())
    }

    @Test
    fun `default sound resources include startup and goodbye bundled audio`() {
        assertEquals(R.raw.default_startup_sound, AppRuntimePolicies.defaultSoundResIdForIndex(1))
        assertEquals(R.raw.default_goodbye_sound, AppRuntimePolicies.defaultSoundResIdForIndex(2))
    }

    @Test
    fun `compatibility startup windows widen from cold boot to sleep wake to usb host attach`() {
        assertTrue(
            AppRuntimePolicies.headUnitSleepWakeStartupWindowMillis() >
                AppRuntimePolicies.bootStartupWindowMillis()
        )
        assertTrue(
            AppRuntimePolicies.usbHostAttachStartupWindowMillis() >=
                AppRuntimePolicies.headUnitSleepWakeStartupWindowMillis()
        )
        assertTrue(
            AppRuntimePolicies.genericCompatibilityStartupWindowMillis() >=
                AppRuntimePolicies.bootStartupWindowMillis()
        )
    }

    @Test
    fun `compatibility recovery grace is based on armed time rather than absolute window boundaries`() {
        assertTrue(
            AppRuntimePolicies.isWithinCompatibilityStartupGraceWindow(
                armedAtMillis = 59_000L,
                nowMillis = 61_000L,
                windowMillis = 60_000L
            )
        )
        assertFalse(
            AppRuntimePolicies.isWithinCompatibilityStartupGraceWindow(
                armedAtMillis = 59_000L,
                nowMillis = 119_001L,
                windowMillis = 60_000L
            )
        )
    }

    @Test
    fun `boot-like visible compat startup is detected only for a fresh boot-completed launch with no prior startup state`() {
        val result = AppRuntimePolicies.evaluateBootLikeVisibleCompatStartup(
            startupMode = StartupMode.BOOT_COMPLETED,
            hasPermissions = true,
            isLoggedIn = true,
            launchIntentFlags = 0,
            hasSavedInstanceState = false,
            deviceBootAgeMillis = 45_000L,
            startupWindowArmed = false,
            bootReceiverSeenInCurrentCandidateWindow = false,
            bootPlaybackOwnershipPresent = false,
            hasPlayableSource = true
        )

        assertTrue(result.matches)
        assertEquals(BootLikeVisibleCompatReason.SMALL_UPTIME_NO_RECEIVER, result.reason)
        assertTrue(result.visibleLaunchFresh)
        assertFalse(result.bootReceiverSeenInCurrentCandidateWindow)
        assertEquals(45_000L, result.visibleLaunchElapsedRealtimeMillis)
    }

    @Test
    fun `boot-like visible compat startup is rejected when a receiver startup window already exists`() {
        val result = AppRuntimePolicies.evaluateBootLikeVisibleCompatStartup(
            startupMode = StartupMode.BOOT_COMPLETED,
            hasPermissions = true,
            isLoggedIn = true,
            launchIntentFlags = 0,
            hasSavedInstanceState = false,
            deviceBootAgeMillis = 30_000L,
            startupWindowArmed = true,
            bootReceiverSeenInCurrentCandidateWindow = true,
            bootPlaybackOwnershipPresent = false,
            hasPlayableSource = true
        )

        assertFalse(result.matches)
        assertEquals(BootLikeVisibleCompatReason.RECEIVER_ALREADY_SEEN, result.reason)
        assertTrue(result.bootReceiverSeenInCurrentCandidateWindow)
    }

    @Test
    fun `boot-like visible compat startup is rejected when launch is too old or source is not playable`() {
        val tooOld = AppRuntimePolicies.evaluateBootLikeVisibleCompatStartup(
            startupMode = StartupMode.BOOT_COMPLETED,
            hasPermissions = true,
            isLoggedIn = true,
            launchIntentFlags = 0,
            hasSavedInstanceState = false,
            deviceBootAgeMillis = AppRuntimePolicies.bootLikeVisibleCompatibilityWindowMillis() + 1L,
            startupWindowArmed = false,
            bootReceiverSeenInCurrentCandidateWindow = false,
            bootPlaybackOwnershipPresent = false,
            hasPlayableSource = true
        )
        val missingSource = AppRuntimePolicies.evaluateBootLikeVisibleCompatStartup(
            startupMode = StartupMode.BOOT_COMPLETED,
            hasPermissions = true,
            isLoggedIn = true,
            launchIntentFlags = 0,
            hasSavedInstanceState = false,
            deviceBootAgeMillis = 30_000L,
            startupWindowArmed = false,
            bootReceiverSeenInCurrentCandidateWindow = false,
            bootPlaybackOwnershipPresent = false,
            hasPlayableSource = false
        )

        assertFalse(tooOld.matches)
        assertEquals(BootLikeVisibleCompatReason.UPTIME_TOO_OLD, tooOld.reason)
        assertFalse(missingSource.matches)
        assertEquals(BootLikeVisibleCompatReason.PLAYABLE_SOURCE_UNAVAILABLE, missingSource.reason)
    }

    @Test
    fun `runtime policies declare fixed local and server filenames for each slot`() {
        val source = readSource("src/main/java/com/example/carchatbot/runtime/AppRuntimePolicies.kt")

        assertTrue(source.contains("hello_custom.audio"))
        assertTrue(source.contains("goodbye_custom.audio"))
        assertTrue(source.contains("server_hello.audio"))
        assertTrue(source.contains("server_goodbye.audio"))
    }

    @Test
    fun `stored sound is not reused when owner differs from current user`() {
        val tempFile = Files.createTempFile("sound", ".mp3").toFile()
        tempFile.writeBytes(ByteArray(4096) { 1 })

        assertFalse(
            AppRuntimePolicies.shouldReuseStoredSoundForUser(
                storedUri = tempFile.toURI().toString(),
                storedOwnerUserId = "user-a",
                currentUserId = "user-b"
            )
        )
    }

    @Test
    fun `stored sound without owner metadata is still reused when local file is valid`() {
        val tempFile = Files.createTempFile("sound", ".mp3").toFile()
        tempFile.writeBytes(ByteArray(4096) { 1 })

        assertFalse(
            AppRuntimePolicies.shouldReuseStoredSoundForUser(
                storedUri = tempFile.toURI().toString(),
                storedOwnerUserId = null,
                currentUserId = "user-a"
            )
        )
    }

    @Test
    fun `stored sound is reused only when it is inside managed sound storage directory`() {
        val tempDir = Files.createTempDirectory("sound-assets-v2").toFile()
        val managedFile = tempDir.resolve("user_user-a_server_sound_1_sound.mp3")
        managedFile.writeBytes(ByteArray(4096) { 1 })

        assertTrue(
            AppRuntimePolicies.shouldReuseStoredSoundForUser(
                storedUri = managedFile.toURI().toString(),
                storedOwnerUserId = "user-a",
                currentUserId = "user-a",
                managedSoundDirectoryPath = tempDir.absolutePath
            )
        )
    }

    @Test
    fun `stored sound is reused only when owner matches current user and file exists`() {
        val tempDir = Files.createTempDirectory("sound-assets-v2").toFile()
        val tempFile = tempDir.resolve("user_user-a_server_sound_1_hello.mp3")
        tempFile.writeBytes(ByteArray(4096) { 1 })

        assertTrue(
            AppRuntimePolicies.shouldReuseStoredSoundForUser(
                storedUri = tempFile.toURI().toString(),
                storedOwnerUserId = "user-a",
                currentUserId = "user-a",
                managedSoundDirectoryPath = tempDir.absolutePath
            )
        )
    }

    @Test
    fun `stored sound is rejected when it was imported locally instead of downloaded from server`() {
        val tempDir = Files.createTempDirectory("sound-assets-v2").toFile()
        val importedFile = tempDir.resolve("user_user-a_sound_1_custom.mp3")
        importedFile.writeBytes(ByteArray(4096) { 1 })

        assertFalse(
            AppRuntimePolicies.shouldReuseStoredSoundForUser(
                storedUri = importedFile.toURI().toString(),
                storedOwnerUserId = "user-a",
                currentUserId = "user-a",
                managedSoundDirectoryPath = tempDir.absolutePath
            )
        )
    }

    @Test
    fun `server sound label is shown only for valid managed server file`() {
        val tempDir = Files.createTempDirectory("sound-assets-v2").toFile()
        val managedFile = tempDir.resolve("user_user-a_server_sound_1_hello.mp3")
        managedFile.writeBytes(ByteArray(4096) { 1 })

        assertTrue(
            AppRuntimePolicies.shouldShowServerManagedSoundLabel(
                storedUri = managedFile.toURI().toString(),
                managedSoundDirectoryPath = tempDir.absolutePath
            )
        )
    }

    @Test
    fun `server sound label is hidden when managed file is missing`() {
        val tempDir = Files.createTempDirectory("sound-assets-v2").toFile()
        val missingFile = tempDir.resolve("user_user-a_server_sound_1_hello.mp3")

        assertFalse(
            AppRuntimePolicies.shouldShowServerManagedSoundLabel(
                storedUri = missingFile.toURI().toString(),
                managedSoundDirectoryPath = tempDir.absolutePath
            )
        )
    }

    @Test
    fun `stored sounds are cleared when login switches to a different user`() {
        assertTrue(
            AppRuntimePolicies.shouldClearStoredSoundsOnAccountChange(
                previousUserId = "user-a",
                nextUserId = "user-b",
                previousPhoneNumber = "0908000000",
                nextPhoneNumber = "0908111111"
            )
        )
    }

    @Test
    fun `stored sounds are kept when login stays on the same user`() {
        assertFalse(
            AppRuntimePolicies.shouldClearStoredSoundsOnAccountChange(
                previousUserId = "user-a",
                nextUserId = "user-a",
                previousPhoneNumber = "0908000000",
                nextPhoneNumber = "0908000000"
            )
        )
    }

    @Test
    fun `stored sounds are kept when there was no previous account`() {
        assertFalse(
            AppRuntimePolicies.shouldClearStoredSoundsOnAccountChange(
                previousUserId = null,
                nextUserId = "user-a",
                previousPhoneNumber = null,
                nextPhoneNumber = "0908000000"
            )
        )
    }

    @Test
    fun `indexed playback retries server refresh before default`() {
        assertEquals(
            PlaybackRecoveryStep.REFETCH_FROM_SERVER,
            AppRuntimePolicies.nextPlaybackRecoveryStep(
                soundIndex = 1,
                hasAttemptedRemoteRefresh = false
            )
        )
    }

    @Test
    fun `indexed playback stops gracefully after remote refresh already failed`() {
        assertEquals(
            PlaybackRecoveryStep.STOP_PLAYBACK,
            AppRuntimePolicies.nextPlaybackRecoveryStep(
                soundIndex = 1,
                hasAttemptedRemoteRefresh = true
            )
        )
    }

    @Test
    fun `direct resource playback stops instead of falling back to bundled default`() {
        assertEquals(
            PlaybackRecoveryStep.STOP_PLAYBACK,
            AppRuntimePolicies.nextPlaybackRecoveryStep(
                soundIndex = null,
                hasAttemptedRemoteRefresh = false
            )
        )
    }

    @Test
    fun `floating button never restarts when user disabled it`() {
        assertFalse(
            AppRuntimePolicies.shouldStartFloatingButton(
                isLoggedIn = true,
                floatingButtonEnabled = false,
                canDrawOverlays = true
            )
        )
    }

    @Test
    fun `content uri is treated as an existing sound`() {
        assertFalse(AppRuntimePolicies.isSoundMissing("content://media/external/audio/media/42"))
    }

    @Test
    fun `small file uri is treated as missing sound`() {
        val tempFile = Files.createTempFile("sound", ".mp3").toFile()
        tempFile.writeBytes(ByteArray(128))

        assertTrue(AppRuntimePolicies.isSoundMissing(tempFile.toURI().toString()))
    }

    @Test
    fun `goodbye missing alone does not trigger background auto download`() {
        val helloFile = Files.createTempFile("hello", ".mp3").toFile()
        helloFile.writeBytes(ByteArray(4096) { 1 })

        assertFalse(
            AppRuntimePolicies.shouldAutoDownloadSounds(
                soundUri1 = helloFile.toURI().toString(),
                soundUri2 = null
            )
        )
    }

    @Test
    fun `duplicate playback request is ignored instead of stopping current sound`() {
        assertTrue(
            AppRuntimePolicies.shouldIgnorePlaybackRequest(
                isPlaying = true,
                isStartPending = false,
                currentUri = "file:///hello.mp3",
                requestedUri = "file:///hello.mp3"
            )
        )
    }

    @Test
    fun `duplicate playback request is ignored while matching uri is still preparing`() {
        assertTrue(
            AppRuntimePolicies.shouldIgnorePlaybackRequest(
                isPlaying = false,
                isStartPending = true,
                currentUri = "file:///hello.mp3",
                requestedUri = "file:///hello.mp3"
            )
        )
    }

    @Test
    fun `force replay request is never ignored`() {
        assertFalse(
            AppRuntimePolicies.shouldIgnorePlaybackRequest(
                isPlaying = true,
                isStartPending = true,
                currentUri = "file:///hello.mp3",
                requestedUri = "file:///hello.mp3",
                forceReplay = true
            )
        )
    }

    @Test
    fun `floating button stops when tapping the same sound during playback`() {
        assertEquals(
            FloatingButtonPlaybackAction.STOP,
            AppRuntimePolicies.resolveFloatingButtonPlaybackAction(
                tappedSoundIndex = 1,
                isPlaying = true,
                currentSoundIndex = 1
            )
        )
    }

    @Test
    fun `floating button starts when tapping a different sound during playback`() {
        assertEquals(
            FloatingButtonPlaybackAction.START,
            AppRuntimePolicies.resolveFloatingButtonPlaybackAction(
                tappedSoundIndex = 2,
                isPlaying = true,
                currentSoundIndex = 1
            )
        )
    }

    @Test
    fun `explicit stop does not finish activity even when playback was started from app open`() {
        assertFalse(
            AppRuntimePolicies.shouldBroadcastPlaybackFinished(
                finishActivityOnCompletion = true,
                terminationReason = PlaybackTerminationReason.EXPLICIT_STOP
            )
        )
    }

    @Test
    fun `natural completion still finishes activity when playback was started from app open`() {
        assertTrue(
            AppRuntimePolicies.shouldBroadcastPlaybackFinished(
                finishActivityOnCompletion = true,
                terminationReason = PlaybackTerminationReason.COMPLETED
            )
        )
    }

    @Test
    fun `heartbeat backs off to five minutes after first failure`() {
        assertEquals(5 * 60 * 1000L, AppRuntimePolicies.nextHeartbeatDelayMillis(consecutiveFailures = 1))
    }

    @Test
    fun `heartbeat backs off to fifteen minutes after repeated failures`() {
        assertEquals(15 * 60 * 1000L, AppRuntimePolicies.nextHeartbeatDelayMillis(consecutiveFailures = 3))
    }

    @Test
    fun `update check is throttled when last request was recent`() {
        assertFalse(
            AppRuntimePolicies.shouldRunUpdateCheck(
                lastAttemptAtMillis = 1_000L,
                nowMillis = 1_000L + 10 * 60 * 1000L
            )
        )
    }

    @Test
    fun `update check runs when throttle window has elapsed`() {
        assertTrue(
            AppRuntimePolicies.shouldRunUpdateCheck(
                lastAttemptAtMillis = 1_000L,
                nowMillis = 1_000L + 31 * 60 * 1000L
            )
        )
    }

    @Test
    fun `reauth warning is shown only when remote sync is blocked`() {
        assertTrue(AppRuntimePolicies.shouldShowRemoteSyncWarning(isRemoteSyncBlocked = true))
        assertFalse(AppRuntimePolicies.shouldShowRemoteSyncWarning(isRemoteSyncBlocked = false))
    }

    @Test
    fun `trusted download url accepts same-host absolute url`() {
        assertEquals(
            "http://103.118.28.117/files/app-release.apk",
            AppRuntimePolicies.normalizeTrustedDownloadUrl(
                downloadUrl = "http://103.118.28.117/files/app-release.apk",
                apiBaseUrl = "http://103.118.28.117/api/"
            )
        )
    }

    @Test
    fun `trusted download url resolves relative path against backend host`() {
        assertEquals(
            "http://103.118.28.117/api/files/app-release.apk",
            AppRuntimePolicies.normalizeTrustedDownloadUrl(
                downloadUrl = "files/app-release.apk",
                apiBaseUrl = "http://103.118.28.117/api/"
            )
        )
    }

    @Test
    fun `trusted download url rejects different host`() {
        assertNull(
            AppRuntimePolicies.normalizeTrustedDownloadUrl(
                downloadUrl = "https://evil.example/app-release.apk",
                apiBaseUrl = "http://103.118.28.117/api/"
            )
        )
    }

    @Test
    fun `display name is decoded from saved file uri`() {
        assertEquals(
            "Hỗ trợ xin chào.mp3",
            AppRuntimePolicies.displayNameFromStoredUri(
                "file:///storage/emulated/0/Android/data/com.example.carchatbot/files/H%E1%BB%97%20tr%E1%BB%A3%20xin%20ch%C3%A0o.mp3"
            )
        )
    }

    @Test
    fun `display name extracts readable filename from saf uri`() {
        assertEquals(
            "goodbye sound.mp3",
            AppRuntimePolicies.displayNameFromStoredUri(
                "content://com.android.providers.media.documents/document/primary%3AMusic%2Fgoodbye%20sound.mp3"
            )
        )
    }

    @Test
    fun `boot completed is handled as post locked boot recovery when locked boot was handled moments ago`() {
        assertTrue(
            AppRuntimePolicies.shouldHandleBootInitAction(
                action = "android.intent.action.BOOT_COMPLETED",
                lastHandledAction = "android.intent.action.LOCKED_BOOT_COMPLETED",
                lastHandledAtMillis = 10_000L,
                nowMillis = 15_000L
            )
        )
    }

    @Test
    fun `same boot action is ignored when it repeats inside dedupe window`() {
        assertFalse(
            AppRuntimePolicies.shouldHandleBootInitAction(
                action = "android.intent.action.BOOT_COMPLETED",
                lastHandledAction = "android.intent.action.BOOT_COMPLETED",
                lastHandledAtMillis = 10_000L,
                nowMillis = 12_000L
            )
        )
    }

    @Test
    fun `same boot action is handled again after dedupe window expires`() {
        assertTrue(
            AppRuntimePolicies.shouldHandleBootInitAction(
                action = "android.intent.action.BOOT_COMPLETED",
                lastHandledAction = "android.intent.action.BOOT_COMPLETED",
                lastHandledAtMillis = 10_000L,
                nowMillis = 30_001L
            )
        )
    }

    @Test
    fun `boot completed stays handled after locked boot even when second signal arrives late`() {
        assertTrue(
            AppRuntimePolicies.shouldHandleBootInitAction(
                action = "android.intent.action.BOOT_COMPLETED",
                lastHandledAction = "android.intent.action.LOCKED_BOOT_COMPLETED",
                lastHandledAtMillis = 10_000L,
                nowMillis = 30_001L
            )
        )
    }

    @Test
    fun `boot startup window helper groups near simultaneous startup signals`() {
        assertTrue(AppRuntimePolicies.isSameBootStartupWindow(1_000L, 14_999L))
        assertFalse(AppRuntimePolicies.isSameBootStartupWindow(1_000L, 15_000L))
        assertEquals(
            AppRuntimePolicies.calculateBootStartupWindowId(1_000L),
            AppRuntimePolicies.calculateBootStartupWindowId(14_999L)
        )
    }

    @Test
    fun `hover safeguard suppresses mouse like hover actions through Android 11`() {
        assertTrue(
            AppRuntimePolicies.shouldSuppressProblematicComposeHoverEvent(
                sdkInt = 29,
                actionMasked = MotionEvent.ACTION_HOVER_EXIT,
                isMouseLikePointer = true
            )
        )
        assertTrue(
            AppRuntimePolicies.shouldSuppressProblematicComposeHoverEvent(
                sdkInt = 29,
                actionMasked = MotionEvent.ACTION_HOVER_MOVE,
                isMouseLikePointer = true
            )
        )
        assertTrue(
            AppRuntimePolicies.shouldSuppressProblematicComposeHoverEvent(
                sdkInt = 30,
                actionMasked = MotionEvent.ACTION_HOVER_EXIT,
                isMouseLikePointer = true
            )
        )
        assertFalse(
            AppRuntimePolicies.shouldSuppressProblematicComposeHoverEvent(
                sdkInt = 31,
                actionMasked = MotionEvent.ACTION_HOVER_EXIT,
                isMouseLikePointer = true
            )
        )
        assertFalse(
            AppRuntimePolicies.shouldSuppressProblematicComposeHoverEvent(
                sdkInt = 29,
                actionMasked = MotionEvent.ACTION_DOWN,
                isMouseLikePointer = true
            )
        )
        assertFalse(
            AppRuntimePolicies.shouldSuppressProblematicComposeHoverEvent(
                sdkInt = 29,
                actionMasked = MotionEvent.ACTION_HOVER_EXIT,
                isMouseLikePointer = false
            )
        )
    }

    @Test
    fun `boot completed reattempts floating restore when locked boot was handled moments ago`() {
        assertTrue(
            AppRuntimePolicies.shouldRetryFloatingRestoreOnUnlockedBootCompleted(
                action = "android.intent.action.BOOT_COMPLETED",
                lastHandledAction = "android.intent.action.LOCKED_BOOT_COMPLETED",
                lastHandledAtMillis = 10_000L,
                nowMillis = 15_000L
            )
        )
    }

    @Test
    fun `same boot completed duplicate does not trigger floating restore retry`() {
        assertFalse(
            AppRuntimePolicies.shouldRetryFloatingRestoreOnUnlockedBootCompleted(
                action = "android.intent.action.BOOT_COMPLETED",
                lastHandledAction = "android.intent.action.BOOT_COMPLETED",
                lastHandledAtMillis = 10_000L,
                nowMillis = 15_000L
            )
        )
    }

    @Test
    fun `hover safeguard recognizes compose hover exit crash signature`() {
        assertTrue(
            AppRuntimePolicies.isProblematicComposeHoverCrash(
                IllegalStateException("The ACTION_HOVER_EXIT event was not cleared.")
            )
        )
        assertFalse(
            AppRuntimePolicies.isProblematicComposeHoverCrash(
                IllegalStateException("Some other compose failure")
            )
        )
    }

    @Test
    fun `core service notification does not use adaptive launcher icon`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/CoreService.kt")

        assertFalse(source.contains(".setSmallIcon(R.mipmap.ic_launcher)"))
        assertTrue(source.contains(".setSmallIcon(R.drawable.ic_notifications)"))
    }

    @Test
    fun `sound player notification does not use adaptive launcher icon`() {
        val source = readSource("src/main/java/com/example/carchatbot/service/SoundPlayerService.kt")

        assertFalse(source.contains(".setSmallIcon(R.mipmap.ic_launcher)"))
        assertTrue(source.contains(".setSmallIcon(R.drawable.ic_notifications)"))
    }
}
