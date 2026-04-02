package com.nuvio.tv.core.network

import android.util.Log
import com.nuvio.tv.BuildConfig
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.Lookup
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type

enum class AppDnsProvider(val storageValue: String) {
    SYSTEM("system"),
    CLOUDFLARE("cloudflare_dns"),
    GOOGLE("google_dns"),
    QUAD9("quad9_dns"),
    ADGUARD("adguard_dns"),
    CLOUDFLARE_DOH("cloudflare_doh"),
    GOOGLE_DOH("google_doh"),
    QUAD9_DOH("quad9_doh"),
    ADGUARD_DOH("adguard_doh"),
    MULLVAD_DOH("mullvad_doh");

    companion object {
        fun fromStorageValue(value: String?): AppDnsProvider {
            return entries.firstOrNull { it.storageValue == value } ?: SYSTEM
        }
    }
}

data class AppDnsConfig(
    val provider: AppDnsProvider = AppDnsProvider.SYSTEM,
    val ipv4FirstEnabled: Boolean = true,
    val dnsCacheEnabled: Boolean = true
)

private data class CachedDnsEntry(
    val addresses: List<InetAddress>,
    val cachedAtMs: Long
)

object AppDnsManager : Dns {
    private const val TAG = "AppDnsManager"
    private const val DNS_CACHE_TTL_MS = 3 * 60 * 60 * 1000L

    private val systemDns: Dns = Dns.SYSTEM
    private val config = AtomicReference(AppDnsConfig())
    private val trackedClients = CopyOnWriteArrayList<WeakReference<OkHttpClient>>()
    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val evictionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val evictionScheduled = AtomicBoolean(false)
    private val plainDnsQueryExecutor = Executors.newFixedThreadPool(4)

    private val dohBootstrapClient by lazy {
        OkHttpClient.Builder()
            .dns(systemDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val cloudflareDns by lazy {
        buildDohResolver(
            endpoint = "https://cloudflare-dns.com/dns-query",
            bootstrapHosts = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
        )
    }

    private val googleDns by lazy {
        buildDohResolver(
            endpoint = "https://dns.google/dns-query",
            bootstrapHosts = listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
        )
    }

    private val quad9DohDns by lazy {
        buildDohResolver(
            endpoint = "https://dns.quad9.net/dns-query",
            bootstrapHosts = listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
        )
    }

    private val adGuardDohDns by lazy {
        buildDohResolver(
            endpoint = "https://dns.adguard-dns.com/dns-query",
            bootstrapHosts = listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff")
        )
    }

    private val mullvadDohDns by lazy {
        buildDohResolver(
            endpoint = "https://dns.mullvad.net/dns-query",
            bootstrapHosts = listOf("194.242.2.2", "194.242.2.3", "2a07:e340::2", "2a07:e340::3")
        )
    }

    private val cloudflarePlainDns by lazy {
        buildPlainDnsResolver(
            serverName = "cloudflare_dns",
            serverAddresses = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
        )
    }

    private val googlePlainDns by lazy {
        buildPlainDnsResolver(
            serverName = "google_dns",
            serverAddresses = listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
        )
    }

    private val quad9PlainDns by lazy {
        buildPlainDnsResolver(
            serverName = "quad9_dns",
            serverAddresses = listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
        )
    }

    private val adGuardPlainDns by lazy {
        buildPlainDnsResolver(
            serverName = "adguard_dns",
            serverAddresses = listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff")
        )
    }

    fun currentConfig(): AppDnsConfig = config.get()

    fun updateProvider(newProvider: AppDnsProvider): Boolean {
        return updateConfig { it.copy(provider = newProvider) }
    }

    fun setIpv4FirstEnabled(enabled: Boolean): Boolean {
        return updateConfig { it.copy(ipv4FirstEnabled = enabled) }
    }

    fun setDnsCacheEnabled(enabled: Boolean): Boolean {
        return updateConfig { it.copy(dnsCacheEnabled = enabled) }
    }

    fun setConfig(newConfig: AppDnsConfig): Boolean {
        return updateConfig { newConfig }
    }

    fun clearCache() {
        val clearedEntries = dnsCache.size
        dnsCache.clear()
        if (BuildConfig.IS_DEBUG_BUILD) {
            Log.d(TAG, "DNS cache cleared entries=$clearedEntries")
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val activeConfig = config.get()
        val startedAtNanos = System.nanoTime()

        getCachedAddresses(hostname = hostname, activeConfig = activeConfig)?.let { cachedAddresses ->
            logLookupSuccess(
                hostname = hostname,
                activeConfig = activeConfig,
                addresses = cachedAddresses,
                elapsedMs = elapsedMs(startedAtNanos),
                fallbackUsed = false,
                cacheHit = true
            )
            return cachedAddresses
        }

        return try {
            val resolvedAddresses = resolveWithProvider(activeConfig.provider, hostname)
            val orderedAddresses = reorderAddresses(resolvedAddresses, activeConfig.ipv4FirstEnabled)
            cacheAddresses(hostname = hostname, activeConfig = activeConfig, addresses = orderedAddresses)
            logLookupSuccess(
                hostname = hostname,
                activeConfig = activeConfig,
                addresses = orderedAddresses,
                elapsedMs = elapsedMs(startedAtNanos),
                fallbackUsed = false,
                cacheHit = false
            )
            orderedAddresses
        } catch (primaryError: Exception) {
            if (activeConfig.provider != AppDnsProvider.SYSTEM) {
                Log.w(
                    TAG,
                    "DNS lookup failed via ${activeConfig.provider.storageValue} for $hostname; falling back to system DNS (${primaryError.javaClass.simpleName}: ${primaryError.message})"
                )
                val fallbackAddresses = reorderAddresses(
                    addresses = systemDns.lookup(hostname),
                    ipv4FirstEnabled = activeConfig.ipv4FirstEnabled
                )
                cacheAddresses(hostname = hostname, activeConfig = activeConfig, addresses = fallbackAddresses)
                logLookupSuccess(
                    hostname = hostname,
                    activeConfig = activeConfig,
                    addresses = fallbackAddresses,
                    elapsedMs = elapsedMs(startedAtNanos),
                    fallbackUsed = true,
                    cacheHit = false
                )
                fallbackAddresses
            } else {
                Log.w(
                    TAG,
                    "DNS lookup failed via system resolver for $hostname (${primaryError.javaClass.simpleName}: ${primaryError.message})"
                )
                throw primaryError
            }
        }
    }

    fun register(client: OkHttpClient): OkHttpClient {
        pruneClearedClients()
        val alreadyTracked = trackedClients.any { it.get() === client }
        if (!alreadyTracked) {
            trackedClients += WeakReference(client)
            if (BuildConfig.IS_DEBUG_BUILD) {
                Log.d(TAG, "Registered DNS-aware client. trackedClients=${trackedClients.size}")
            }
        }
        return client
    }

    private fun updateConfig(transform: (AppDnsConfig) -> AppDnsConfig): Boolean {
        while (true) {
            val currentConfig = config.get()
            val newConfig = transform(currentConfig)
            if (currentConfig == newConfig) return false
            if (config.compareAndSet(currentConfig, newConfig)) {
                val providerChanged = currentConfig.provider != newConfig.provider
                if (providerChanged) {
                    clearCache()
                    scheduleConnectionEviction()
                }
                Log.i(
                    TAG,
                    "DNS config updated provider=${currentConfig.provider.storageValue}->${newConfig.provider.storageValue}, ipv4First=${currentConfig.ipv4FirstEnabled}->${newConfig.ipv4FirstEnabled}, cache=${currentConfig.dnsCacheEnabled}->${newConfig.dnsCacheEnabled}, trackedClients=${trackedClients.size}, evictionScheduled=$providerChanged"
                )
                return true
            }
        }
    }

    private fun scheduleConnectionEviction() {
        if (!evictionScheduled.compareAndSet(false, true)) {
            if (BuildConfig.IS_DEBUG_BUILD) {
                Log.d(TAG, "DNS eviction already scheduled; skipping duplicate request")
            }
            return
        }

        evictionScope.launch {
            try {
                val evictedPools = evictTrackedConnections()
                Log.i(TAG, "DNS connection eviction finished evictedPools=$evictedPools")
            } catch (error: Exception) {
                Log.w(TAG, "DNS connection eviction failed: ${error.message}", error)
            } finally {
                evictionScheduled.set(false)
            }
        }
    }

    private fun resolveWithProvider(provider: AppDnsProvider, hostname: String): List<InetAddress> {
        return when (provider) {
            AppDnsProvider.SYSTEM -> systemDns.lookup(hostname)
            AppDnsProvider.CLOUDFLARE -> cloudflarePlainDns.lookup(hostname)
            AppDnsProvider.GOOGLE -> googlePlainDns.lookup(hostname)
            AppDnsProvider.QUAD9 -> quad9PlainDns.lookup(hostname)
            AppDnsProvider.ADGUARD -> adGuardPlainDns.lookup(hostname)
            AppDnsProvider.CLOUDFLARE_DOH -> cloudflareDns.lookup(hostname)
            AppDnsProvider.GOOGLE_DOH -> googleDns.lookup(hostname)
            AppDnsProvider.QUAD9_DOH -> quad9DohDns.lookup(hostname)
            AppDnsProvider.ADGUARD_DOH -> adGuardDohDns.lookup(hostname)
            AppDnsProvider.MULLVAD_DOH -> mullvadDohDns.lookup(hostname)
        }
    }

    private fun reorderAddresses(
        addresses: List<InetAddress>,
        ipv4FirstEnabled: Boolean
    ): List<InetAddress> {
        if (!ipv4FirstEnabled) return addresses
        return addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    private fun buildDohResolver(endpoint: String, bootstrapHosts: List<String>): DnsOverHttps {
        return DnsOverHttps.Builder()
            .client(dohBootstrapClient)
            .url(endpoint.toHttpUrl())
            .bootstrapDnsHosts(bootstrapHosts.map(InetAddress::getByName))
            .includeIPv6(true)
            .resolvePrivateAddresses(true)
            .resolvePublicAddresses(true)
            .post(false)
            .systemDns(systemDns)
            .build()
    }

    private fun buildPlainDnsResolver(serverName: String, serverAddresses: List<String>): Dns {
        val resolvers = serverAddresses.map { address ->
            SimpleResolver(InetAddress.getByName(address)).apply {
                setTCP(false)
                setIgnoreTruncation(false)
                setTimeout(Duration.ofSeconds(4))
            }
        }
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return resolveViaPlainDns(
                    hostname = hostname,
                    serverName = serverName,
                    resolvers = resolvers
                )
            }
        }
    }

    private fun resolveViaPlainDns(
        hostname: String,
        serverName: String,
        resolvers: List<SimpleResolver>
    ): List<InetAddress> {
        var lastError: Exception? = null
        for (resolver in resolvers) {
            try {
                val addresses = queryResolver(hostname, resolver)
                if (addresses.isNotEmpty()) {
                    return addresses
                }
            } catch (error: Exception) {
                lastError = error
                if (BuildConfig.IS_DEBUG_BUILD) {
                    Log.d(TAG, "Plain DNS resolver $serverName failed for $hostname via ${resolver.address}: ${error.message}")
                }
            }
        }
        throw UnknownHostException(
            buildString {
                append("No DNS answer for ")
                append(hostname)
                append(" via ")
                append(serverName)
                lastError?.message?.let {
                    append(" (")
                    append(it)
                    append(")")
                }
            }
        )
    }

    private fun queryResolver(hostname: String, resolver: SimpleResolver): List<InetAddress> {
        val ipv4Future = plainDnsQueryExecutor.submit<List<InetAddress>> {
            runLookup(hostname = hostname, resolver = resolver, type = Type.A)
        }
        val ipv6Future = plainDnsQueryExecutor.submit<List<InetAddress>> {
            runLookup(hostname = hostname, resolver = resolver, type = Type.AAAA)
        }

        val ipv4Addresses = runCatching { ipv4Future.get() }.getOrElse { emptyList() }
        val ipv6Addresses = runCatching { ipv6Future.get() }.getOrElse { emptyList() }

        if (ipv4Addresses.isEmpty() && ipv6Addresses.isEmpty()) {
            throw UnknownHostException("No DNS records for $hostname via ${resolver.address}")
        }

        return buildList {
            addAll(ipv4Addresses)
            addAll(ipv6Addresses)
        }
    }

    private fun runLookup(hostname: String, resolver: SimpleResolver, type: Int): List<InetAddress> {
        val records = Lookup(hostname, type).apply {
            setResolver(resolver)
            setCache(null)
        }.run().orEmpty()

        return buildList {
            records.forEach { record ->
                when (record) {
                    is ARecord -> add(record.address)
                    is AAAARecord -> add(record.address)
                }
            }
        }
    }

    private fun getCachedAddresses(hostname: String, activeConfig: AppDnsConfig): List<InetAddress>? {
        if (!activeConfig.dnsCacheEnabled) return null
        val cachedEntry = dnsCache[hostname] ?: return null
        val isExpired = System.currentTimeMillis() - cachedEntry.cachedAtMs > DNS_CACHE_TTL_MS
        if (isExpired) {
            dnsCache.remove(hostname)
            return null
        }
        return cachedEntry.addresses
    }

    private fun cacheAddresses(
        hostname: String,
        activeConfig: AppDnsConfig,
        addresses: List<InetAddress>
    ) {
        if (!activeConfig.dnsCacheEnabled || addresses.isEmpty()) return
        dnsCache[hostname] = CachedDnsEntry(
            addresses = addresses,
            cachedAtMs = System.currentTimeMillis()
        )
    }

    private fun logLookupSuccess(
        hostname: String,
        activeConfig: AppDnsConfig,
        addresses: List<InetAddress>,
        elapsedMs: Long,
        fallbackUsed: Boolean,
        cacheHit: Boolean
    ) {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        val addressSummary = addresses.joinToString { it.hostAddress ?: it.toString() }
        Log.d(
            TAG,
            "DNS lookup host=$hostname provider=${activeConfig.provider.storageValue} ipv4First=${activeConfig.ipv4FirstEnabled} cache=${activeConfig.dnsCacheEnabled} cacheHit=$cacheHit fallback=$fallbackUsed elapsedMs=$elapsedMs addresses=[$addressSummary]"
        )
    }

    private fun evictTrackedConnections(): Int {
        pruneClearedClients()
        var evicted = 0
        trackedClients.forEach { reference ->
            reference.get()?.connectionPool?.let { pool ->
                pool.evictAll()
                evicted += 1
            }
        }
        return evicted
    }

    private fun pruneClearedClients() {
        trackedClients.removeAll { it.get() == null }
    }

    private fun elapsedMs(startedAtNanos: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
    }
}

fun OkHttpClient.Builder.buildWithAppDns(): OkHttpClient {
    return AppDnsManager.register(dns(AppDnsManager).build())
}
