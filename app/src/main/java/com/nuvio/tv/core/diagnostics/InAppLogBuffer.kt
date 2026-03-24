package com.nuvio.tv.core.diagnostics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InAppLogBuffer @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val lock = Any()

    private val logFile: File by lazy {
        File(File(context.filesDir, "diagnostics/live"), "app-buffer.log")
    }

    fun debug(tag: String, message: String) = append("D", tag, message)

    fun info(tag: String, message: String) = append("I", tag, message)

    fun warn(tag: String, message: String, throwable: Throwable? = null) =
        append("W", tag, message, throwable)

    fun error(tag: String, message: String, throwable: Throwable? = null) =
        append("E", tag, message, throwable)

    fun append(level: String, tag: String, message: String, throwable: Throwable? = null) {
        synchronized(lock) {
            val file = ensureLogFile()
            file.appendText(buildEntry(level, tag, message, throwable), Charsets.UTF_8)
            trimIfNeeded(file)
        }
    }

    fun export(): String {
        synchronized(lock) {
            val file = logFile
            if (!file.exists()) return ""
            return file.readText(Charsets.UTF_8).trim()
        }
    }

    private fun buildEntry(level: String, tag: String, message: String, throwable: Throwable?): String {
        return buildString {
            append(Instant.now().toString())
            append(' ')
            append(level.uppercase())
            append('/')
            append(tag)
            append(": ")
            append(sanitizeInline(message))
            append('\n')

            throwable?.let {
                append(it.stackTraceToString())
                if (!endsWith("\n")) {
                    append('\n')
                }
            }
        }
    }

    private fun trimIfNeeded(file: File) {
        if (file.length() <= MAX_FILE_BYTES) return

        val trimmed = file.readLines(Charsets.UTF_8)
            .takeLast(MAX_LINES)
            .joinToString(separator = "\n")
            .let { if (it.isBlank()) "" else "$it\n" }

        file.writeText(trimmed, Charsets.UTF_8)
    }

    private fun ensureLogFile(): File {
        val file = logFile
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.createNewFile()
        }
        return file
    }

    private fun sanitizeInline(message: String): String {
        return message.replace('\n', ' ').replace('\r', ' ').trim()
    }

    companion object {
        private const val MAX_FILE_BYTES = 256 * 1024L
        private const val MAX_LINES = 800
    }
}
