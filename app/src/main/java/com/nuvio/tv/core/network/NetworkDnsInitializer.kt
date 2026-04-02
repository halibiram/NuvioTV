package com.nuvio.tv.core.network

import com.nuvio.tv.data.local.NetworkDnsSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@Singleton
class NetworkDnsInitializer @Inject constructor(
    private val dataStore: NetworkDnsSettingsDataStore
) {
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            dataStore.settings.collectLatest { settings ->
                AppDnsManager.setConfig(
                    AppDnsConfig(
                        provider = settings.provider,
                        ipv4FirstEnabled = settings.ipv4FirstEnabled,
                        dnsCacheEnabled = settings.dnsCacheEnabled
                    )
                )
            }
        }
    }
}
