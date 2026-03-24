package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSanitizerTest {

    @Test
    fun sanitizeText_redactsSecretsAndLongTokens() {
        val sanitized = DiagnosticsSanitizer.sanitizeText(
            "token=abc123def456ghi789jkl012 password:superSecret bearer Bearer abcdefghijklmnopqrstuvwxyz123456"
        )

        assertFalse(sanitized.contains("abc123def456ghi789jkl012"))
        assertFalse(sanitized.contains("superSecret"))
        assertFalse(sanitized.contains("abcdefghijklmnopqrstuvwxyz123456"))
        assertTrue(sanitized.contains("token=<redacted>"))
        assertTrue(sanitized.contains("password=<redacted>"))
        assertTrue(sanitized.contains("Bearer <redacted>"))
    }

    @Test
    fun sanitizeText_redactsPrivateUrlsAndEmails() {
        val sanitized = DiagnosticsSanitizer.sanitizeText(
            "open http://192.168.1.25:8080/report?id=42&token=abcdef and mail qa@example.com"
        )

        assertTrue(sanitized.contains("http://<redacted-host>:8080/report?<redacted>"))
        assertTrue(sanitized.contains("<redacted-email>"))
        assertFalse(sanitized.contains("192.168.1.25"))
        assertFalse(sanitized.contains("qa@example.com"))
        assertFalse(sanitized.contains("token=abcdef"))
    }

    @Test
    fun sanitizeSingleLine_returnsNullForBlankInput() {
        assertTrue(DiagnosticsSanitizer.sanitizeSingleLine("   ") == null)
    }
}
