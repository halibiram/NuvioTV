package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

@Singleton
class ScraperLatencyDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "scraper_latency"
        const val PLUGIN_LATENCY_TTL_MS = 24L * 60L * 60L * 1000L
        const val EMA_ALPHA = 0.3
        const val FAILURE_PENALTY_MS = 60_000L
        private val LATENCY_KEY = stringPreferencesKey("scraper_latency")
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    suspend fun record(scraperId: String, durationMs: Long, success: Boolean = true) {
        val now = System.currentTimeMillis()
        val recordedMs = if (success) {
            durationMs
        } else {
            maxOf(durationMs, FAILURE_PENALTY_MS)
        }
        store().edit { prefs ->
            val root = prefs[LATENCY_KEY]?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            } ?: JSONObject()

            val existing = root.optJSONObject(scraperId)
            val previousSamples = existing?.optInt("samples", 0) ?: 0
            val emaMs = if (previousSamples <= 0) {
                recordedMs
            } else {
                val prev = existing?.optLong("emaMs", recordedMs) ?: recordedMs
                (EMA_ALPHA * recordedMs + (1.0 - EMA_ALPHA) * prev).roundToLong()
            }

            val expired = mutableListOf<String>()
            root.keys().forEach { key ->
                if (key == scraperId) return@forEach
                val entry = root.optJSONObject(key) ?: return@forEach
                val updatedAt = entry.optLong("updatedAtMs", 0L)
                if (updatedAt <= 0L || now - updatedAt > PLUGIN_LATENCY_TTL_MS) {
                    expired += key
                }
            }
            expired.forEach { root.remove(it) }

            root.put(scraperId, JSONObject().apply {
                put("emaMs", emaMs)
                put("lastMs", recordedMs)
                put("updatedAtMs", now)
                put("samples", previousSamples + 1)
            })
            prefs[LATENCY_KEY] = root.toString()
        }
    }

    suspend fun snapshot(): Map<String, Long> {
        val now = System.currentTimeMillis()
        val raw = store().data.first()[LATENCY_KEY] ?: return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = mutableMapOf<String, Long>()
        root.keys().forEach { key ->
            val entry = root.optJSONObject(key) ?: return@forEach
            val updatedAt = entry.optLong("updatedAtMs", 0L)
            if (updatedAt > 0L && now - updatedAt <= PLUGIN_LATENCY_TTL_MS) {
                result[key] = entry.optLong("emaMs", 0L)
            }
        }
        return result
    }
}
