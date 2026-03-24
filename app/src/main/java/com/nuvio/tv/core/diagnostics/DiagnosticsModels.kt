package com.nuvio.tv.core.diagnostics

import com.squareup.moshi.JsonClass

enum class ReportSource {
    MANUAL,
    CRASH
}

@JsonClass(generateAdapter = true)
data class DiagnosticsReportRef(
    val id: String,
    val source: ReportSource,
    val createdAtEpochMs: Long,
    val directoryName: String
)

@JsonClass(generateAdapter = true)
data class DeviceInfo(
    val packageName: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val androidVersion: String,
    val sdkInt: Int,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val supportedAbis: List<String>,
    val installerPackageName: String?
)

@JsonClass(generateAdapter = true)
data class CrashSummary(
    val type: String,
    val message: String?,
    val threadName: String?,
    val stackTrace: String,
    val capturedAtEpochMs: Long
) {
    companion object {
        fun fromThrowable(
            throwable: Throwable,
            threadName: String? = Thread.currentThread().name,
            capturedAtEpochMs: Long = System.currentTimeMillis()
        ): CrashSummary {
            return CrashSummary(
                type = throwable::class.java.name,
                message = throwable.message,
                threadName = threadName,
                stackTrace = throwable.stackTraceToString(),
                capturedAtEpochMs = capturedAtEpochMs
            )
        }
    }
}

@JsonClass(generateAdapter = true)
data class PendingCrashMarker(
    val reportId: String,
    val createdAtEpochMs: Long,
    val lastRoute: String?,
    val crashType: String?,
    val crashMessage: String?,
    val threadName: String?
)

@JsonClass(generateAdapter = true)
data class DiagnosticsManifest(
    val reportId: String,
    val source: ReportSource,
    val createdAtEpochMs: Long,
    val createdAtIsoUtc: String,
    val packageName: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val androidVersion: String,
    val sdkInt: Int,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val supportedAbis: List<String>,
    val installerPackageName: String?,
    val lastRoute: String?,
    val crashType: String?,
    val crashMessage: String?,
    val crashThreadName: String?
)

data class DiagnosticsSnapshot(
    val manifest: DiagnosticsManifest,
    val diagnosticsText: String,
    val appLogText: String,
    val systemLogText: String,
    val crashText: String?,
    val userNoteText: String?
)

data class DiagnosticsStoredReport(
    val ref: DiagnosticsReportRef,
    val manifest: DiagnosticsManifest,
    val diagnosticsText: String,
    val appLogText: String,
    val systemLogText: String,
    val crashText: String?,
    val userNoteText: String?
)

data class DiagnosticsReportSummary(
    val ref: DiagnosticsReportRef,
    val manifest: DiagnosticsManifest,
    val userNotePreview: String?
)
