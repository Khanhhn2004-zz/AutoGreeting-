package com.example.carchatbot.utils

import com.example.carchatbot.data.remote.model.AppLogRequest

class PendingLogBuffer(private val maxSize: Int) {
    private val pending = ArrayDeque<AppLogRequest>()

    @Synchronized
    fun add(log: AppLogRequest) {
        pending.addLast(log)
        while (pending.size > maxSize) {
            pending.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): List<AppLogRequest> {
        return pending.toList()
    }

    @Synchronized
    fun acknowledge(delivered: List<AppLogRequest>) {
        if (delivered.isEmpty()) {
            return
        }

        val deliveredSet = delivered.toHashSet()
        val retained = pending.filterNot { it in deliveredSet }
        pending.clear()
        retained.forEach { pending.addLast(it) }
    }

    @Synchronized
    fun clear() {
        pending.clear()
    }
}
