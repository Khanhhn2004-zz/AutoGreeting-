package com.example.carchatbot

import com.example.carchatbot.data.remote.model.AppLogRequest
import com.example.carchatbot.utils.PendingLogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingLogBufferTest {

    @Test
    fun `snapshot keeps logs until acknowledged`() {
        val buffer = PendingLogBuffer(maxSize = 10)
        val log = AppLogRequest(type = "LOG", tag = "Core", message = "hello", createdAt = 1L)

        buffer.add(log)
        val snapshot = buffer.snapshot()

        assertEquals(listOf(log), snapshot)
        assertEquals(listOf(log), buffer.snapshot())
    }

    @Test
    fun `acknowledge removes only delivered logs`() {
        val buffer = PendingLogBuffer(maxSize = 10)
        val first = AppLogRequest(type = "LOG", tag = "Core", message = "first", createdAt = 1L)
        val second = AppLogRequest(type = "LOG", tag = "Core", message = "second", createdAt = 2L)

        buffer.add(first)
        buffer.add(second)
        buffer.acknowledge(listOf(first))

        assertEquals(listOf(second), buffer.snapshot())
    }

    @Test
    fun `buffer drops oldest entries past max size`() {
        val buffer = PendingLogBuffer(maxSize = 2)
        val first = AppLogRequest(type = "LOG", tag = "Core", message = "first", createdAt = 1L)
        val second = AppLogRequest(type = "LOG", tag = "Core", message = "second", createdAt = 2L)
        val third = AppLogRequest(type = "LOG", tag = "Core", message = "third", createdAt = 3L)

        buffer.add(first)
        buffer.add(second)
        buffer.add(third)

        assertEquals(listOf(second, third), buffer.snapshot())
        assertTrue(buffer.snapshot().none { it == first })
    }
}
