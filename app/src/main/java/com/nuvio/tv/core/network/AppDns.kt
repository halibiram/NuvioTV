package com.nuvio.tv.core.network

import android.util.Log
import com.nuvio.tv.BuildConfig
import java.io.IOException
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
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Lookup
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.SOARecord
import org.xbill.DNS.Section
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
    val staleExpiresAtMs: Long,
    val ttlMs: Long,
    val ttlSource: String,
    val failureMessage: String? = null
)

private data class CachedLookupResult(
    val entry: CachedDnsEntry,
    val stale: Boolean
)

private data class PlainDnsProviderConfig(
    val serverName: String,
    val resolvers: List<SimpleResolver>
)

private data class DohProviderConfig(
    val serverName: String,
    val endpointUrl: HttpUrl,
    val client: OkHttpClient
)

private data class DnsRecordResult(
    val addresses: List<InetAddress>,
    val ttlMs: Long? = null,
    val negativeTtlMs: Long? = null
)

private data class ResolvedDnsResult(
    val result: DnsLookupResult,
    val fallbackUsed: Boolean
)

private class NegativeDnsException(
    message: String,
    val ttlMs: Long? = null
) : UnknownHostException(message)

object AppDnsManager : Dns {
    private const val TAG = "AppDnsManager"
    private const val DEFAULT_DNS_CACHE_TTL_MS = 5 * 60 * 1000L
    private const val DEFAULT_NEGATIVE_DNS_CACHE_TTL_MS = 30 * 1000L
    private const val MIN_STALE_WHILE_REVALIDATE_MS = 15 * 1000L
    private const val MAX_STALE_WHILE_REVALIDATE_MS = 5 * 60 * 1000L

    private val dnsMessageMediaType = "application/dns-message".toMediaType()

    private val systemDns: Dns = Dns.SYSTEM
    private val config = AtomicReference(AppDnsConfig())
    private val trackedClients = CopyOnWriteArrayList<WeakReference<OkHttpClient>>()
    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val refreshInFlight = ConcurrentHashMap.newKeySet<String>()
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
        buildDohProvider(
            serverName = "cloudflare_doh",
            endpoint = "https://cloudflare-dns.com/dns-query",
            bootstrapHosts = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
        )
    }

    private val googleDns by lazy {
        buildDohProvider(
            serverName = "google_doh",
            endpoint = "https://dns.google/dns-query",
            bootstrapHosts = listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
        )
    }

    private val quad9DohDns by lazy {
        buildDohProvider(
            serverName = "quad9_doh",
            endpoint = "https://dns.quad9.net/dns-query",
            bootstrapHosts = listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
        )
    }

    private val adGuardDohDns by lazy {
        buildDohProvider(
            serverName = "adguard_doh",
            endpoint = "https://dns.adguard-dns.com/dns-query",
            bootstrapHosts = listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff")
        )
    }

    private val mullvadDohDns by lazy {
        buildDohProvider(
            serverName = "mullvad_doh",
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
        refreshInFlight.clear()
        if (BuildConfig.IS_DEBUG_BUILD) {
            Log.d(TAG, "DNS cache cleared entries=$clearedEntries")
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val activeConfig = config.get()
        val startedAtNanos = System.nanoTime()
        val now = System.currentTimeMillis()

        getCachedLookupResult(hostname = hostname, activeConfig = activeConfig, nowMs = now)?.let { cached ->
            val ttlRemainingMs = (cached.entry.expiresAtMs - now).coerceAtLeast(0L)
            val staleRemainingMs = if (cached.stale) {
                (cached.entry.staleExpiresAtMs - now).coerceAtLeast(0L)
            } else {
                0L
            }

            cached.entry.failureMessage?.let { failureMessage ->
                logLookupFailure(
                    hostname = hostname,
                    activeConfig = activeConfig,
                    elapsedMs = elapsedMs(startedAtNanos),
                    fallbackUsed = false,
                    cacheHit = true,
                    ttlMs = cached.entry.ttlMs,
                    ttlSource = cached.entry.ttlSource,
                    ttlRemainingMs = ttlRemainingMs,
                    reason = failureMessage
                )
                throw NegativeDnsException(failureMessage, cached.entry.ttlMs)
            }

            if (cached.stale) {
                scheduleStaleRefresh(hostname = hostname, activeConfig = activeConfig)
            }

            logLookupSuccess(
                hostname = hostname,
                activeConfig = activeConfig,
                addresses = cached.entry.addresses,
                elapsedMs = elapsedMs(startedAtNanos),
                fallbackUsed = false,
                cacheHit = true,
                stale = cached.stale,
                ttlMs = cached.entry.ttlMs,
                ttlSource = cached.entry.ttlSource,
                ttlRemainingMs = ttlRemainingMs,
                staleRemainingMs = staleRemainingMs
            )
            return cached.entry.addresses
        }

        return try {
            val resolved = resolveWithFallback(hostname = hostname, activeConfig = activeConfig)
            val orderedAddresses = reorderAddresses(resolved.result.addresses, activeConfig.ipv4FirstEnabled)
            val cachedTtlMs = cacheAddresses(
                hostname = hostname,
                activeConfig = activeConfig,
                addresses = orderedAddresses,
                resolvedTtlMs = resolved.result.ttlMs,
                ttlSource = resolved.result.ttlSource
            )
            logLookupSuccess(
                hostname = hostname,
                activeConfig = activeConfig,
                addresses = orderedAddresses,
                elapsedMs = elapsedMs(startedAtNanos),
                fallbackUsed = resolved.fallbackUsed,
                cacheHit = false,
                stale = false,
                ttlMs = cachedTtlMs,
                ttlSource = resolved.result.ttlSource,
                ttlRemainingMs = cachedTtlMs,
                staleRemainingMs = computeStaleWhileRevalidateMs(cachedTtlMs)
            )
            orderedAddresses
        } catch (error: Exception) {
            val negativeTtlMs = cacheFailure(
                hostname = hostname,
                activeConfig = activeConfig,
                failureMessage = error.message ?: "DNS lookup failed for $hostname",
                resolvedTtlMs = (error as? NegativeDnsException)?.ttlMs,
                ttlSource = if ((error as? NegativeDnsException)?.ttlMs != null) "dns_negative" else "default_negative"
            )
            logLookupFailure(
                hostname = hostname,
                activeConfig = activeConfig,
                elapsedMs = elapsedMs(startedAtNanos),
                fallbackUsed = activeConfig.provider != AppDnsProvider.SYSTEM,
                cacheHit = false,
                ttlMs = negativeTtlMs,
                ttlSource = if ((error as? NegativeDnsException)?.ttlMs != null) "dns_negative" else "default_negative",
                ttlRemainingMs = negativeTtlMs,
                reason = error.message ?: "DNS lookup failed for $hostname"
            )
            throw error
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

    private fun resolveWithFallback(hostname: String, activeConfig: AppDnsConfig): ResolvedDnsResult {
        return try {
            ResolvedDnsResult(
                result = resolveWithProvider(activeConfig.provider, hostname),
                fallbackUsed = false
            )
        } catch (primaryError: Exception) {
            if (activeConfig.provider == AppDnsProvider.SYSTEM) {
                throw primaryError
            }

            Log.w(
                TAG,
                "DNS lookup failed via ${activeConfig.provider.storageValue} for $hostname; falling back to system DNS (${primaryError.javaClass.simpleName}: ${primaryError.message})"
            )

            try {
                ResolvedDnsResult(
                    result = DnsLookupResult(addresses = systemDns.lookup(hostname)),
                    fallbackUsed = true
                )
            } catch (fallbackError: Exception) {
                throw buildLookupFailureException(
                    hostname = hostname,
                    provider = activeConfig.provider,
                    primaryError = primaryError,
                    fallbackError = fallbackError
                )
            }
        }
    }

    private fun buildLookupFailureException(
        hostname: String,
        provider: AppDnsProvider,
        primaryError: Exception,
        fallbackError: Exception
    ): Exception {
        val message = buildString {
            append("DNS lookup failed for ")
            append(hostname)
            append(" via ")
            append(provider.storageValue)
            append(" and system fallback failed")
            append(" (primary=")
            append(primaryError.message ?: primaryError.javaClass.simpleName)
            append(", fallback=")
            append(fallbackError.message ?: fallbackError.javaClass.simpleName)
            append(")")
        }
        val ttlMs = listOfNotNull(
            (primaryError as? NegativeDnsException)?.ttlMs,
            (fallbackError as? NegativeDnsException)?.ttlMs
        ).minOrNull()
        val exception = if (ttlMs != null) {
            NegativeDnsException(message, ttlMs)
        } else {
            UnknownHostException(message)
        }
        exception.initCause(fallbackError)
        return exception
    }

    private fun resolveWithProvider(provider: AppDnsProvider, hostname: String): DnsLookupResult {
        return when (provider) {
            AppDnsProvider.SYSTEM -> DnsLookupResult(addresses = systemDns.lookup(hostname))
            AppDnsProvider.CLOUDFLARE -> resolveViaPlainDns(hostname = hostname, providerConfig = cloudflarePlainDns)
            AppDnsProvider.GOOGLE -> resolveViaPlainDns(hostname = hostname, providerConfig = googlePlainDns)
            AppDnsProvider.QUAD9 -> resolveViaPlainDns(hostname = hostname, providerConfig = quad9PlainDns)
            AppDnsProvider.ADGUARD -> resolveViaPlainDns(hostname = hostname, providerConfig = adGuardPlainDns)
            AppDnsProvider.CLOUDFLARE_DOH -> resolveViaDoh(hostname = hostname, providerConfig = cloudflareDns)
            AppDnsProvider.GOOGLE_DOH -> resolveViaDoh(hostname = hostname, providerConfig = googleDns)
            AppDnsProvider.QUAD9_DOH -> resolveViaDoh(hostname = hostname, providerConfig = quad9DohDns)
            AppDnsProvider.ADGUARD_DOH -> resolveViaDoh(hostname = hostname, providerConfig = adGuardDohDns)
            AppDnsProvider.MULLVAD_DOH -> resolveViaDoh(hostname = hostname, providerConfig = mullvadDohDns)
        }
    }

    private fun reorderAddresses(
        addresses: List<InetAddress>,
        ipv4FirstEnabled: Boolean
    ): List<InetAddress> {
        if (!ipv4FirstEnabled) return addresses
        return addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    private fun buildDohProvider(
        serverName: String,
        endpoint: String,
        bootstrapHosts: List<String>
    ): DohProviderConfig {
        val endpointUrl = endpoint.toHttpUrl()
        val endpointHost = endpointUrl.host
        val bootstrapAddresses = bootstrapHosts.map(InetAddress::getByName)
        val client = dohBootstrapClient.newBuilder()
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        return if (hostname.equals(endpointHost, ignoreCase = true)) {
                            bootstrapAddresses
                        } else {
                            systemDns.lookup(hostname)
                        }
                    }
                }
            )
            .build()
        return DohProviderConfig(
            serverName = serverName,
            endpointUrl = endpointUrl,
            client = client
        )
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

    private fun resolveViaDoh(
        hostname: String,
        providerConfig: DohProviderConfig
    ): DnsLookupResult {
        val ipv4Future = plainDnsQueryExecutor.submit<DnsRecordResult> {
            runDohLookup(hostname = hostname, providerConfig = providerConfig, type = Type.A)
        }
        val ipv6Future = plainDnsQueryExecutor.submit<DnsRecordResult> {
            runDohLookup(hostname = hostname, providerConfig = providerConfig, type = Type.AAAA)
        }

        val ipv4Result = runCatching { ipv4Future.get() }.getOrElse { throw unwrapDnsQueryError(it) }
        val ipv6Result = runCatching { ipv6Future.get() }.getOrElse { throw unwrapDnsQueryError(it) }

        val addresses = buildList {
            addAll(ipv4Result.addresses)
            addAll(ipv6Result.addresses)
        }
        if (addresses.isEmpty()) {
            val negativeTtlMs = listOfNotNull(ipv4Result.negativeTtlMs, ipv6Result.negativeTtlMs).minOrNull()
            throw NegativeDnsException(
                message = "No DNS records for $hostname via ${providerConfig.serverName}",
                ttlMs = negativeTtlMs
            )
        }

        return DnsLookupResult(
            addresses = addresses,
            ttlMs = listOfNotNull(ipv4Result.ttlMs, ipv6Result.ttlMs).minOrNull(),
            ttlSource = "doh"
        )
    }

    private fun runDohLookup(
        hostname: String,
        providerConfig: DohProviderConfig,
        type: Int
    ): DnsRecordResult {
        val query = Message.newQuery(
            Record.newRecord(
                absoluteDnsName(hostname),
                type,
                DClass.IN
            )
        )
        val request = Request.Builder()
            .url(providerConfig.endpointUrl)
            .header("Accept", dnsMessageMediaType.toString())
            .post(query.toWire().toRequestBody(dnsMessageMediaType))
            .build()

        providerConfig.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("DoH query failed ${response.code} for $hostname via ${providerConfig.serverName}")
            }

            val responseBytes = response.body?.bytes()
                ?: throw IOException("Empty DoH response for $hostname via ${providerConfig.serverName}")
            val message = Message(responseBytes)
            return when (message.header.rcode) {
                Rcode.NOERROR -> parseDnsMessageRecords(message)
                Rcode.NXDOMAIN -> DnsRecordResult(
                    addresses = emptyList(),
                    negativeTtlMs = extractNegativeTtlMs(message)
                )
                else -> throw IOException(
                    "DoH query returned ${Rcode.string(message.header.rcode)} for $hostname via ${providerConfig.serverName}"
                )
            }
        }
    }

    private fun parseDnsMessageRecords(message: Message): DnsRecordResult {
        val answerRecords = message.getSectionArray(Section.ANSWER)
        val addresses = buildList {
            answerRecords.forEach { record ->
                when (record) {
                    is ARecord -> add(record.address)
                    is AAAARecord -> add(record.address)
                }
            }
        }
        if (addresses.isEmpty()) {
            return DnsRecordResult(
                addresses = emptyList(),
                negativeTtlMs = extractNegativeTtlMs(message)
            )
        }

        val ttlMs = answerRecords
            .mapNotNull { record ->
                when (record) {
                    is ARecord, is AAAARecord -> TimeUnit.SECONDS.toMillis(record.ttl.coerceAtLeast(0L))
                    else -> null
                }
            }
            .minOrNull()

        return DnsRecordResult(
            addresses = addresses,
            ttlMs = ttlMs
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
        throw NegativeDnsException(
            message = buildString {
                append("No DNS answer for ")
                append(hostname)
                append(" via ")
                append(providerConfig.serverName)
                lastError?.message?.let {
                    append(" (")
                    append(it)
                    append(")")
                }
            },
            ttlMs = (lastError as? NegativeDnsException)?.ttlMs
        )
    }

    private fun queryResolver(hostname: String, resolver: SimpleResolver): DnsRecordResult {
        val ipv4Future = plainDnsQueryExecutor.submit<DnsRecordResult> {
            runLookup(hostname = hostname, resolver = resolver, type = Type.A)
        }
        val ipv6Future = plainDnsQueryExecutor.submit<DnsRecordResult> {
            runLookup(hostname = hostname, resolver = resolver, type = Type.AAAA)
        }

        val ipv4Result = runCatching { ipv4Future.get() }.getOrElse { throw unwrapDnsQueryError(it) }
        val ipv6Result = runCatching { ipv6Future.get() }.getOrElse { throw unwrapDnsQueryError(it) }

        if (ipv4Result.addresses.isEmpty() && ipv6Result.addresses.isEmpty()) {
            throw NegativeDnsException(
                message = "No DNS records for $hostname via ${resolver.address}",
                ttlMs = listOfNotNull(ipv4Result.negativeTtlMs, ipv6Result.negativeTtlMs).minOrNull()
            )
        }

        return DnsRecordResult(
            addresses = buildList {
                addAll(ipv4Result.addresses)
                addAll(ipv6Result.addresses)
            },
            ttlMs = listOfNotNull(ipv4Result.ttlMs, ipv6Result.ttlMs).minOrNull()
        )
    }

    private fun unwrapDnsQueryError(error: Throwable): Exception {
        val cause = error.cause
        return when (cause) {
            is Exception -> cause
            else -> IOException(cause?.message ?: error.message ?: "DNS query failed")
        }
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
            .map { TimeUnit.SECONDS.toMillis(it.ttl.coerceAtLeast(0L)) }
            .minOrNull()

        return DnsRecordResult(
            addresses = addresses,
            ttlMs = ttlMs
        )
    }

    private fun extractNegativeTtlMs(message: Message): Long? {
        val authorityRecords = message.getSectionArray(Section.AUTHORITY)
        val soaRecord = authorityRecords.filterIsInstance<SOARecord>().firstOrNull() ?: return null
        return TimeUnit.SECONDS.toMillis(soaRecord.minimum.coerceAtLeast(0L))
    }

    private fun absoluteDnsName(hostname: String): Name {
        return Name.fromString(if (hostname.endsWith('.')) hostname else "$hostname.")
    }

    private fun getCachedLookupResult(
        hostname: String,
        activeConfig: AppDnsConfig,
        nowMs: Long
    ): CachedLookupResult? {
        if (!activeConfig.dnsCacheEnabled) return null
        val cachedEntry = dnsCache[hostname] ?: return null
        if (nowMs >= cachedEntry.staleExpiresAtMs) {
            dnsCache.remove(hostname)
            return null
        }
        if (cachedEntry.failureMessage != null && nowMs >= cachedEntry.expiresAtMs) {
            dnsCache.remove(hostname)
            return null
        }
        return CachedLookupResult(
            entry = cachedEntry,
            stale = cachedEntry.failureMessage == null && nowMs >= cachedEntry.expiresAtMs
        )
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
        val staleWindowMs = computeStaleWhileRevalidateMs(effectiveTtlMs)
        dnsCache[hostname] = CachedDnsEntry(
            addresses = addresses,
            cachedAtMs = now,
            expiresAtMs = now + effectiveTtlMs,
            staleExpiresAtMs = now + effectiveTtlMs + staleWindowMs,
            ttlMs = effectiveTtlMs,
            ttlSource = ttlSource
        )
        return effectiveTtlMs
    }

    private fun cacheFailure(
        hostname: String,
        activeConfig: AppDnsConfig,
        failureMessage: String,
        resolvedTtlMs: Long?,
        ttlSource: String
    ): Long {
        val effectiveTtlMs = resolvedTtlMs ?: DEFAULT_NEGATIVE_DNS_CACHE_TTL_MS
        if (!activeConfig.dnsCacheEnabled) return effectiveTtlMs

        val now = System.currentTimeMillis()
        dnsCache[hostname] = CachedDnsEntry(
            addresses = emptyList(),
            cachedAtMs = now,
            expiresAtMs = now + effectiveTtlMs,
            staleExpiresAtMs = now + effectiveTtlMs,
            ttlMs = effectiveTtlMs,
            ttlSource = ttlSource,
            failureMessage = failureMessage
        )
        return effectiveTtlMs
    }

    private fun computeStaleWhileRevalidateMs(ttlMs: Long): Long {
        if (ttlMs <= 0L) return 0L
        val candidate = ttlMs / 4
        return min(MAX_STALE_WHILE_REVALIDATE_MS, max(MIN_STALE_WHILE_REVALIDATE_MS, candidate))
    }

    private fun scheduleStaleRefresh(hostname: String, activeConfig: AppDnsConfig) {
        if (!activeConfig.dnsCacheEnabled) return
        val refreshKey = "${activeConfig.provider.storageValue}:$hostname"
        if (!refreshInFlight.add(refreshKey)) return

        if (BuildConfig.IS_DEBUG_BUILD) {
            Log.d(TAG, "DNS stale cache refresh scheduled host=$hostname provider=${activeConfig.provider.storageValue}")
        }

        evictionScope.launch {
            try {
                val resolved = resolveWithFallback(hostname = hostname, activeConfig = activeConfig)
                val orderedAddresses = reorderAddresses(resolved.result.addresses, activeConfig.ipv4FirstEnabled)
                val cachedTtlMs = cacheAddresses(
                    hostname = hostname,
                    activeConfig = activeConfig,
                    addresses = orderedAddresses,
                    resolvedTtlMs = resolved.result.ttlMs,
                    ttlSource = resolved.result.ttlSource
                )
                if (BuildConfig.IS_DEBUG_BUILD) {
                    val addressSummary = orderedAddresses.joinToString { it.hostAddress ?: it.toString() }
                    Log.d(
                        TAG,
                        "DNS stale cache refresh completed host=$hostname provider=${activeConfig.provider.storageValue} fallback=${resolved.fallbackUsed} ttlMs=$cachedTtlMs ttlSource=${resolved.result.ttlSource} addresses=[$addressSummary]"
                    )
                }
            } catch (error: Exception) {
                if (BuildConfig.IS_DEBUG_BUILD) {
                    Log.d(
                        TAG,
                        "DNS stale cache refresh failed host=$hostname provider=${activeConfig.provider.storageValue}: ${error.message}"
                    )
                }
            } finally {
                refreshInFlight.remove(refreshKey)
            }
        }
    }

    private fun logLookupSuccess(
        hostname: String,
        activeConfig: AppDnsConfig,
        addresses: List<InetAddress>,
        elapsedMs: Long,
        fallbackUsed: Boolean,
        cacheHit: Boolean,
        stale: Boolean,
        ttlMs: Long,
        ttlSource: String,
        ttlRemainingMs: Long,
        staleRemainingMs: Long
    ) {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        val addressSummary = addresses.joinToString { it.hostAddress ?: it.toString() }
        Log.d(
            TAG,
            "DNS lookup host=$hostname provider=${activeConfig.provider.storageValue} ipv4First=${activeConfig.ipv4FirstEnabled} cache=${activeConfig.dnsCacheEnabled} cacheHit=$cacheHit stale=$stale fallback=$fallbackUsed elapsedMs=$elapsedMs ttlMs=$ttlMs ttlRemainingMs=$ttlRemainingMs staleRemainingMs=$staleRemainingMs ttlSource=$ttlSource addresses=[$addressSummary]"
        )
    }

    private fun logLookupFailure(
        hostname: String,
        activeConfig: AppDnsConfig,
        elapsedMs: Long,
        fallbackUsed: Boolean,
        cacheHit: Boolean,
        ttlMs: Long,
        ttlSource: String,
        ttlRemainingMs: Long,
        reason: String
    ) {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        Log.d(
            TAG,
            "DNS lookup failed host=$hostname provider=${activeConfig.provider.storageValue} ipv4First=${activeConfig.ipv4FirstEnabled} cache=${activeConfig.dnsCacheEnabled} cacheHit=$cacheHit fallback=$fallbackUsed elapsedMs=$elapsedMs ttlMs=$ttlMs ttlRemainingMs=$ttlRemainingMs ttlSource=$ttlSource reason=$reason"
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
