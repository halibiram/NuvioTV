package com.nuvio.tv.ui.diagnostics

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.diagnostics.CrashRecoveryStore
import com.nuvio.tv.core.diagnostics.DiagnosticsReportManager
import com.nuvio.tv.core.diagnostics.InAppLogBuffer
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.DiagnosticsReportServer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CrashRecoveryUiState(
    val isVisible: Boolean = false,
    val reportId: String? = null,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val detailMessage: String? = null
)

@HiltViewModel
class CrashRecoveryViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val crashRecoveryStore: CrashRecoveryStore,
    private val diagnosticsReportManager: DiagnosticsReportManager,
    private val inAppLogBuffer: InAppLogBuffer
) : ViewModel() {

    private val _uiState = MutableStateFlow(CrashRecoveryUiState())
    val uiState: StateFlow<CrashRecoveryUiState> = _uiState.asStateFlow()

    private var reportServer: DiagnosticsReportServer? = null
    private var hasPreparedPendingCrash = false

    fun preparePendingCrashIfNeeded() {
        if (hasPreparedPendingCrash) return
        hasPreparedPendingCrash = true

        viewModelScope.launch {
            val pendingCrash = crashRecoveryStore.getPendingCrash() ?: return@launch
            inAppLogBuffer.warn(
                TAG,
                "Pending crash report detected id=${pendingCrash.reportId} route=${pendingCrash.lastRoute.orEmpty()}"
            )

            val report = withContext(Dispatchers.IO) {
                diagnosticsReportManager.loadStoredReport(pendingCrash.reportId)
            }

            if (report == null) {
                crashRecoveryStore.clearPendingCrash()
                inAppLogBuffer.warn(TAG, "Pending crash report was missing on disk and has been cleared")
                _uiState.value = CrashRecoveryUiState(
                    isVisible = true,
                    reportId = pendingCrash.reportId,
                    title = context.getString(R.string.diagnostics_crash_qr_title),
                    subtitle = context.getString(R.string.diagnostics_crash_qr_subtitle),
                    detailMessage = context.getString(R.string.diagnostics_crash_missing_report)
                )
                return@launch
            }

            val ipAddress = DeviceIpAddress.get(context)
            if (ipAddress == null) {
                _uiState.value = CrashRecoveryUiState(
                    isVisible = true,
                    reportId = report.ref.id,
                    title = context.getString(R.string.diagnostics_crash_qr_title),
                    subtitle = context.getString(R.string.diagnostics_crash_qr_subtitle),
                    detailMessage = context.getString(R.string.diagnostics_crash_saved_offline)
                )
                return@launch
            }

            val server = DiagnosticsReportServer.startOnAvailablePort(diagnosticsReportManager)
            if (server == null) {
                _uiState.value = CrashRecoveryUiState(
                    isVisible = true,
                    reportId = report.ref.id,
                    title = context.getString(R.string.diagnostics_crash_qr_title),
                    subtitle = context.getString(R.string.diagnostics_crash_qr_subtitle),
                    detailMessage = context.getString(R.string.diagnostics_crash_server_error)
                )
                return@launch
            }

            reportServer = server
            val reportUrl = "http://$ipAddress:${server.listeningPort}/report/${report.ref.id}"
            val qrBitmap = QrCodeGenerator.generate(reportUrl, 512)

            _uiState.value = CrashRecoveryUiState(
                isVisible = true,
                reportId = report.ref.id,
                qrCodeBitmap = qrBitmap,
                serverUrl = reportUrl,
                title = context.getString(R.string.diagnostics_crash_qr_title),
                subtitle = context.getString(R.string.diagnostics_crash_qr_subtitle),
                detailMessage = context.getString(R.string.diagnostics_crash_saved_message)
            )
        }
    }

    fun dismissPrompt() {
        crashRecoveryStore.clearPendingCrash()
        stopServerInternal()
        _uiState.value = CrashRecoveryUiState()
    }

    private fun stopServerInternal() {
        reportServer?.stop()
        reportServer = null
    }

    override fun onCleared() {
        super.onCleared()
        stopServerInternal()
    }

    companion object {
        private const val TAG = "CrashRecovery"
    }
}
