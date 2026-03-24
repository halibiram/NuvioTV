package com.nuvio.tv.core.diagnostics

import android.os.Process
import kotlin.system.exitProcess

class CrashReportExceptionHandler(
    private val diagnosticsReportManager: DiagnosticsReportManager,
    private val crashRecoveryStore: CrashRecoveryStore,
    private val inAppLogBuffer: InAppLogBuffer,
    private val previousHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            inAppLogBuffer.error(
                TAG,
                "Uncaught exception on thread=${thread.name}",
                throwable
            )
            diagnosticsReportManager.captureCrashReportBlocking(
                throwable = throwable,
                lastRoute = crashRecoveryStore.getLastRoute(),
                threadName = thread.name
            )
        }.onFailure { captureError ->
            runCatching {
                inAppLogBuffer.error(
                    TAG,
                    "Crash diagnostics capture failed before process death",
                    captureError
                )
            }
        }

        previousHandler?.uncaughtException(thread, throwable) ?: run {
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    companion object {
        private const val TAG = "CrashExceptionHandler"
    }
}
