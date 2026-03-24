package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.diagnostics.DiagnosticsReportManager
import com.nuvio.tv.core.diagnostics.InAppLogBuffer
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.DiagnosticsReportServer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdvancedDiagnosticsUiState(
    val isGeneratingReport: Boolean = false,
    val activeReportId: String? = null,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
) {
    val isQrVisible: Boolean = qrCodeBitmap != null && !serverUrl.isNullOrBlank()
}

@HiltViewModel
class AdvancedDiagnosticsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val diagnosticsReportManager: DiagnosticsReportManager,
    private val inAppLogBuffer: InAppLogBuffer
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdvancedDiagnosticsUiState())
    val uiState: StateFlow<AdvancedDiagnosticsUiState> = _uiState.asStateFlow()

    private var reportServer: DiagnosticsReportServer? = null

    fun generateManualReport() {
        if (_uiState.value.isGeneratingReport) return

        viewModelScope.launch {
            inAppLogBuffer.info(TAG, "Manual diagnostics capture requested from Advanced settings")
            stopServerInternal()
            _uiState.update {
                it.copy(
                    isGeneratingReport = true,
                    statusMessage = null,
                    errorMessage = null,
                    qrCodeBitmap = null,
                    serverUrl = null
                )
            }

            val reportResult = diagnosticsReportManager.createManualReport(
                userNote = context.getString(R.string.diagnostics_report_user_note_default)
            )

            reportResult.onSuccess { reportRef ->
                val ip = DeviceIpAddress.get(context)
                if (ip == null) {
                    inAppLogBuffer.warn(TAG, "Manual diagnostics report created but no LAN IP was available")
                    _uiState.update {
                        it.copy(
                            isGeneratingReport = false,
                            activeReportId = reportRef.id,
                            statusMessage = context.getString(R.string.diagnostics_report_saved_offline),
                            errorMessage = null
                        )
                    }
                    return@onSuccess
                }

                val server = DiagnosticsReportServer.startOnAvailablePort(diagnosticsReportManager)
                if (server == null) {
                    inAppLogBuffer.warn(TAG, "Manual diagnostics report created but local server failed to start")
                    _uiState.update {
                        it.copy(
                            isGeneratingReport = false,
                            activeReportId = reportRef.id,
                            statusMessage = null,
                            errorMessage = context.getString(R.string.diagnostics_report_server_error)
                        )
                    }
                    return@onSuccess
                }

                reportServer = server
                val reportUrl = "http://$ip:${server.listeningPort}/report/${reportRef.id}"
                val qrBitmap = QrCodeGenerator.generate(reportUrl, 512)
                inAppLogBuffer.info(TAG, "Manual diagnostics report ready id=${reportRef.id} url=$reportUrl")

                _uiState.update {
                    it.copy(
                        isGeneratingReport = false,
                        activeReportId = reportRef.id,
                        qrCodeBitmap = qrBitmap,
                        serverUrl = reportUrl,
                        statusMessage = context.getString(R.string.diagnostics_report_ready_message),
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                inAppLogBuffer.error(TAG, "Manual diagnostics report generation failed", error)
                _uiState.update {
                    it.copy(
                        isGeneratingReport = false,
                        statusMessage = null,
                        errorMessage = error.localizedMessage
                            ?: context.getString(R.string.diagnostics_report_generic_error)
                    )
                }
            }
        }
    }

    fun dismissQrFlow() {
        stopServerInternal()
        _uiState.update {
            it.copy(
                qrCodeBitmap = null,
                serverUrl = null,
                statusMessage = null
            )
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
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
        private const val TAG = "AdvancedDiagnostics"
    }
}
