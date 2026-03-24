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
            """
            <div class="empty-state stack">
              <strong>No diagnostics reports have been captured on this device yet.</strong>
              <p>Open Advanced settings on the TV and use Report a problem to capture one. If you are checking a crash recovery flow, relaunch the app after reconnecting the TV to your local network.</p>
            </div>
            """.trimIndent()
        } else {
            reports.joinToString(separator = "") { report ->
                """
                <a class="report-card ${reportCardClass(report)}" href="/report/${escapeHtml(report.ref.id)}">
                  <div class="report-card-top">
                    <span class="pill ${reportPillClass(report)}">${reportSourceLabel(report)}</span>
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
        val userNoteBlock = """
            <section class="panel stack">
              <div class="panel-head">
                <h2>User note</h2>
                <p>Edit the short summary you want to include in the copied issue payload. Save changes before using Copy payload or Open issue payload.</p>
              </div>
              <form class="stack" method="post" action="/report/${escapeHtml(report.ref.id)}/note">
                <textarea class="note-input" name="user_note" rows="5" placeholder="Add a short summary, what you tried, or reproduction hints...">${escapeHtml(report.userNoteText.orEmpty())}</textarea>
                <div class="button-row">
                  <button class="btn btn-secondary" type="submit">Save note</button>
                </div>
              </form>
            </section>
        """.trimIndent()

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

        val logAvailabilityBlock = if (
            report.appLogText.isBlank() &&
            !report.systemLogText.ifBlank { "" }.let { com.nuvio.tv.core.diagnostics.SystemLogcatCollector.hasMeaningfulOutput(it) } &&
            report.crashText.isNullOrBlank()
        ) {
            """
            <section class="panel stack">
              <div class="panel-head">
                <h2>Log availability</h2>
                <p>This report has metadata but no captured logs yet. If the problem is reproducible, run the flow again right after reproducing it, or use the next-launch crash prompt if the app exits unexpectedly.</p>
              </div>
            </section>
            """.trimIndent()
        } else {
            ""
        }

        return wrapHtml(
            title = "NuvioTV report ${report.ref.id}",
            body = """
            <section class="hero ${reportHeroClass(report)}">
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
                <a class="btn btn-secondary" href="/report/${escapeHtml(report.ref.id)}/logs">Open logs</a>
                <a class="btn btn-secondary" href="/report/${escapeHtml(report.ref.id)}/download">Download logs</a>
                <button class="btn btn-secondary" type="button" onclick="copyReportPayload('${escapeJs(report.ref.id)}', this)">Copy payload</button>
                <form class="inline-form" method="post" action="/report/${escapeHtml(report.ref.id)}/delete" onsubmit="return confirm('Delete this report from the TV? This cannot be undone.');">
                  <button class="btn btn-danger" type="submit">Delete report</button>
                </form>
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
            $logAvailabilityBlock
            ${renderFooterActions(showDiscord = DiagnosticsSupportLinks.discordUrl.isNotBlank())}
            """.trimIndent()
        )
    }

    fun renderMissingReportPage(reportId: String?): String {
        val safeReportId = reportId?.takeIf { it.isNotBlank() } ?: "unknown"
        return wrapHtml(
            title = "Diagnostics report unavailable",
            body = """
            <section class="hero">
              <span class="eyebrow">Diagnostics unavailable</span>
              <h1>That report is no longer available</h1>
              <p>The report may have been pruned, never fully written, or the link may be incomplete.</p>
            </section>
            <section class="panel stack">
              <div class="panel-head">
                <h2>Next steps</h2>
                <p>Report ID: ${escapeHtml(safeReportId)}</p>
              </div>
              <ul class="rule-list">
                <li>Go back to the diagnostics landing page and open a newer saved report if one exists.</li>
                <li>If you still need this issue, generate a fresh report from the TV and scan the new QR code.</li>
                <li>If this happened after a crash, reproduce it again and reopen the app soon after the crash so recovery data is still available.</li>
              </ul>
              <div class="button-row">
                <a class="btn" href="/">Back to captured reports</a>
              </div>
            </section>
            ${renderFooterActions(showDiscord = DiagnosticsSupportLinks.discordUrl.isNotBlank())}
            """.trimIndent()
        )
    }

    fun renderLogsText(report: DiagnosticsStoredReport): String {
        val hasAppLogs = report.appLogText.isNotBlank()
        val hasSystemLogs = com.nuvio.tv.core.diagnostics.SystemLogcatCollector.hasMeaningfulOutput(report.systemLogText)
        val hasCrashTrace = !report.crashText.isNullOrBlank()
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
            if (!hasAppLogs && !hasSystemLogs && !hasCrashTrace) {
                appendLine()
                appendLine("## Capture guidance")
                appendLine("No log sections contained captured lines. If the problem is reproducible, create a fresh report right after reproducing it. If the app crashed, prefer the next-launch recovery prompt so the saved crash report is not missed.")
            }
            report.crashText?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("## Crash trace")
                appendLine(it)
            }
        }.trim()
    }

    private fun renderFooterActions(showDiscord: Boolean): String {
        val discordButton = if (showDiscord) {
            "<a class=\"btn btn-discord\" href=\"${DiagnosticsSupportLinks.discordUrl}\" target=\"_blank\" rel=\"noreferrer\">${discordIcon()}<span>Open Discord</span></a>"
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
            <a class="btn btn-github" href="${DiagnosticsSupportLinks.githubIssuesUrl}" target="_blank" rel="noreferrer">${githubIcon()}<span>Open GitHub Issues</span></a>
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
              background:
                radial-gradient(circle at top left, rgba(59, 130, 246, 0.22), transparent 34%),
                radial-gradient(circle at top right, rgba(14, 165, 233, 0.18), transparent 26%),
                linear-gradient(180deg, rgba(255,255,255,0.08), rgba(255,255,255,0.03));
              box-shadow: 0 24px 64px rgba(0,0,0,0.28);
              margin-bottom: 18px;
            }
            .hero-manual {
              background:
                radial-gradient(circle at top left, rgba(250, 204, 21, 0.22), transparent 34%),
                radial-gradient(circle at top right, rgba(251, 191, 36, 0.14), transparent 26%),
                linear-gradient(180deg, rgba(255,255,255,0.08), rgba(255,255,255,0.03));
            }
            .hero-crash {
              background:
                radial-gradient(circle at top left, rgba(255, 122, 89, 0.2), transparent 34%),
                radial-gradient(circle at top right, rgba(251, 113, 133, 0.14), transparent 26%),
                linear-gradient(180deg, rgba(255,255,255,0.08), rgba(255,255,255,0.03));
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
            .note-input {
              width: 100%;
              min-height: 136px;
              resize: vertical;
              padding: 16px;
              border-radius: 18px;
              border: 1px solid rgba(255,255,255,0.1);
              background: rgba(255,255,255,0.04);
              color: rgba(255,255,255,0.92);
              font: inherit;
              font-size: 14px;
              line-height: 1.6;
              outline: none;
            }
            .note-input:focus {
              border-color: rgba(216, 230, 255, 0.55);
              box-shadow: 0 0 0 3px rgba(216, 230, 255, 0.12);
            }
            .note-input::placeholder {
              color: rgba(255,255,255,0.38);
            }
            .button-row {
              display: flex;
              flex-wrap: wrap;
              gap: 10px;
            }
            .inline-form { display: inline-flex; }
            .btn {
              display: inline-flex;
              align-items: center;
              justify-content: center;
              gap: 10px;
              min-height: 46px;
              padding: 0 18px;
              border-radius: 999px;
              border: 1px solid rgba(255,255,255,0.14);
              background: rgba(255,255,255,0.06);
              color: #fff;
              font-size: 14px;
              font-weight: 600;
               font-family: inherit;
               cursor: pointer;
               transition: transform 0.2s ease, background 0.2s ease, border-color 0.2s ease;
            }
            .btn-primary {
              background: linear-gradient(135deg, #f7f8fa 0%, #d8e6ff 100%);
              color: #0a0d12;
              border-color: transparent;
            }
            .btn-github {
              background: linear-gradient(135deg, #24292f 0%, #111418 100%);
              border-color: rgba(255,255,255,0.08);
              color: #f6f8fa;
            }
            .btn-discord {
              background: linear-gradient(135deg, #5865f2 0%, #404eed 100%);
              border-color: rgba(255,255,255,0.08);
              color: #ffffff;
            }
            .btn-danger {
              background: rgba(255, 102, 102, 0.14);
              border-color: rgba(255, 102, 102, 0.32);
              color: #ffd6d6;
            }
            .btn-secondary:hover, .btn:hover {
              transform: translateY(-1px);
              border-color: rgba(125, 211, 252, 0.34);
              background: linear-gradient(180deg, rgba(14, 165, 233, 0.14), rgba(255,255,255,0.08));
            }
            .btn-primary:hover {
              background: linear-gradient(135deg, #ffffff 0%, #ebf2ff 100%);
            }
            .btn-github:hover {
              background: linear-gradient(135deg, #2f363d 0%, #161b22 100%);
            }
            .btn-discord:hover {
              background: linear-gradient(135deg, #6975f7 0%, #5662f6 100%);
            }
            .btn-danger:hover {
              background: rgba(255, 102, 102, 0.2);
              border-color: rgba(255, 128, 128, 0.42);
            }
            .btn-disabled {
              opacity: 0.45;
              cursor: default;
            }
            .btn-icon {
              width: 18px;
              height: 18px;
              flex: 0 0 18px;
              display: inline-block;
            }
            .btn-icon svg {
              width: 18px;
              height: 18px;
              display: block;
              fill: currentColor;
            }
            .report-card {
              display: block;
              padding: 16px 18px;
              border-radius: 20px;
              background: rgba(255,255,255,0.03);
              border: 1px solid rgba(255,255,255,0.06);
              transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
            }
            .report-card:hover {
              transform: translateY(-2px);
              box-shadow: 0 18px 36px rgba(0,0,0,0.18);
            }
            .report-card-manual {
              background: linear-gradient(180deg, rgba(250, 204, 21, 0.12), rgba(255,255,255,0.03));
              border-color: rgba(250, 204, 21, 0.28);
            }
            .report-card-manual:hover {
              border-color: rgba(253, 224, 71, 0.46);
            }
            .report-card-crash {
              background: linear-gradient(180deg, rgba(255, 122, 89, 0.11), rgba(255,255,255,0.03));
              border-color: rgba(255, 122, 89, 0.28);
            }
            .report-card-crash:hover {
              border-color: rgba(255, 159, 133, 0.44);
            }
            .pill-manual {
              background: rgba(250, 204, 21, 0.18);
              color: #fff3bf;
            }
            .pill-crash {
              background: rgba(255, 122, 89, 0.18);
              color: #ffd9cf;
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
            .empty-state strong {
              color: rgba(255,255,255,0.88);
              display: block;
              font-size: 16px;
            }
            .empty-state p {
              color: rgba(255,255,255,0.62);
              font-size: 14px;
              line-height: 1.6;
            }
            .footer-panel { margin-top: 18px; }
            @media (max-width: 640px) {
              .page { padding: 18px 14px 42px; }
              .hero, .panel { padding: 18px; border-radius: 22px; }
              .hero h1 { font-size: 24px; }
              .meta-grid { grid-template-columns: 1fr; }
              .button-row { flex-direction: column; }
              .btn { width: 100%; }
              .inline-form { width: 100%; }
              .inline-form .btn { width: 100%; }
              .note-input { font-size: 16px; }
              .report-card-top { flex-direction: column; align-items: flex-start; }
            }
          </style>
          <script>
            async function copyReportPayload(reportId, button) {
              const originalLabel = button && button.textContent ? button.textContent : 'Copy payload';
              try {
                const response = await fetch('/report/' + encodeURIComponent(reportId) + '/payload');
                if (!response.ok) {
                  throw new Error('Payload unavailable');
                }
                const text = await response.text();
                await navigator.clipboard.writeText(text);
                if (button) button.textContent = 'Copied';
              } catch (error) {
                if (button) button.textContent = 'Copy failed';
              }
              if (button) {
                window.setTimeout(() => {
                  button.textContent = originalLabel;
                }, 1600);
              }
            }
          </script>
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

    private fun reportCardClass(report: DiagnosticsStoredReport): String {
        return if (report.ref.source.name.equals("CRASH", ignoreCase = true)) {
            "report-card-crash"
        } else {
            "report-card-manual"
        }
    }

    private fun reportPillClass(report: DiagnosticsStoredReport): String {
        return if (report.ref.source.name.equals("CRASH", ignoreCase = true)) {
            "pill-crash"
        } else {
            "pill-manual"
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

    private fun escapeJs(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
    }

    private fun githubIcon(): String {
        return """
        <span class="btn-icon" aria-hidden="true">
          <svg viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg">
            <path d="M8 0C3.58 0 0 3.67 0 8.2c0 3.63 2.29 6.71 5.47 7.8.4.08.55-.18.55-.4 0-.2-.01-.87-.01-1.58-2.01.38-2.53-.5-2.69-.96-.09-.24-.48-.97-.82-1.17-.28-.16-.68-.56-.01-.57.63-.01 1.08.59 1.23.83.72 1.24 1.87.89 2.33.68.07-.54.28-.89.51-1.09-1.78-.21-3.64-.92-3.64-4.08 0-.9.31-1.64.82-2.22-.08-.21-.36-1.05.08-2.19 0 0 .67-.22 2.2.85.64-.18 1.32-.28 2-.28s1.36.1 2 .28c1.53-1.07 2.2-.85 2.2-.85.44 1.14.16 1.98.08 2.19.51.58.82 1.31.82 2.22 0 3.17-1.87 3.87-3.65 4.08.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .22.15.49.55.4A8.22 8.22 0 0 0 16 8.2C16 3.67 12.42 0 8 0Z"/>
          </svg>
        </span>
        """.trimIndent()
    }

    private fun discordIcon(): String {
        return """
        <span class="btn-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <path d="M20.32 4.37A19.79 19.79 0 0 0 15.45 3c-.21.38-.45.88-.62 1.28a18.42 18.42 0 0 0-5.66 0c-.17-.4-.42-.9-.63-1.28-1.7.29-3.33.76-4.87 1.37C.59 8.87-.24 13.26.17 17.59a19.98 19.98 0 0 0 5.98 3.04c.48-.67.91-1.39 1.28-2.14-.7-.27-1.37-.61-2-.99.17-.13.33-.27.49-.41 3.84 1.84 8 1.84 11.8 0 .16.14.32.28.49.41-.63.38-1.3.72-2 .99.37.75.8 1.47 1.28 2.14a19.9 19.9 0 0 0 5.99-3.04c.48-5.02-.82-9.37-3.16-13.22ZM8.68 14.96c-1.15 0-2.1-1.08-2.1-2.4 0-1.33.93-2.4 2.1-2.4 1.18 0 2.12 1.08 2.1 2.4 0 1.33-.93 2.4-2.1 2.4Zm6.64 0c-1.15 0-2.1-1.08-2.1-2.4 0-1.33.93-2.4 2.1-2.4 1.18 0 2.12 1.08 2.1 2.4 0 1.33-.92 2.4-2.1 2.4Z"/>
          </svg>
        </span>
        """.trimIndent()
    }

    private fun reportHeroClass(report: DiagnosticsStoredReport): String {
        return if (report.ref.source.name.equals("CRASH", ignoreCase = true)) {
            "hero-crash"
        } else {
            "hero-manual"
        }
    }
}
