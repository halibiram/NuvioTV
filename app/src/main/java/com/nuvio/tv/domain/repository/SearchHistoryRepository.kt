package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.SearchHistoryItem
import kotlinx.coroutines.flow.Flow

interface SearchHistoryRepository {
    fun getRecentSearches(): Flow<List<String>>
    fun getRecentlyViewed(): Flow<List<SearchHistoryItem>>
    suspend fun addSearchQuery(query: String)
    suspend fun addRecentlyViewed(item: SearchHistoryItem)
    suspend fun clearRecentSearches()
    suspend fun clearRecentlyViewed()
}
