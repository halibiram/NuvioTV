package com.nuvio.tv.core.diagnostics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CrashRecoveryStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setLastRoute(route: String?) {
        prefs.edit().apply {
            if (route.isNullOrBlank()) {
                remove(KEY_LAST_ROUTE)
            } else {
                putString(KEY_LAST_ROUTE, route)
            }
        }.apply()
    }

    fun getLastRoute(): String? {
        return prefs.getString(KEY_LAST_ROUTE, null)?.takeIf { it.isNotBlank() }
    }

    fun markPendingCrash(
        reportRef: DiagnosticsReportRef,
        crashSummary: CrashSummary,
        lastRoute: String?
    ): Boolean {
        return prefs.edit()
            .putString(KEY_PENDING_REPORT_ID, reportRef.id)
            .putLong(KEY_PENDING_CREATED_AT, reportRef.createdAtEpochMs)
            .putString(KEY_PENDING_LAST_ROUTE, lastRoute)
            .putString(KEY_PENDING_CRASH_TYPE, crashSummary.type)
            .putString(KEY_PENDING_CRASH_MESSAGE, crashSummary.message)
            .putString(KEY_PENDING_THREAD_NAME, crashSummary.threadName)
            .commit()
    }

    fun getPendingCrash(): PendingCrashMarker? {
        val reportId = prefs.getString(KEY_PENDING_REPORT_ID, null)?.takeIf { it.isNotBlank() }
            ?: return null

        return PendingCrashMarker(
            reportId = reportId,
            createdAtEpochMs = prefs.getLong(KEY_PENDING_CREATED_AT, 0L),
            lastRoute = prefs.getString(KEY_PENDING_LAST_ROUTE, null),
            crashType = prefs.getString(KEY_PENDING_CRASH_TYPE, null),
            crashMessage = prefs.getString(KEY_PENDING_CRASH_MESSAGE, null),
            threadName = prefs.getString(KEY_PENDING_THREAD_NAME, null)
        )
    }

    fun clearPendingCrash() {
        prefs.edit()
            .remove(KEY_PENDING_REPORT_ID)
            .remove(KEY_PENDING_CREATED_AT)
            .remove(KEY_PENDING_LAST_ROUTE)
            .remove(KEY_PENDING_CRASH_TYPE)
            .remove(KEY_PENDING_CRASH_MESSAGE)
            .remove(KEY_PENDING_THREAD_NAME)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "diagnostics_crash_recovery"
        private const val KEY_LAST_ROUTE = "last_route"
        private const val KEY_PENDING_REPORT_ID = "pending_report_id"
        private const val KEY_PENDING_CREATED_AT = "pending_created_at"
        private const val KEY_PENDING_LAST_ROUTE = "pending_last_route"
        private const val KEY_PENDING_CRASH_TYPE = "pending_crash_type"
        private const val KEY_PENDING_CRASH_MESSAGE = "pending_crash_message"
        private const val KEY_PENDING_THREAD_NAME = "pending_thread_name"
    }
}
