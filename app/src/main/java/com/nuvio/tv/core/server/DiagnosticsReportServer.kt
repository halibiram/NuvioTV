package com.nuvio.tv.core.server

import com.nuvio.tv.core.diagnostics.DiagnosticsReportManager
import fi.iki.elonen.NanoHTTPD

class DiagnosticsReportServer(
    private val reportManager: DiagnosticsReportManager,
    port: Int = 8080
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.trimEnd('/').ifBlank { "/" }

        return when {
            session.method == Method.GET && path == "/" -> serveLandingPage()
            session.method == Method.GET && path.startsWith("/report/") -> serveReportRoute(path)
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

    private fun serveReportRoute(path: String): Response {
        val parts = path.removePrefix("/").split('/')
        if (parts.size < 2 || parts.first() != "report") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        val reportId = parts[1]
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
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/html",
                DiagnosticsReportWebPage.renderMissingReportPage(reportId)
            )
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
