package com.nuvio.tv.core.diagnostics

import android.content.Context
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class DiagnosticsReportManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val moshi: Moshi,
    private val snapshotBuilder: DiagnosticsSnapshotBuilder,
    private val filePruner: DiagnosticsFilePruner,
    private val crashRecoveryStore: CrashRecoveryStore,
    private val inAppLogBuffer: InAppLogBuffer
) {
    private val manifestAdapter by lazy {
        moshi.adapter(DiagnosticsManifest::class.java).indent("  ")
    }

    suspend fun createManualReport(
        userNote: String? = null,
        lastRoute: String? = crashRecoveryStore.getLastRoute()
    ): Result<DiagnosticsReportRef> = withContext(Dispatchers.IO) {
        runCatching {
            createReport(
                source = ReportSource.MANUAL,
                userNote = userNote,
                lastRoute = lastRoute,
                crashSummary = null,
                markPendingCrash = false
            )
        }
    }

    suspend fun createCrashReport(
        crashSummary: CrashSummary,
        lastRoute: String? = crashRecoveryStore.getLastRoute(),
        userNote: String? = null,
        markPendingCrash: Boolean = true
    ): Result<DiagnosticsReportRef> = withContext(Dispatchers.IO) {
        runCatching {
            createReport(
                source = ReportSource.CRASH,
                userNote = userNote,
                lastRoute = lastRoute,
                crashSummary = crashSummary,
                markPendingCrash = markPendingCrash
            )
        }
    }

    fun captureCrashReportBlocking(
        throwable: Throwable,
        lastRoute: String? = crashRecoveryStore.getLastRoute(),
        threadName: String? = Thread.currentThread().name
    ): Result<DiagnosticsReportRef> {
        return runCatching {
            createReport(
                source = ReportSource.CRASH,
                userNote = null,
                lastRoute = lastRoute,
                crashSummary = CrashSummary.fromThrowable(throwable, threadName = threadName),
                markPendingCrash = true
            )
        }
    }

    fun getReportDirectory(reportId: String): File {
        return File(reportsRootDirectory(), reportId)
    }

    fun getRecentReports(limit: Int = MAX_REPORTS): List<DiagnosticsReportRef> {
        return reportsRootDirectory().listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.mapNotNull { directory ->
                loadStoredReport(directory.name)?.ref
            }
            .orEmpty()
    }

    fun loadStoredReport(reportId: String): DiagnosticsStoredReport? {
        val reportDirectory = getReportDirectory(reportId)
        if (!reportDirectory.exists() || !reportDirectory.isDirectory) {
            return null
        }

        val manifestFile = File(reportDirectory, "manifest.json")
        val manifest = manifestFile.takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
            ?.let { manifestAdapter.fromJson(it) }
            ?: return null

        return DiagnosticsStoredReport(
            ref = DiagnosticsReportRef(
                id = manifest.reportId,
                source = manifest.source,
                createdAtEpochMs = manifest.createdAtEpochMs,
                directoryName = reportDirectory.name
            ),
            manifest = manifest,
            diagnosticsText = readOptionalFile(File(reportDirectory, "diagnostics.txt")).orEmpty(),
            appLogText = readOptionalFile(File(reportDirectory, "app-log.txt")).orEmpty(),
            crashText = readOptionalFile(File(reportDirectory, "crash.txt")),
            userNoteText = readOptionalFile(File(reportDirectory, "user-note.txt"))
        )
    }

    fun buildIssuePayload(reportId: String): String? {
        val report = loadStoredReport(reportId) ?: return null
        return buildString {
            appendLine("Title: replace with a short, specific crash/problem summary")
            appendLine()
            appendLine("App version: ${report.manifest.appVersionName} (${report.manifest.appVersionCode})")
            appendLine("Package: ${report.manifest.packageName}")
            appendLine("Android: ${report.manifest.androidVersion} / SDK ${report.manifest.sdkInt}")
            appendLine(
                "Device: ${report.manifest.manufacturer} ${report.manifest.model} " +
                    "(brand=${report.manifest.brand}, device=${report.manifest.device})"
            )
            appendLine("Install source: ${report.manifest.installerPackageName ?: "unknown"}")
            appendLine("Report source: ${report.manifest.source.name.lowercase()}")
            appendLine("Last route: ${report.manifest.lastRoute ?: "unknown"}")
            appendLine()
            appendLine("Steps to reproduce:")
            appendLine("1.")
            appendLine("2.")
            appendLine("3.")
            appendLine()
            appendLine("Expected behavior:")
            appendLine()
            appendLine("Actual behavior:")
            appendLine()
            appendLine("Frequency:")
            appendLine()

            report.userNoteText?.takeIf { it.isNotBlank() }?.let { note ->
                appendLine("User note:")
                appendLine(note)
                appendLine()
            }

            if (!report.manifest.crashType.isNullOrBlank() || !report.manifest.crashMessage.isNullOrBlank()) {
                appendLine("Crash summary:")
                appendLine("- Type: ${report.manifest.crashType ?: "unknown"}")
                appendLine("- Message: ${report.manifest.crashMessage ?: "(none)"}")
                appendLine("- Thread: ${report.manifest.crashThreadName ?: "unknown"}")
                appendLine()
            }

            appendLine("Diagnostics:")
            appendLine(report.diagnosticsText.trim())
        }.trim()
    }

    private fun createReport(
        source: ReportSource,
        userNote: String?,
        lastRoute: String?,
        crashSummary: CrashSummary?,
        markPendingCrash: Boolean
    ): DiagnosticsReportRef {
        val createdAtEpochMs = System.currentTimeMillis()
        val reportId = buildReportId(source, createdAtEpochMs)
        val reportRef = DiagnosticsReportRef(
            id = reportId,
            source = source,
            createdAtEpochMs = createdAtEpochMs,
            directoryName = reportId
        )
        val snapshot = snapshotBuilder.build(
            reportRef = reportRef,
            lastRoute = lastRoute,
            userNote = userNote,
            crashSummary = crashSummary
        )

        val reportDirectory = getReportDirectory(reportId)
        if (!reportDirectory.exists() && !reportDirectory.mkdirs()) {
            error("Unable to create diagnostics report directory: ${reportDirectory.absolutePath}")
        }

        writeFile(File(reportDirectory, "manifest.json"), manifestAdapter.toJson(snapshot.manifest))
        writeFile(File(reportDirectory, "diagnostics.txt"), snapshot.diagnosticsText)
        writeFile(
            File(reportDirectory, "app-log.txt"),
            snapshot.appLogText.ifBlank { "No in-app logs captured yet." }
        )
        snapshot.crashText?.let { writeFile(File(reportDirectory, "crash.txt"), it) }
        snapshot.userNoteText?.let { writeFile(File(reportDirectory, "user-note.txt"), it) }

        if (markPendingCrash && crashSummary != null) {
            crashRecoveryStore.markPendingCrash(reportRef, crashSummary, lastRoute)
        }

        filePruner.prune(reportsRootDirectory(), MAX_REPORTS)
        inAppLogBuffer.info(TAG, "Created diagnostics report id=$reportId source=${source.name}")
        return reportRef
    }

    private fun reportsRootDirectory(): File {
        return File(context.filesDir, REPORTS_ROOT_PATH)
    }

    private fun writeFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content.trimEnd() + "\n", Charsets.UTF_8)
    }

    private fun readOptionalFile(file: File): String? {
        return file.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim()
    }

    private fun buildReportId(source: ReportSource, createdAtEpochMs: Long): String {
        val suffix = UUID.randomUUID().toString().substring(0, 8)
        return "${source.name.lowercase()}-$createdAtEpochMs-$suffix"
    }

    companion object {
        private const val TAG = "DiagnosticsReportMgr"
        private const val REPORTS_ROOT_PATH = "diagnostics/reports"
        private const val MAX_REPORTS = 10
    }
}
