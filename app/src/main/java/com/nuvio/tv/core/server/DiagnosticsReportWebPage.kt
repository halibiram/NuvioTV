package com.nuvio.tv.core.server

import com.nuvio.tv.core.diagnostics.DiagnosticsSupportLinks
import com.nuvio.tv.core.diagnostics.DiagnosticsStoredReport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DiagnosticsReportWebPage {

    fun renderLandingPage(reports: List<DiagnosticsStoredReport>): String {
        val reportsHtml = if (reports.isEmpty()) {
            "<div class=\"empty-state\">No diagnostics reports have been captured on this device yet.</div>"
        } else {
            reports.joinToString(separator = "") { report ->
                """
                <a class="report-card" href="/report/${escapeHtml(report.ref.id)}">
                  <div class="report-card-top">
                    <span class="pill">${reportSourceLabel(report)}</span>
                    <span class="muted">${escapeHtml(formatTimestamp(report.manifest.createdAtEpochMs))}</span>
                  </div>
                  <h3>${escapeHtml(reportTitle(report))}</h3>
                  <p>${escapeHtml(reportSubtitle(report))}</p>
                </a>
                """.trimIndent()
            }
        }

        return wrapHtml(
            title = "NuvioTV diagnostics",
            body = """
            <section class="hero">
              <span class="eyebrow">NuvioTV diagnostics</span>
              <h1>Captured reports on this TV</h1>
            <p>Open the latest report, review the redacted diagnostics, then create one GitHub issue for one problem.</p>
            </section>
            <section class="panel stack">
              <div class="panel-head">
                <h2>Recent reports</h2>
                <p>Newer reports appear first.</p>
              </div>
              $reportsHtml
            </section>
            ${renderFooterActions(showDiscord = DiagnosticsSupportLinks.discordUrl.isNotBlank())}
            """.trimIndent()
        )
    }

    fun renderReportPage(report: DiagnosticsStoredReport): String {
        val diagnosticsPreview = escapeHtml(report.diagnosticsText.take(1400).trim())
        val userNoteBlock = report.userNoteText?.takeIf { it.isNotBlank() }?.let {
            """
            <section class="panel stack">
              <div class="panel-head">
                <h2>User note</h2>
              </div>
              <pre>${escapeHtml(it)}</pre>
            </section>
            """.trimIndent()
        }.orEmpty()

        val crashBlock = report.crashText?.takeIf { it.isNotBlank() }?.let {
            """
            <section class="panel stack">
              <div class="panel-head">
                <h2>Crash trace</h2>
                <p>Crash reports require logs or a stack trace.</p>
              </div>
              <pre>${escapeHtml(it.take(5000))}</pre>
            </section>
            """.trimIndent()
        }.orEmpty()

        return wrapHtml(
            title = "NuvioTV report ${report.ref.id}",
            body = """
            <section class="hero">
              <span class="eyebrow">${reportSourceLabel(report)}</span>
              <h1>${escapeHtml(reportTitle(report))}</h1>
              <p>${escapeHtml(reportSubtitle(report))}</p>
            </section>

            <section class="panel stack">
              <div class="panel-head">
                <h2>Before you open an issue</h2>
                <p>Follow the repository rules so the report is actionable.</p>
              </div>
              <ul class="rule-list">
                <li>Open one issue for one problem.</li>
                <li>Use a short, specific title. Do not leave the default placeholder.</li>
                <li>Include app version, install method, device model, and Android version.</li>
                <li>Describe exact steps, expected behavior, actual behavior, and frequency.</li>
                <li>Crash reports require logs or a stack trace.</li>
              </ul>
            </section>

            <section class="meta-grid">
              <article class="panel stat-card"><span class="meta-label">App</span><strong>${escapeHtml(report.manifest.appVersionName)} (${report.manifest.appVersionCode})</strong></article>
              <article class="panel stat-card"><span class="meta-label">Android</span><strong>${escapeHtml(report.manifest.androidVersion)} / SDK ${report.manifest.sdkInt}</strong></article>
              <article class="panel stat-card"><span class="meta-label">Device</span><strong>${escapeHtml(report.manifest.manufacturer)} ${escapeHtml(report.manifest.model)}</strong></article>
              <article class="panel stat-card"><span class="meta-label">Route</span><strong>${escapeHtml(report.manifest.lastRoute ?: "unknown")}</strong></article>
            </section>

            <section class="panel stack">
              <div class="panel-head">
                <h2>Diagnostics summary</h2>
                <p>This block is sanitized for common secrets and URL query strings, but still review it before sharing publicly.</p>
              </div>
              <pre>$diagnosticsPreview</pre>
              <div class="button-row">
                <a class="btn" href="/report/${escapeHtml(report.ref.id)}/payload">Open issue payload</a>
                <a class="btn btn-secondary" href="/report/${escapeHtml(report.ref.id)}/logs">Open logs</a>
              </div>
            </section>

            <section class="panel stack">
              <div class="panel-head">
                <h2>Privacy check</h2>
                <p>Obvious secrets, private LAN hosts, email addresses, and URL query strings are redacted automatically. Still confirm the text looks safe before posting it to GitHub or Discord.</p>
              </div>
            </section>

            $userNoteBlock
            $crashBlock
            ${renderFooterActions(showDiscord = DiagnosticsSupportLinks.discordUrl.isNotBlank())}
            """.trimIndent()
        )
    }

    fun renderLogsText(report: DiagnosticsStoredReport): String {
        return buildString {
            appendLine("# Diagnostics report ${report.ref.id}")
            appendLine("# Source: ${report.ref.source.name.lowercase()}")
            appendLine("# Created: ${formatTimestamp(report.manifest.createdAtEpochMs)}")
            appendLine("# Privacy: obvious secrets, private hosts, email addresses, and URL query strings are redacted automatically. Review before sharing publicly.")
            appendLine()
            appendLine("## Diagnostics")
            appendLine(report.diagnosticsText.ifBlank { "No diagnostics summary available." })
            appendLine()
            appendLine("## In-app logs")
            appendLine(report.appLogText.ifBlank { "No in-app logs captured." })
            appendLine()
            appendLine("## System logcat")
            appendLine(report.systemLogText.ifBlank { "System logcat unavailable for this report." })
            report.crashText?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("## Crash trace")
                appendLine(it)
            }
        }.trim()
    }

    private fun renderFooterActions(showDiscord: Boolean): String {
        val discordButton = if (showDiscord) {
            "<a class=\"btn btn-secondary\" href=\"${DiagnosticsSupportLinks.discordUrl}\" target=\"_blank\" rel=\"noreferrer\">Open Discord</a>"
        } else {
            "<span class=\"btn btn-disabled\">Discord link not configured</span>"
        }

        return """
        <section class="panel stack footer-panel">
          <div class="panel-head">
            <h2>Report this problem</h2>
            <p>Use GitHub for fixable bug reports. Discord can be used for quick community follow-up and report discussion.</p>
          </div>
          <div class="button-row">
            <a class="btn btn-primary" href="${DiagnosticsSupportLinks.githubIssuesUrl}" target="_blank" rel="noreferrer">Open GitHub Issues</a>
            $discordButton
          </div>
        </section>
        """.trimIndent()
    }

    private fun wrapHtml(title: String, body: String): String {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
          <title>${escapeHtml(title)}</title>
          <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800&display=swap" rel="stylesheet">
          <style>
            * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
            body {
              font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
              min-height: 100vh;
              margin: 0;
              color: #f3f4f6;
              background:
                radial-gradient(circle at top left, rgba(63, 94, 251, 0.18), transparent 28%),
                radial-gradient(circle at top right, rgba(17, 205, 239, 0.12), transparent 24%),
                linear-gradient(180deg, #040508 0%, #0c1117 48%, #060709 100%);
            }
            a { color: inherit; text-decoration: none; }
            .page { max-width: 760px; margin: 0 auto; padding: 28px 18px 56px; }
            .hero {
              padding: 28px;
              border: 1px solid rgba(255,255,255,0.08);
              border-radius: 28px;
              background: linear-gradient(180deg, rgba(255,255,255,0.06), rgba(255,255,255,0.03));
              box-shadow: 0 24px 64px rgba(0,0,0,0.28);
              margin-bottom: 18px;
            }
            .eyebrow, .meta-label, .pill {
              display: inline-flex;
              font-size: 11px;
              line-height: 1;
              letter-spacing: 0.16em;
              text-transform: uppercase;
              color: rgba(255,255,255,0.62);
            }
            .pill {
              padding: 8px 10px;
              border-radius: 999px;
              background: rgba(255,255,255,0.08);
              color: rgba(255,255,255,0.84);
            }
            .hero h1 {
              font-size: 30px;
              line-height: 1.08;
              letter-spacing: -0.04em;
              margin-top: 14px;
            }
            .hero p {
              margin-top: 10px;
              color: rgba(255,255,255,0.7);
              font-size: 15px;
              line-height: 1.6;
            }
            .stack { display: grid; gap: 14px; }
            .panel {
              padding: 22px;
              border-radius: 24px;
              border: 1px solid rgba(255,255,255,0.08);
              background: rgba(11, 15, 20, 0.86);
              backdrop-filter: blur(14px);
              -webkit-backdrop-filter: blur(14px);
              margin-top: 16px;
            }
            .panel-head h2 { font-size: 18px; letter-spacing: -0.02em; }
            .panel-head p, .muted {
              color: rgba(255,255,255,0.58);
              font-size: 13px;
              line-height: 1.55;
              margin-top: 6px;
            }
            .meta-grid {
              display: grid;
              grid-template-columns: repeat(2, minmax(0, 1fr));
              gap: 14px;
              margin-top: 16px;
            }
            .stat-card strong {
              display: block;
              margin-top: 8px;
              font-size: 15px;
              line-height: 1.45;
            }
            .rule-list {
              display: grid;
              gap: 10px;
              padding-left: 18px;
              color: rgba(255,255,255,0.84);
            }
            pre {
              white-space: pre-wrap;
              word-break: break-word;
              padding: 16px;
              border-radius: 18px;
              background: rgba(255,255,255,0.04);
              color: rgba(255,255,255,0.84);
              font-size: 12px;
              line-height: 1.55;
              overflow: hidden;
            }
            .button-row {
              display: flex;
              flex-wrap: wrap;
              gap: 10px;
            }
            .btn {
              display: inline-flex;
              align-items: center;
              justify-content: center;
              min-height: 46px;
              padding: 0 18px;
              border-radius: 999px;
              border: 1px solid rgba(255,255,255,0.14);
              background: rgba(255,255,255,0.06);
              color: #fff;
              font-size: 14px;
              font-weight: 600;
              transition: transform 0.2s ease, background 0.2s ease, border-color 0.2s ease;
            }
            .btn-primary {
              background: linear-gradient(135deg, #f7f8fa 0%, #d8e6ff 100%);
              color: #0a0d12;
              border-color: transparent;
            }
            .btn-secondary:hover, .btn:hover {
              transform: translateY(-1px);
              border-color: rgba(255,255,255,0.28);
              background: rgba(255,255,255,0.12);
            }
            .btn-primary:hover {
              background: linear-gradient(135deg, #ffffff 0%, #ebf2ff 100%);
            }
            .btn-disabled {
              opacity: 0.45;
              cursor: default;
            }
            .report-card {
              display: block;
              padding: 16px 18px;
              border-radius: 20px;
              background: rgba(255,255,255,0.03);
              border: 1px solid rgba(255,255,255,0.06);
            }
            .report-card-top {
              display: flex;
              align-items: center;
              justify-content: space-between;
              gap: 12px;
              margin-bottom: 12px;
            }
            .report-card h3 {
              font-size: 17px;
              letter-spacing: -0.02em;
            }
            .report-card p {
              margin-top: 6px;
              color: rgba(255,255,255,0.62);
              font-size: 14px;
              line-height: 1.55;
            }
            .empty-state {
              padding: 26px;
              border-radius: 20px;
              background: rgba(255,255,255,0.03);
              color: rgba(255,255,255,0.5);
              text-align: center;
            }
            .footer-panel { margin-top: 18px; }
            @media (max-width: 640px) {
              .page { padding: 18px 14px 42px; }
              .hero, .panel { padding: 18px; border-radius: 22px; }
              .hero h1 { font-size: 24px; }
              .meta-grid { grid-template-columns: 1fr; }
              .button-row { flex-direction: column; }
              .btn { width: 100%; }
              .report-card-top { flex-direction: column; align-items: flex-start; }
            }
          </style>
        </head>
        <body>
          <main class="page">
            $body
          </main>
        </body>
        </html>
        """.trimIndent()
    }

    private fun reportSourceLabel(report: DiagnosticsStoredReport): String {
        return if (report.ref.source.name.equals("CRASH", ignoreCase = true)) {
            "Previous app crash"
        } else {
            "Manual report"
        }
    }

    private fun reportTitle(report: DiagnosticsStoredReport): String {
        return when {
            !report.manifest.crashType.isNullOrBlank() -> report.manifest.crashType.orEmpty()
            !report.userNoteText.isNullOrBlank() -> report.userNoteText.orEmpty().lineSequence().firstOrNull().orEmpty()
            else -> "NuvioTV diagnostics report"
        }
    }

    private fun reportSubtitle(report: DiagnosticsStoredReport): String {
        val route = report.manifest.lastRoute ?: "unknown screen"
        return if (report.ref.source.name.equals("CRASH", ignoreCase = true)) {
            "Captured after a crash near $route. Review the diagnostics below before opening an issue."
        } else {
            "Manual report captured near $route. Include exact steps and expected vs actual behavior."
        }
    }

    private fun formatTimestamp(epochMs: Long): String {
        if (epochMs <= 0L) return "unknown"
        return runCatching {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMs))
        }.getOrDefault("unknown")
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
