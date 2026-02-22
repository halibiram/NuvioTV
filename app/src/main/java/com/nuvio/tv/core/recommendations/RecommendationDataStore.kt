package com.nuvio.tv.core.recommendations

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recommendationDataStore by preferencesDataStore(
    name = "tv_recommendation_prefs"
)

/**
 * Persists channel IDs created via [TvContractCompat] so they survive app restarts,
 * and stores the global "recommendations enabled" toggle.
 */
@Singleton
class RecommendationDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_NEW_RELEASES_CHANNEL_ID =
            longPreferencesKey("new_releases_channel_id")
        private val KEY_TRENDING_CHANNEL_ID =
            longPreferencesKey("trending_channel_id")
        private val KEY_RECOMMENDATIONS_ENABLED =
            booleanPreferencesKey("recommendations_enabled")
    }

    // ── Channel ID CRUD ──

    suspend fun getChannelId(channelType: String): Long? {
        val key = keyForType(channelType)
        return context.recommendationDataStore.data.map { it[key] }.first()
    }

    suspend fun setChannelId(channelType: String, channelId: Long) {
        val key = keyForType(channelType)
        context.recommendationDataStore.edit { it[key] = channelId }
    }

    suspend fun clearChannelId(channelType: String) {
        val key = keyForType(channelType)
        context.recommendationDataStore.edit { it.remove(key) }
    }

    // ── Global toggle ──

    suspend fun isEnabled(): Boolean =
        context.recommendationDataStore.data.map {
            it[KEY_RECOMMENDATIONS_ENABLED] ?: true
        }.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.recommendationDataStore.edit {
            it[KEY_RECOMMENDATIONS_ENABLED] = enabled
        }
    }

    // ── Helpers ──

    private fun keyForType(channelType: String) = when (channelType) {
        RecommendationConstants.CHANNEL_NEW_RELEASES -> KEY_NEW_RELEASES_CHANNEL_ID
        RecommendationConstants.CHANNEL_TRENDING -> KEY_TRENDING_CHANNEL_ID
        else -> throw IllegalArgumentException("Unknown channel type: $channelType")
    }
}
