package com.nuvio.tv.core.server

import com.nuvio.tv.core.diagnostics.DiagnosticsReportManager
import com.nuvio.tv.core.diagnostics.DiagnosticsReportSummary
import com.nuvio.tv.core.diagnostics.DiagnosticsStoredReport
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DiagnosticsReportServer(
    private val reportManager: DiagnosticsReportManager,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val reportCache = ConcurrentHashMap<String, DiagnosticsStoredReport>()
    private val summaryCache = ConcurrentHashMap<String, DiagnosticsReportSummary>()
    private val payloadCache = ConcurrentHashMap<String, String>()
    private val logsTextCache = ConcurrentHashMap<String, String>()
    private val downloadLogsTextCache = ConcurrentHashMap<String, String>()
    private val warmingReportIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val warmExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.trimEnd('/').ifBlank { "/" }

        return when {
            session.method == Method.GET && path == "/" -> serveLandingPage()
            path.startsWith("/report/") -> serveReportRoute(session, path)
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/html",
                DiagnosticsReportWebPage.renderMissingReportPage(null)
            )
        }
    }

    private fun serveLandingPage(): Response {
        val recentReports = reportManager.getRecentReportSummaries(limit = 10)
        recentReports.forEach { summary -> summaryCache[summary.ref.id] = summary }
        warmReportsInBackground(recentReports.map { it.ref.id })
        val html = DiagnosticsReportWebPage.renderLandingPageSummaries(recentReports)
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveReportRoute(session: IHTTPSession, path: String): Response {
        val parts = path.removePrefix("/").split('/')
        if (parts.size < 2 || parts.first() != "report") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        val reportId = parts[1]
        if (session.method == Method.POST && parts.getOrNull(2) == "note") {
            return handleUserNoteUpdate(session, reportId)
        }

        if (session.method == Method.POST && parts.getOrNull(2) == "delete") {
            return if (reportManager.deleteReport(reportId)) {
                invalidateReportCaches(reportId)
                newFixedLengthResponse(
                    Response.Status.REDIRECT,
                    MIME_HTML,
                    ""
                ).apply {
                    addHeader("Location", "/")
                }
            } else {
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/html",
                    DiagnosticsReportWebPage.renderMissingReportPage(reportId)
                )
            }
        }

        val report = getCachedReport(reportId)
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/html",
                DiagnosticsReportWebPage.renderMissingReportPage(reportId)
            )

        return when (parts.getOrNull(2)) {
            null -> newFixedLengthResponse(
                Response.Status.OK,
                "text/html",
                DiagnosticsReportWebPage.renderReportPage(report)
            )
            "logs" -> newFixedLengthResponse(
                Response.Status.OK,
                MIME_PLAINTEXT,
                getCachedLogsText(report, previewSystemLogcat = true)
            )
            "payload" -> newFixedLengthResponse(
                Response.Status.OK,
                MIME_PLAINTEXT,
                getCachedPayload(report) ?: "Report payload unavailable"
            )
            "download" -> buildDownloadResponse(
                fileName = "$reportId-logs.txt",
                content = getCachedLogsText(report, previewSystemLogcat = false)
            )
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/html",
                DiagnosticsReportWebPage.renderMissingReportPage(reportId)
            )
        }
    }

    private fun handleUserNoteUpdate(session: IHTTPSession, reportId: String): Response {
        val parsedFiles = HashMap<String, String>()
        return runCatching {
            session.parseBody(parsedFiles)
            val userNote = session.parameters["user_note"]?.firstOrNull()
            if (reportManager.updateUserNote(reportId, userNote)) {
                val refreshedReport = reportManager.loadStoredReport(reportId)
                if (refreshedReport != null) {
                    cacheReportAndDerived(refreshedReport)
                } else {
                    invalidateReportCaches(reportId)
                }
                newFixedLengthResponse(
                    Response.Status.REDIRECT,
                    MIME_HTML,
                    ""
                ).apply {
                    addHeader("Location", "/report/$reportId")
                }
            } else {
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/html",
                    DiagnosticsReportWebPage.renderMissingReportPage(reportId)
                )
            }
        }.getOrElse {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Unable to update user note"
            )
        }
    }

    private fun buildDownloadResponse(fileName: String, content: String): Response {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        return newChunkedResponse(
            Response.Status.OK,
            "text/plain; charset=utf-8",
            ByteArrayInputStream(bytes)
        ).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
        }
    }

    private fun getCachedReport(reportId: String): DiagnosticsStoredReport? {
        reportCache[reportId]?.let { return it }

        val report = reportManager.loadStoredReport(reportId) ?: return null
        cacheReportAndDerived(report)
        return report
    }

    private fun getCachedPayload(report: DiagnosticsStoredReport): String? {
        payloadCache[report.ref.id]?.let { return it }

        val payload = reportManager.buildIssuePayload(report)
        payloadCache[report.ref.id] = payload
        return payload
    }

    private fun getCachedLogsText(report: DiagnosticsStoredReport, previewSystemLogcat: Boolean): String {
        val cache = if (previewSystemLogcat) logsTextCache else downloadLogsTextCache
        cache[report.ref.id]?.let { return it }

        val text = DiagnosticsReportWebPage.renderLogsText(report, previewSystemLogcat = previewSystemLogcat)
        cache[report.ref.id] = text
        return text
    }

    private fun warmReportsInBackground(reportIds: List<String>) {
        val idsToWarm = reportIds.filter { reportId ->
            !reportCache.containsKey(reportId) && warmingReportIds.add(reportId)
        }

        if (idsToWarm.isEmpty()) return

        warmExecutor.execute {
            idsToWarm.forEach { reportId ->
                try {
                    val report = reportManager.loadStoredReport(reportId)
                    if (report != null) {
                        cacheReportAndDerived(report)
                    }
                } finally {
                    warmingReportIds.remove(reportId)
                }
            }
        }
    }

    private fun cacheReportAndDerived(report: DiagnosticsStoredReport) {
        reportCache[report.ref.id] = report
        summaryCache[report.ref.id] = DiagnosticsReportSummary(
            ref = report.ref,
            manifest = report.manifest,
            userNotePreview = report.userNoteText?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
        )
    }

    private fun invalidateReportCaches(reportId: String) {
        reportCache.remove(reportId)
        summaryCache.remove(reportId)
        payloadCache.remove(reportId)
        logsTextCache.remove(reportId)
        downloadLogsTextCache.remove(reportId)
        warmingReportIds.remove(reportId)
    }

    override fun stop() {
        super.stop()
        warmExecutor.shutdownNow()
        reportCache.clear()
        summaryCache.clear()
        payloadCache.clear()
        logsTextCache.clear()
        downloadLogsTextCache.clear()
        warmingReportIds.clear()
    }

    companion object {
        fun startOnAvailablePort(
            reportManager: DiagnosticsReportManager,
            startPort: Int = 8080,
            maxAttempts: Int = 10
        ): DiagnosticsReportServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    return DiagnosticsReportServer(reportManager, port).also {
                        it.start(SOCKET_READ_TIMEOUT, false)
                    }
                } catch (_: Exception) {
                    // Port in use, try next.
                }
            }
            return null
        }
    }
}
