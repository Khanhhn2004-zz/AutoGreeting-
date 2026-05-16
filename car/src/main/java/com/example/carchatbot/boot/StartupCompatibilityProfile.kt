package com.example.carchatbot.boot

import com.example.carchatbot.runtime.AppRuntimePolicies

/**
 * Các loại tín hiệu có thể dùng để chứng minh một lần startup/boot-like.
 */
enum class StartupCompatibilityTrigger {
    DIRECT_BOOT_RECEIVER,
    POST_UNLOCK_RECEIVER,
    PROCESS_VISIBLE,
    HOST_VISIBLE_ATTACH
}

/**
 * Profile runtime mô tả mức độ tin cậy và cách execute trên từng nhóm thiết bị.
 *
 * Profile giúp BOOT branch không xử lý mọi Android box giống nhau: có thiết bị
 * phát được ngay từ receiver, có thiết bị phải đợi process visible mới ổn định.
 */
enum class StartupCompatibilityProfile(
    val trustedTriggers: Set<StartupCompatibilityTrigger>,
    val startupWindowMillis: Long,
    val attemptsDirectBootExecution: Boolean,
    val requiresVisibleExecutionSignal: Boolean
) {
    COLD_BOOT_ONLY(
        trustedTriggers = setOf(
            StartupCompatibilityTrigger.DIRECT_BOOT_RECEIVER,
            StartupCompatibilityTrigger.POST_UNLOCK_RECEIVER
        ),
        startupWindowMillis = AppRuntimePolicies.bootStartupWindowMillis(),
        attemptsDirectBootExecution = true,
        requiresVisibleExecutionSignal = false
    ),
    HEAD_UNIT_SLEEP_WAKE(
        trustedTriggers = setOf(
            StartupCompatibilityTrigger.DIRECT_BOOT_RECEIVER,
            StartupCompatibilityTrigger.POST_UNLOCK_RECEIVER,
            StartupCompatibilityTrigger.PROCESS_VISIBLE
        ),
        startupWindowMillis = AppRuntimePolicies.headUnitSleepWakeStartupWindowMillis(),
        attemptsDirectBootExecution = true,
        requiresVisibleExecutionSignal = false
    ),
    USB_BOX_HOST_ATTACH(
        trustedTriggers = setOf(
            StartupCompatibilityTrigger.PROCESS_VISIBLE,
            StartupCompatibilityTrigger.HOST_VISIBLE_ATTACH
        ),
        startupWindowMillis = AppRuntimePolicies.usbHostAttachStartupWindowMillis(),
        attemptsDirectBootExecution = false,
        requiresVisibleExecutionSignal = true
    ),
    UNKNOWN_GENERIC(
        trustedTriggers = setOf(
            StartupCompatibilityTrigger.DIRECT_BOOT_RECEIVER,
            StartupCompatibilityTrigger.POST_UNLOCK_RECEIVER,
            StartupCompatibilityTrigger.PROCESS_VISIBLE
        ),
        startupWindowMillis = AppRuntimePolicies.genericCompatibilityStartupWindowMillis(),
        attemptsDirectBootExecution = false,
        requiresVisibleExecutionSignal = true
    );

    /**
     * Kiểm tra trigger có được profile này tin cậy hay không.
     */
    fun supports(trigger: StartupCompatibilityTrigger): Boolean {
        return trigger in trustedTriggers
    }

    companion object {
        val DEFAULT = UNKNOWN_GENERIC
    }
}
