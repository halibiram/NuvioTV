package com.nuvio.tv.ui.screens.player.iec

import android.util.Log

/**
 * Single emit path for sink diagnostics.
 *
 * Every line goes to logcat and to the playback-report callback with identical text, so a
 * logcat capture and a report carry the same lines and either one can be scored on its own.
 *
 * A run of identical consecutive lines is collapsed: the first is emitted immediately, and
 * when the run ends a summary line "<line> repeat=<n>" reports how many further copies were
 * seen (the syslog idiom, kept on its own line so the count is never misread as belonging to
 * the next event). `iec_health` is exempt and always emitted; the sink throttles it already.
 * A trailing run with no following line is summarised on the next `emit` or not at all, which
 * only loses the tail count of the very last run.
 *
 * Any failure inside the emit path disables diagnostics for this instance and reports that
 * once, rather than reaching the caller on the playback thread. The callback is invoked
 * synchronously on the caller's thread; the receiver owns any thread hop it needs.
 */
internal class IecDiagnostics(
    private val onDiagnosticEvent: ((String) -> Unit)?,
    private val tag: String = TAG
) {
    private var lastLine: String? = null
    private var lastWarn: Boolean = false
    private var suppressed: Int = 0
    private var disabled: Boolean = false

    fun emit(line: String, warn: Boolean = false) {
        if (disabled) return
        try {
            if (line == lastLine && !line.startsWith(HEALTH_PREFIX)) {
                suppressed++
                return
            }
            flushSuppressed()
            write(line, warn)
            lastLine = line
            lastWarn = warn
        } catch (t: Throwable) {
            disabled = true
            try {
                Log.w(tag, "diag_disabled reason=${t.javaClass.simpleName}")
            } catch (_: Throwable) {
                // Nothing left to report through.
            }
        }
    }

    private fun flushSuppressed() {
        val n = suppressed
        if (n <= 0) return
        suppressed = 0
        val prev = lastLine ?: return
        write("$prev repeat=$n", lastWarn)
    }

    private fun write(text: String, warn: Boolean) {
        if (warn) Log.w(tag, text) else Log.i(tag, text)
        onDiagnosticEvent?.invoke(text)
    }

    private companion object {
        const val TAG = "IecPassthrough"
        const val HEALTH_PREFIX = "iec_health "
    }
}
