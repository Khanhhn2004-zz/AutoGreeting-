package com.example.carchatbot.support

import java.io.File
import javax.inject.Inject

class DiagnosticsPrivacySanitizer @Inject constructor() {

    fun sanitizeText(value: String?): String {
        if (value.isNullOrBlank()) {
            return ""
        }

        return value
            .let(::sanitizeBearerTokens)
            .let(::sanitizeEmails)
            .let(::sanitizePhoneNumbers)
            .let(::sanitizeIpv4Addresses)
            .let(::sanitizeAppPaths)
            .let(::sanitizeSecrets)
            .trim()
    }

    private fun sanitizeBearerTokens(value: String): String {
        return value.replace(Regex("""Bearer\s+[A-Za-z0-9._\-]+"""), "Bearer REDACTED")
    }

    private fun sanitizeEmails(value: String): String {
        return EMAIL_REGEX.replace(value) { match ->
            val email = match.value
            val atIndex = email.indexOf('@')
            if (atIndex <= 0) {
                "REDACTED_EMAIL"
            } else {
                "${email.first()}***${email.substring(atIndex)}"
            }
        }
    }

    private fun sanitizePhoneNumbers(value: String): String {
        return PHONE_REGEX.replace(value) { match ->
            val raw = match.value
            if (raw.length >= 3) "${raw.take(3)}***" else "***"
        }
    }

    private fun sanitizeIpv4Addresses(value: String): String {
        return IPV4_REGEX.replace(value) { match ->
            val parts = match.value.split('.')
            if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.xxx" else "x.x.x.x"
        }
    }

    private fun sanitizeAppPaths(value: String): String {
        return PATH_REGEX.replace(value) { match ->
            File(match.value).name.ifBlank { "redacted-path" }
        }
    }

    private fun sanitizeSecrets(value: String): String {
        val keyed = SECRET_KEY_VALUE_REGEX.replace(value) { match ->
            val prefix = match.groups[1]?.value ?: "secret"
            "$prefix=REDACTED"
        }
        return FREEFORM_SECRET_REGEX.replace(keyed) { match ->
            val token = match.value
            if (token.contains('.')) {
                token
            } else {
                "REDACTED_SECRET"
            }
        }
    }

    companion object {
        private val PHONE_REGEX = Regex("""\b0\d{8,10}\b""")
        private val EMAIL_REGEX = Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b""")
        private val IPV4_REGEX = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        private val PATH_REGEX = Regex("""(?:[A-Za-z]:\\|/)(?:[^\\/\s]+[\\/])*[^\\/\s]+""")
        private val SECRET_KEY_VALUE_REGEX = Regex("""(?i)\b(secret|token|password|auth)\s*=\s*([^\s]+)""")
        private val FREEFORM_SECRET_REGEX = Regex("""(?i)\b[\w\-]*(?:secret|token|password)[\w\-]*\b""")
    }
}
