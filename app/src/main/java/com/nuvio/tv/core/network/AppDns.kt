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

private data class DnsLookupResult(
    val addresses: List<InetAddress>,
    val ttlMs: Long? = null,
    val ttlSource: String = "default"
)

private data class CachedDnsEntry(
    val addresses: List<InetAddress>,
    val cachedAtMs: Long,
    val expiresAtMs: Long,
    val ttlMs: Long,
    val ttlSource: String
)

private data class PlainDnsProviderConfig(
    val serverName: String,
    val resolvers: List<SimpleResolver>
)

private data class DnsRecordResult(
    val addresses: List<InetAddress>,
    val ttlMs: Long?
)

object AppDnsManager : Dns {
    private const val TAG = "AppDnsManager"
    private const val DEFAULT_DNS_CACHE_TTL_MS = 3 * 60 * 60 * 1000L

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
        buildPlainDnsProvider(
            serverName = "cloudflare_dns",
            serverAddresses = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
        )
    }

    private val googlePlainDns by lazy {
        buildPlainDnsProvider(
            serverName = "google_dns",
            serverAddresses = listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
        )
    }

    private val quad9PlainDns by lazy {
        buildPlainDnsProvider(
            serverName = "quad9_dns",
            serverAddresses = listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
        )
    }

    private val adGuardPlainDns by lazy {
        buildPlainDnsProvider(
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

        getCachedEntry(hostname = hostname, activeConfig = activeConfig)?.let { cachedEntry ->
            logLookupSuccess(
                hostname = hostname,
                activeConfig = activeConfig,
                addresses = cachedEntry.addresses,
                elapsedMs = elapsedMs(startedAtNanos),
                fallbackUsed = false,
                cacheHit = true,
                ttlMs = cachedEntry.ttlMs,
                ttlSource = cachedEntry.ttlSource,
                ttlRemainingMs = (cachedEntry.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            )
            return cachedEntry.addresses
        }

        return try {
            val resolvedResult = resolveWithProvider(activeConfig.provider, hostname)
            val orderedAddresses = reorderAddresses(resolvedResult.addresses, activeConfig.ipv4FirstEnabled)
            val cachedTtlMs = cacheAddresses(
                hostname = hostname,
                activeConfig = activeConfig,
                addresses = orderedAddresses,
                resolvedTtlMs = resolvedResult.ttlMs,
                ttlSource = resolvedResult.ttlSource
            )
            logLookupSuccess(
                hostname = hostname,
                activeConfig = activeConfig,
                addresses = orderedAddresses,
                elapsedMs = elapsedMs(startedAtNanos),
                fallbackUsed = false,
                cacheHit = false,
                ttlMs = cachedTtlMs,
                ttlSource = resolvedResult.ttlSource,
                ttlRemainingMs = cachedTtlMs
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
                val cachedTtlMs = cacheAddresses(
                    hostname = hostname,
                    activeConfig = activeConfig,
                    addresses = fallbackAddresses,
                    resolvedTtlMs = null,
                    ttlSource = "default"
                )
                logLookupSuccess(
                    hostname = hostname,
                    activeConfig = activeConfig,
                    addresses = fallbackAddresses,
                    elapsedMs = elapsedMs(startedAtNanos),
                    fallbackUsed = true,
                    cacheHit = false,
                    ttlMs = cachedTtlMs,
                    ttlSource = "default",
                    ttlRemainingMs = cachedTtlMs
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

    private fun resolveWithProvider(provider: AppDnsProvider, hostname: String): DnsLookupResult {
        return when (provider) {
            AppDnsProvider.SYSTEM -> DnsLookupResult(addresses = systemDns.lookup(hostname))
            AppDnsProvider.CLOUDFLARE -> resolveViaPlainDns(hostname = hostname, providerConfig = cloudflarePlainDns)
            AppDnsProvider.GOOGLE -> resolveViaPlainDns(hostname = hostname, providerConfig = googlePlainDns)
            AppDnsProvider.QUAD9 -> resolveViaPlainDns(hostname = hostname, providerConfig = quad9PlainDns)
            AppDnsProvider.ADGUARD -> resolveViaPlainDns(hostname = hostname, providerConfig = adGuardPlainDns)
            AppDnsProvider.CLOUDFLARE_DOH -> DnsLookupResult(addresses = cloudflareDns.lookup(hostname))
            AppDnsProvider.GOOGLE_DOH -> DnsLookupResult(addresses = googleDns.lookup(hostname))
            AppDnsProvider.QUAD9_DOH -> DnsLookupResult(addresses = quad9DohDns.lookup(hostname))
            AppDnsProvider.ADGUARD_DOH -> DnsLookupResult(addresses = adGuardDohDns.lookup(hostname))
            AppDnsProvider.MULLVAD_DOH -> DnsLookupResult(addresses = mullvadDohDns.lookup(hostname))
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

    private fun buildPlainDnsProvider(serverName: String, serverAddresses: List<String>): PlainDnsProviderConfig {
        return PlainDnsProviderConfig(
            serverName = serverName,
            resolvers = serverAddresses.map { address ->
                SimpleResolver(InetAddress.getByName(address)).apply {
                    setTCP(false)
                    setIgnoreTruncation(false)
                    setTimeout(Duration.ofSeconds(4))
                }
            }
        )
    }

    private fun resolveViaPlainDns(
        hostname: String,
        providerConfig: PlainDnsProviderConfig
    ): DnsLookupResult {
        var lastError: Exception? = null
        for (resolver in providerConfig.resolvers) {
            try {
                val result = queryResolver(hostname, resolver)
                if (result.addresses.isNotEmpty()) {
                    return DnsLookupResult(
                        addresses = result.addresses,
                        ttlMs = result.ttlMs,
                        ttlSource = if (result.ttlMs != null) "dnsjava" else "default"
                    )
                }
            } catch (error: Exception) {
                lastError = error
                if (BuildConfig.IS_DEBUG_BUILD) {
                    Log.d(TAG, "Plain DNS resolver ${providerConfig.serverName} failed for $hostname via ${resolver.address}: ${error.message}")
                }
            }
        }
        throw UnknownHostException(
            buildString {
                append("No DNS answer for ")
                append(hostname)
                append(" via ")
                append(providerConfig.serverName)
                lastError?.message?.let {
                    append(" (")
                    append(it)
                    append(")")
                }
            }
        )
    }

    private fun queryResolver(hostname: String, resolver: SimpleResolver): DnsRecordResult {
        val ipv4Future = plainDnsQueryExecutor.submit<DnsRecordResult> {
            runLookup(hostname = hostname, resolver = resolver, type = Type.A)
        }
        val ipv6Future = plainDnsQueryExecutor.submit<DnsRecordResult> {
            runLookup(hostname = hostname, resolver = resolver, type = Type.AAAA)
        }

        val ipv4Result = runCatching { ipv4Future.get() }.getOrElse { DnsRecordResult(emptyList(), null) }
        val ipv6Result = runCatching { ipv6Future.get() }.getOrElse { DnsRecordResult(emptyList(), null) }

        if (ipv4Result.addresses.isEmpty() && ipv6Result.addresses.isEmpty()) {
            throw UnknownHostException("No DNS records for $hostname via ${resolver.address}")
        }

        val ttlMs = listOfNotNull(ipv4Result.ttlMs, ipv6Result.ttlMs).minOrNull()
        return DnsRecordResult(
            addresses = buildList {
                addAll(ipv4Result.addresses)
                addAll(ipv6Result.addresses)
            },
            ttlMs = ttlMs
        )
    }

    private fun runLookup(hostname: String, resolver: SimpleResolver, type: Int): DnsRecordResult {
        val records = Lookup(hostname, type).apply {
            setResolver(resolver)
            setCache(null)
        }.run().orEmpty()

        val addresses = buildList {
            records.forEach { record ->
                when (record) {
                    is ARecord -> add(record.address)
                    is AAAARecord -> add(record.address)
                }
            }
        }
        val ttlMs = records
            .map { it.ttl.coerceAtLeast(0L) }
            .minOrNull()
            ?.let { TimeUnit.SECONDS.toMillis(it) }
        return DnsRecordResult(addresses = addresses, ttlMs = ttlMs)
    }

    private fun getCachedEntry(hostname: String, activeConfig: AppDnsConfig): CachedDnsEntry? {
        if (!activeConfig.dnsCacheEnabled) return null
        val cachedEntry = dnsCache[hostname] ?: return null
        val isExpired = System.currentTimeMillis() >= cachedEntry.expiresAtMs
        if (isExpired) {
            dnsCache.remove(hostname)
            return null
        }
        return cachedEntry
    }

    private fun cacheAddresses(
        hostname: String,
        activeConfig: AppDnsConfig,
        addresses: List<InetAddress>,
        resolvedTtlMs: Long?,
        ttlSource: String
    ): Long {
        val effectiveTtlMs = resolvedTtlMs ?: DEFAULT_DNS_CACHE_TTL_MS
        if (!activeConfig.dnsCacheEnabled || addresses.isEmpty()) return effectiveTtlMs
        val now = System.currentTimeMillis()
        dnsCache[hostname] = CachedDnsEntry(
            addresses = addresses,
            cachedAtMs = now,
            expiresAtMs = now + effectiveTtlMs,
            ttlMs = effectiveTtlMs,
            ttlSource = ttlSource
        )
        return effectiveTtlMs
    }

    private fun logLookupSuccess(
        hostname: String,
        activeConfig: AppDnsConfig,
        addresses: List<InetAddress>,
        elapsedMs: Long,
        fallbackUsed: Boolean,
        cacheHit: Boolean,
        ttlMs: Long,
        ttlSource: String,
        ttlRemainingMs: Long
    ) {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        val addressSummary = addresses.joinToString { it.hostAddress ?: it.toString() }
        Log.d(
            TAG,
            "DNS lookup host=$hostname provider=${activeConfig.provider.storageValue} ipv4First=${activeConfig.ipv4FirstEnabled} cache=${activeConfig.dnsCacheEnabled} cacheHit=$cacheHit fallback=$fallbackUsed elapsedMs=$elapsedMs ttlMs=$ttlMs ttlRemainingMs=$ttlRemainingMs ttlSource=$ttlSource addresses=[$addressSummary]"
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
