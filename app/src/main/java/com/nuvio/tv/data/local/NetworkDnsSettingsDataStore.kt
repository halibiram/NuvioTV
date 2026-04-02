package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.core.network.AppDnsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.networkDnsDataStore: DataStore<Preferences> by preferencesDataStore(name = "network_dns_settings")

@Singleton
class NetworkDnsSettingsDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val dataStore = context.networkDnsDataStore
    private val providerKey = stringPreferencesKey("network_dns_provider")
    private val legacyModeKey = stringPreferencesKey("network_dns_mode")
    private val ipv4FirstEnabledKey = booleanPreferencesKey("network_dns_ipv4_first_enabled")
    private val dnsCacheEnabledKey = booleanPreferencesKey("network_dns_cache_enabled")

    val settings: Flow<NetworkDnsSettings> = dataStore.data.map { prefs ->
        val legacyValue = prefs[legacyModeKey]
        val provider = when {
            prefs[providerKey] != null -> AppDnsProvider.fromStorageValue(prefs[providerKey])
            legacyValue == "ipv4_first" -> AppDnsProvider.SYSTEM
            else -> AppDnsProvider.fromStorageValue(legacyValue)
        }
        val ipv4FirstEnabled = prefs[ipv4FirstEnabledKey]
            ?: when (legacyValue) {
                null, "ipv4_first" -> true
                else -> false
            }
        val dnsCacheEnabled = prefs[dnsCacheEnabledKey] ?: true
        NetworkDnsSettings(
            provider = provider,
            ipv4FirstEnabled = ipv4FirstEnabled,
            dnsCacheEnabled = dnsCacheEnabled
        )
    }

    suspend fun setProvider(provider: AppDnsProvider) {
        dataStore.edit { prefs ->
            prefs[providerKey] = provider.storageValue
        }
    }

    suspend fun setIpv4FirstEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[ipv4FirstEnabledKey] = enabled
        }
    }

    suspend fun setDnsCacheEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[dnsCacheEnabledKey] = enabled
        }
    }
}

data class NetworkDnsSettings(
    val provider: AppDnsProvider = AppDnsProvider.SYSTEM,
    val ipv4FirstEnabled: Boolean = true,
    val dnsCacheEnabled: Boolean = true
)
