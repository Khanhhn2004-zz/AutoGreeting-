package com.example.carchatbot

import com.example.carchatbot.support.DiagnosticsPrivacySanitizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsPrivacySanitizerTest {

    @Test
    fun `sanitizer masks phone numbers and email addresses`() {
        val sanitizer = DiagnosticsPrivacySanitizer()

        val text = "phone=0971234567 email=user@example.com"
        val sanitized = sanitizer.sanitizeText(text)

        assertFalse(sanitized.contains("0971234567"))
        assertFalse(sanitized.contains("user@example.com"))
        assertTrue(sanitized.contains("097***"))
        assertTrue(sanitized.contains("u***@example.com"))
    }

    @Test
    fun `sanitizer redacts bearer tokens and collapses app file paths`() {
        val sanitizer = DiagnosticsPrivacySanitizer()

        val text = "Bearer abcdef123456 /data/user/0/com.example.carchatbot/files/sound_assets_v2/server_hello.audio"
        val sanitized = sanitizer.sanitizeText(text)

        assertFalse(sanitized.contains("abcdef123456"))
        assertFalse(sanitized.contains("/data/user/0/com.example.carchatbot"))
        assertTrue(sanitized.contains("Bearer REDACTED"))
        assertTrue(sanitized.contains("server_hello.audio"))
    }

    @Test
    fun `sanitizer masks ipv4 addresses and trims exception payloads`() {
        val sanitizer = DiagnosticsPrivacySanitizer()

        val text = "ip=192.168.1.45 java.lang.IllegalStateException: secret-token"
        val sanitized = sanitizer.sanitizeText(text)

        assertFalse(sanitized.contains("192.168.1.45"))
        assertFalse(sanitized.contains("secret-token"))
        assertTrue(sanitized.contains("192.168.1.xxx"))
        assertTrue(sanitized.contains("java.lang.IllegalStateException"))
    }
}
