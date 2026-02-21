package com.nuvio.tv.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.SearchHistoryItem
import com.nuvio.tv.domain.repository.SearchHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchHistoryRepositoryImpl @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) : SearchHistoryRepository {

    companion object {
        private const val FEATURE = "search_history"
        private const val MAX_RECENT_SEARCHES = 15
        private const val MAX_RECENTLY_VIEWED = 20
    }

    private val gson = Gson()
    private val recentSearchesKey = stringPreferencesKey("recent_searches_key")
    private val recentlyViewedKey = stringPreferencesKey("recently_viewed_key")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private fun <T> profileFlow(extract: (prefs: androidx.datastore.preferences.core.Preferences) -> T): Flow<T> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs -> extract(prefs) }
        }

    override fun getRecentSearches(): Flow<List<String>> = profileFlow { prefs ->
        parseRecentSearches(prefs[recentSearchesKey])
    }

    override fun getRecentlyViewed(): Flow<List<SearchHistoryItem>> = profileFlow { prefs ->
        parseRecentlyViewed(prefs[recentlyViewedKey])
    }

    override suspend fun addSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return

        store().edit { prefs ->
            val currentList = parseRecentSearches(prefs[recentSearchesKey]).toMutableList()
            // Remove if exists to move it to the top
            currentList.remove(trimmed)
            currentList.add(0, trimmed)
            // Trim to max
            val trimmedList = currentList.take(MAX_RECENT_SEARCHES)
            prefs[recentSearchesKey] = gson.toJson(trimmedList)
        }
    }

    override suspend fun addRecentlyViewed(item: SearchHistoryItem) {
        store().edit { prefs ->
            val currentList = parseRecentlyViewed(prefs[recentlyViewedKey]).toMutableList()
            // Remove existing match based on id AND type
            currentList.removeAll { it.id == item.id && it.type == item.type }
            currentList.add(0, item)
            // Trim to max
            val trimmedList = currentList.take(MAX_RECENTLY_VIEWED)
            prefs[recentlyViewedKey] = gson.toJson(trimmedList)
        }
    }

    override suspend fun clearRecentSearches() {
        store().edit { prefs ->
            prefs.remove(recentSearchesKey)
        }
    }

    override suspend fun clearRecentlyViewed() {
        store().edit { prefs ->
            prefs.remove(recentlyViewedKey)
        }
    }

    private fun parseRecentSearches(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseRecentlyViewed(json: String?): List<SearchHistoryItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<SearchHistoryItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
