package com.nuvio.tv.core.server

import com.nuvio.tv.core.diagnostics.DiagnosticsManifest
import com.nuvio.tv.core.diagnostics.DiagnosticsReportRef
import com.nuvio.tv.core.diagnostics.DiagnosticsStoredReport
import com.nuvio.tv.core.diagnostics.DiagnosticsSupportLinks
import com.nuvio.tv.core.diagnostics.ReportSource
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportWebPageTest {

    @Test
    fun renderReportPage_includesIssueRulesAndActions() {
        val html = DiagnosticsReportWebPage.renderReportPage(sampleReport())

        assertTrue(html.contains("Open GitHub Issues"))
        assertTrue(html.contains(DiagnosticsSupportLinks.githubIssuesUrl))
        assertTrue(html.contains("Crash reports require logs or a stack trace"))
        assertTrue(html.contains("Open issue payload"))
        assertTrue(html.contains("Open logs"))
        assertTrue(html.contains("Privacy check"))
        assertTrue(html.contains("redacted automatically"))
    }

    @Test
    fun renderLandingPage_showsRecentReportsHeading() {
        val html = DiagnosticsReportWebPage.renderLandingPage(listOf(sampleReport()))

        assertTrue(html.contains("Recent reports"))
        assertTrue(html.contains("Captured reports on this TV"))
    }

    @Test
    fun renderLandingPage_emptyStateIncludesNextStepGuidance() {
        val html = DiagnosticsReportWebPage.renderLandingPage(emptyList())

        assertTrue(html.contains("No diagnostics reports have been captured on this device yet."))
        assertTrue(html.contains("Open Advanced settings on the TV and use Report a problem"))
    }

    @Test
    fun renderLogsText_includesSystemLogcatSection() {
        val logs = DiagnosticsReportWebPage.renderLogsText(sampleReport())

        assertTrue(logs.contains("# Privacy:"))
        assertTrue(logs.contains("## System logcat"))
        assertTrue(logs.contains("system log line"))
    }

    @Test
    fun renderLogsText_emptyLogsIncludesCaptureGuidance() {
        val logs = DiagnosticsReportWebPage.renderLogsText(
            sampleReport().copy(
                appLogText = "",
                systemLogText = "",
                crashText = null
            )
        )

        assertTrue(logs.contains("## Capture guidance"))
        assertTrue(logs.contains("create a fresh report right after reproducing it"))
    }

    @Test
    fun renderReportPage_withoutLogsShowsAvailabilityGuidance() {
        val html = DiagnosticsReportWebPage.renderReportPage(
            sampleReport().copy(
                appLogText = "",
                systemLogText = "",
                crashText = null
            )
        )

        assertTrue(html.contains("Log availability"))
        assertTrue(html.contains("metadata but no captured logs yet"))
    }

    @Test
    fun renderMissingReportPage_includesRecoveryActions() {
        val html = DiagnosticsReportWebPage.renderMissingReportPage("report-42")

        assertTrue(html.contains("That report is no longer available"))
        assertTrue(html.contains("Report ID: report-42"))
        assertTrue(html.contains("Back to captured reports"))
    }

    private fun sampleReport(): DiagnosticsStoredReport {
        val manifest = DiagnosticsManifest(
            reportId = "crash-1",
            source = ReportSource.CRASH,
            createdAtEpochMs = 1_700_000_000_000,
            createdAtIsoUtc = "2023-11-14T12:00:00Z",
            packageName = "com.nuvio.tv",
            appVersionName = "0.5.0-beta",
            appVersionCode = 43,
            androidVersion = "12",
            sdkInt = 31,
            manufacturer = "Google",
            brand = "google",
            model = "Chromecast",
            device = "boreal",
            supportedAbis = listOf("arm64-v8a"),
            installerPackageName = "com.android.vending",
            lastRoute = "settings/advanced",
            crashType = "java.lang.IllegalStateException",
            crashMessage = "boom",
            crashThreadName = "main"
        )
        return DiagnosticsStoredReport(
            ref = DiagnosticsReportRef(
                id = manifest.reportId,
                source = manifest.source,
                createdAtEpochMs = manifest.createdAtEpochMs,
                directoryName = manifest.reportId
            ),
            manifest = manifest,
            diagnosticsText = "diagnostics block",
            appLogText = "app log line",
            systemLogText = "system log line",
            crashText = "stack trace",
            userNoteText = "something went wrong"
        )
    }
}
