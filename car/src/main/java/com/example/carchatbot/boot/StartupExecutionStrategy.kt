package com.example.carchatbot.boot

/**
 * Kết quả execution cho một startup signal đã chuẩn hóa.
 */
enum class StartupExecutionStrategy {
    START_FOREGROUND_SERVICE_NOW,
    DEFER_UNTIL_PROCESS_VISIBLE
}

/**
 * Quyết định được truyền từ startup coordination sang bước launch service.
 *
 * `reason` được lưu trong diagnostics để support giải thích được vì sao BOOT
 * audio start ngay hoặc bị defer để recovery.
 */
data class StartupExecutionPlan(
    val strategy: StartupExecutionStrategy,
    val reason: String
) {
    /**
     * Cho biết plan này có được launch foreground service ngay không.
     */
    fun allowsForegroundServiceStart(): Boolean {
        return strategy == StartupExecutionStrategy.START_FOREGROUND_SERVICE_NOW
    }

    companion object {
        /**
         * Tạo plan execute ngay qua foreground service.
         */
        fun direct(reason: String): StartupExecutionPlan {
            return StartupExecutionPlan(
                strategy = StartupExecutionStrategy.START_FOREGROUND_SERVICE_NOW,
                reason = reason
            )
        }

        /**
         * Tạo plan defer, thường dùng khi profile yêu cầu đợi tín hiệu visible.
         */
        fun deferred(reason: String): StartupExecutionPlan {
            return StartupExecutionPlan(
                strategy = StartupExecutionStrategy.DEFER_UNTIL_PROCESS_VISIBLE,
                reason = reason
            )
        }
    }
}
