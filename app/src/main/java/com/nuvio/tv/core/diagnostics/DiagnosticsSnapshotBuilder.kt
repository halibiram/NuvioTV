package com.nuvio.tv.core.diagnostics

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticsSnapshotBuilder @Inject constructor(
    private val deviceInfoCollector: DeviceInfoCollector,
    private val inAppLogBuffer: InAppLogBuffer,
    private val systemLogcatCollector: SystemLogcatCollector
) {
    fun build(
        reportRef: DiagnosticsReportRef,
        lastRoute: String?,
        userNote: String?,
        crashSummary: CrashSummary?
    ): DiagnosticsSnapshot {
        val deviceInfo = deviceInfoCollector.collect()
        val createdAtIsoUtc = Instant.ofEpochMilli(reportRef.createdAtEpochMs).toString()
        val manifest = DiagnosticsManifest(
            reportId = reportRef.id,
            source = reportRef.source,
            createdAtEpochMs = reportRef.createdAtEpochMs,
            createdAtIsoUtc = createdAtIsoUtc,
            packageName = deviceInfo.packageName,
            appVersionName = deviceInfo.appVersionName,
            appVersionCode = deviceInfo.appVersionCode,
            androidVersion = deviceInfo.androidVersion,
            sdkInt = deviceInfo.sdkInt,
            manufacturer = deviceInfo.manufacturer,
            brand = deviceInfo.brand,
            model = deviceInfo.model,
            device = deviceInfo.device,
            supportedAbis = deviceInfo.supportedAbis,
            installerPackageName = deviceInfo.installerPackageName,
            lastRoute = lastRoute,
            crashType = crashSummary?.type,
            crashMessage = crashSummary?.message,
            crashThreadName = crashSummary?.threadName
        )

        val appLogText = inAppLogBuffer.export()
        val systemLogText = systemLogcatCollector.collect()

        return DiagnosticsSnapshot(
            manifest = manifest,
            diagnosticsText = buildDiagnosticsText(
                manifest = manifest,
                userNote = userNote,
                appLogText = appLogText,
                systemLogText = systemLogText,
                crashSummary = crashSummary
            ),
            appLogText = appLogText,
            systemLogText = systemLogText,
            crashText = crashSummary?.stackTrace,
            userNoteText = userNote?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun buildDiagnosticsText(
        manifest: DiagnosticsManifest,
        userNote: String?,
        appLogText: String,
        systemLogText: String,
        crashSummary: CrashSummary?
    ): String {
        return buildString {
            appendLine("NuvioTV diagnostics report")
            appendLine("Report ID: ${manifest.reportId}")
            appendLine("Source: ${manifest.source.name.lowercase()}")
            appendLine("Created UTC: ${manifest.createdAtIsoUtc}")
            appendLine("App version: ${manifest.appVersionName} (${manifest.appVersionCode})")
            appendLine("Package: ${manifest.packageName}")
            appendLine("Android: ${manifest.androidVersion} / SDK ${manifest.sdkInt}")
            appendLine(
                "Device: ${manifest.manufacturer} ${manifest.model} " +
                    "(brand=${manifest.brand}, device=${manifest.device})"
            )
            appendLine(
                "ABI: ${manifest.supportedAbis.ifEmpty { listOf("unknown") }.joinToString()}"
            )
            appendLine("Installer: ${manifest.installerPackageName ?: "unknown"}")
            appendLine("Last route: ${manifest.lastRoute ?: "unknown"}")

            if (crashSummary != null) {
                appendLine("Crash type: ${crashSummary.type}")
                appendLine("Crash message: ${crashSummary.message ?: "(none)"}")
                appendLine("Crash thread: ${crashSummary.threadName ?: "unknown"}")
            }

            val cleanNote = userNote?.trim().orEmpty()
            if (cleanNote.isNotEmpty()) {
                appendLine()
                appendLine("User note:")
                appendLine(cleanNote)
            }

            appendLine()
            appendLine("In-app logs included: ${if (appLogText.isBlank()) "no" else "yes"}")
            appendLine(
                "System logcat included: ${if (SystemLogcatCollector.hasMeaningfulOutput(systemLogText)) "yes" else "best-effort unavailable"}"
            )
        }.trim()
    }
}
