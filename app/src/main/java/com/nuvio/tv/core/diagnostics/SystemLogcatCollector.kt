package com.nuvio.tv.core.diagnostics

import android.os.Process
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemLogcatCollector @Inject constructor() {

    fun collect(maxLines: Int = DEFAULT_MAX_LINES): String {
        val commands = listOf(
            listOf("logcat", "-d", "-t", maxLines.toString(), "--pid", Process.myPid().toString()),
            listOf("logcat", "-d", "-t", maxLines.toString())
        )

        commands.forEachIndexed { index, command ->
            val output = runCommand(command) ?: return@forEachIndexed
            val normalized = if (index == 0) {
                output
            } else {
                filterForAppSignals(output)
            }

            if (normalized.isNotBlank()) {
                return normalized.trim()
            }
        }

        return UNAVAILABLE_MESSAGE
    }

    private fun runCommand(command: List<String>): String? {
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                return null
            }

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }
        }.getOrNull()
    }

    internal fun filterForAppSignals(rawLogcat: String): String {
        return rawLogcat.lineSequence()
            .filter { line ->
                APP_SIGNAL_TAGS.any { tag -> line.contains(tag, ignoreCase = false) } ||
                    APP_SIGNAL_KEYWORDS.any { keyword -> line.contains(keyword, ignoreCase = true) }
            }
            .toList()
            .takeLast(FALLBACK_MAX_LINES)
            .joinToString(separator = "\n")
    }

    companion object {
        const val UNAVAILABLE_MESSAGE =
            "System logcat unavailable for this report. Android may restrict log access on this build/device."

        private const val DEFAULT_MAX_LINES = 400
        private const val FALLBACK_MAX_LINES = 250
        private const val COMMAND_TIMEOUT_SECONDS = 2L

        fun hasMeaningfulOutput(logText: String): Boolean {
            return logText.isNotBlank() && !logText.startsWith(UNAVAILABLE_MESSAGE)
        }

        private val APP_SIGNAL_TAGS = listOf(
            "NuvioApplication",
            "DiagnosticsReportMgr",
            "AdvancedDiagnostics",
            "CrashRecovery",
            "CrashExceptionHandler"
        )

        private val APP_SIGNAL_KEYWORDS = listOf(
            "com.nuvio.tv",
            "nuvio"
        )
    }
}
