package com.nuvio.tv.core.diagnostics

import java.net.URI
import java.util.Locale

object DiagnosticsSanitizer {

    fun sanitizeText(input: String?): String {
        if (input.isNullOrBlank()) return input.orEmpty()

        var sanitized = input
        sanitized = sanitizeUrls(sanitized)
        sanitized = redactKeyValueSecrets(sanitized)
        sanitized = redactBearerTokens(sanitized)
        sanitized = redactEmails(sanitized)
        sanitized = redactLongTokens(sanitized)
        return sanitized
    }

    fun sanitizeSingleLine(input: String?): String? {
        return input?.takeIf { it.isNotBlank() }?.let { sanitizeText(it).trim() }
    }

    private fun sanitizeUrls(value: String): String {
        return URL_REGEX.replace(value) { match ->
            sanitizeUrl(match.value)
        }
    }

    private fun sanitizeUrl(rawUrl: String): String {
        val candidate = rawUrl.trimEnd('.', ',', ';', ')', ']', '}')
        val trailing = rawUrl.removePrefix(candidate)
        val sanitized = runCatching {
            val uri = URI(candidate)
            val scheme = uri.scheme ?: return@runCatching candidate
            val host = uri.host
            val sanitizedHost = when {
                host.isNullOrBlank() -> null
                isPrivateHost(host) -> "<redacted-host>"
                else -> host
            }
            val authority = buildString {
                if (sanitizedHost != null) {
                    append(sanitizedHost)
                    if (uri.port != -1) {
                        append(':')
                        append(uri.port)
                    }
                }
            }.ifBlank { null }
            buildString {
                append(scheme)
                append("://")
                append(authority ?: "<redacted-host>")
                if (!uri.path.isNullOrBlank()) append(sanitizePath(uri.path))
                if (uri.query != null) append("?<redacted>")
                if (uri.fragment != null) append("#<redacted>")
            }
        }.getOrDefault(candidate)
        return sanitized + trailing
    }

    private fun redactKeyValueSecrets(value: String): String {
        return SECRET_KEY_VALUE_REGEX.replace(value) { match ->
            val key = match.groups[1]?.value.orEmpty()
            "$key=<redacted>"
        }
    }

    private fun redactBearerTokens(value: String): String {
        return BEARER_REGEX.replace(value, "Bearer <redacted>")
    }

    private fun redactEmails(value: String): String {
        return EMAIL_REGEX.replace(value, "<redacted-email>")
    }

    private fun redactLongTokens(value: String): String {
        return LONG_TOKEN_REGEX.replace(value) { match ->
            val token = match.value
            if (token.any(Char::isLetter) && token.any(Char::isDigit)) {
                "<redacted-token>"
            } else {
                token
            }
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.US)
        if (normalized == "localhost") return true
        if (normalized.endsWith(".local")) return true
        if (normalized.startsWith("10.")) return true
        if (normalized.startsWith("192.168.")) return true
        if (normalized.startsWith("127.")) return true

        if (normalized.startsWith("172.")) {
            val secondOctet = normalized.split('.').getOrNull(1)?.toIntOrNull()
            if (secondOctet in 16..31) return true
        }

        return false
    }

    private fun sanitizePath(path: String): String {
        val hasLeadingSlash = path.startsWith('/')
        val hasTrailingSlash = path.endsWith('/') && path.length > 1
        val sanitizedSegments = path.split('/')
            .filter { it.isNotEmpty() }
            .map { segment ->
                when {
                    looksSensitivePathSegment(segment) -> "<redacted-segment>"
                    else -> segment
                }
            }

        if (sanitizedSegments.isEmpty()) return if (hasLeadingSlash) "/" else ""

        return buildString {
            if (hasLeadingSlash) append('/')
            append(sanitizedSegments.joinToString("/"))
            if (hasTrailingSlash) append('/')
        }
    }

    private fun looksSensitivePathSegment(segment: String): Boolean {
        val normalized = segment.substringBefore('?').substringBefore('#')
        if (normalized.isBlank()) return false
        if (UUID_REGEX.matches(normalized)) return true
        if (LONG_DIGIT_REGEX.matches(normalized)) return true
        if (LONG_HEX_REGEX.matches(normalized)) return true
        if (normalized.length >= 24 && normalized.any(Char::isLetter) && normalized.any(Char::isDigit)) {
            return true
        }
        return false
    }

    private val URL_REGEX = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    private val SECRET_KEY_VALUE_REGEX =
        Regex("(?i)\\b(authorization|token|access[_-]?token|refresh[_-]?token|api[_-]?key|apikey|password|passwd|secret|session(?:id)?|cookie)\\b\\s*[:=]\\s*([^\\s,;]+)")
    private val BEARER_REGEX = Regex("(?i)Bearer\\s+[A-Za-z0-9._\\-+/=]{8,}")
    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val LONG_TOKEN_REGEX = Regex("(?<![A-Za-z0-9])[A-Za-z0-9_\\-]{24,}(?![A-Za-z0-9])")
    private val UUID_REGEX = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val LONG_DIGIT_REGEX = Regex("\\d{6,}")
    private val LONG_HEX_REGEX = Regex("(?i)[0-9a-f]{16,}")
}
