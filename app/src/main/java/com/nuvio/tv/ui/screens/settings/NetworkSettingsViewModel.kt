package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.AppDnsManager
import com.nuvio.tv.core.network.AppDnsProvider
import com.nuvio.tv.data.local.NetworkDnsSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class NetworkSettingsViewModel @Inject constructor(
    private val dataStore: NetworkDnsSettingsDataStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(NetworkSettingsUiState())
    val uiState: StateFlow<NetworkSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        dnsProvider = settings.provider,
                        ipv4FirstEnabled = settings.ipv4FirstEnabled
                    )
                }
            }
        }
    }

    fun setDnsProvider(provider: AppDnsProvider) {
        viewModelScope.launch {
            AppDnsManager.updateProvider(provider)
            dataStore.setProvider(provider)
        }
    }

    fun setIpv4FirstEnabled(enabled: Boolean) {
        viewModelScope.launch {
            AppDnsManager.setIpv4FirstEnabled(enabled)
            dataStore.setIpv4FirstEnabled(enabled)
        }
    }
}

data class NetworkSettingsUiState(
    val dnsProvider: AppDnsProvider = AppDnsProvider.SYSTEM,
    val ipv4FirstEnabled: Boolean = true
)
