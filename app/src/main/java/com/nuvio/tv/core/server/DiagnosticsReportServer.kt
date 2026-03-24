package com.nuvio.tv.core.server

import com.nuvio.tv.core.diagnostics.DiagnosticsReportManager
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.HashMap

class DiagnosticsReportServer(
    private val reportManager: DiagnosticsReportManager,
    port: Int = 8080
) : NanoHTTPD(port) {

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
        val recentReports = reportManager.getRecentReports(limit = 10)
            .mapNotNull { reportManager.loadStoredReport(it.id) }
        val html = DiagnosticsReportWebPage.renderLandingPage(recentReports)
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

        val report = reportManager.loadStoredReport(reportId)
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
                DiagnosticsReportWebPage.renderLogsText(report)
            )
            "payload" -> newFixedLengthResponse(
                Response.Status.OK,
                MIME_PLAINTEXT,
                reportManager.buildIssuePayload(reportId) ?: "Report payload unavailable"
            )
            "download" -> buildDownloadResponse(
                fileName = "$reportId-logs.txt",
                content = DiagnosticsReportWebPage.renderLogsText(report)
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
