package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.proto.WatchProgressRecord
import com.nuvio.tv.data.local.proto.WatchProgressShard
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchProgressPreferences @Inject constructor(
    private val legacyFactory: ProfileDataStoreFactory,
    private val shardFactory: WatchProgressShardStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "WatchProgressPrefs"
        private const val LEGACY_FEATURE = "watch_progress_preferences"
    }

    private val gson = Gson()
    private val legacyWatchProgressKey = stringPreferencesKey("watch_progress_map")
    private val initializedProfiles = ConcurrentHashMap.newKeySet<Int>()
    private val initializationMutex = Mutex()

    private val allRawEntries: Flow<Map<String, WatchProgress>> = profileManager.activeProfileId.flatMapLatest { profileId ->
        flow {
            ensureProfileInitialized(profileId)
            emitAll(observeRawEntries(profileId))
        }
    }

    /**
     * Get all watch progress items, sorted by last watched (most recent first)
     * For series, only returns the series-level entry (not individual episode entries)
     * to avoid duplicates in continue watching.
     */
    val allProgress: Flow<List<WatchProgress>> = allRawEntries.map { allItems ->
        // Group all entries by contentId and pick the most recently watched.
        // When lastWatched is equal (e.g. batch mark-as-watched), prefer the highest season/episode.
        allItems.values
            .groupBy { it.contentId }
            .mapValues { (_, items) ->
                items.maxWithOrNull(
                    compareBy<WatchProgress> { it.lastWatched }
                        .thenBy { it.season ?: 0 }
                        .thenBy { it.episode ?: 0 }
                )
            }
            .values
            .filterNotNull()
            .sortedByDescending { it.lastWatched }
    }.distinctUntilChanged()

    val allRawProgress: Flow<List<WatchProgress>> = allRawEntries.map { entries ->
        entries.values.sortedByDescending { it.lastWatched }
    }.distinctUntilChanged()

    /**
     * Get items that are in progress (not completed)
     */
    val continueWatching: Flow<List<WatchProgress>> = allProgress.map { list ->
        list.filter { it.isInProgress() }
    }.distinctUntilChanged()

    /**
     * Get watch progress for a specific content item
     */
    fun getProgress(contentId: String): Flow<WatchProgress?> {
        return allRawEntries.map { entries ->
            // Try direct key first (movies), then find latest episode entry (series).
            entries[contentId] ?: entries.values
                .filter { it.contentId == contentId }
                .maxByOrNull { it.lastWatched }
        }.distinctUntilChanged()
    }

    /**
     * Get watch progress for a specific episode
     */
    fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> {
        val key = episodeKey(contentId, season, episode)
        return allRawEntries.map { entries ->
            entries[key] ?: entries.values.firstOrNull {
                it.contentId == contentId && it.season == season && it.episode == episode
            }
        }.distinctUntilChanged()
    }

    /**
     * Get all episode progress for a series
     */
    fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> {
        return allRawEntries.map { entries ->
            entries.values
                .filter { it.contentId == contentId && it.season != null && it.episode != null }
                .associateBy { (it.season!! to it.episode!!) }
        }.distinctUntilChanged()
    }

    /**
     * Save or update watch progress
     */
    suspend fun saveProgress(progress: WatchProgress) {
        val profileId = profileManager.activeProfileId.value
        ensureProfileInitialized(profileId)
        upsertEntries(profileId, listOf(progress))
    }

    suspend fun saveProgressBatch(progressList: List<WatchProgress>) {
        if (progressList.isEmpty()) return
        val profileId = profileManager.activeProfileId.value
        ensureProfileInitialized(profileId)
        upsertEntries(profileId, progressList)
    }

    /**
     * Remove watch progress for a specific item
     */
    suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        val profileId = profileManager.activeProfileId.value
        ensureProfileInitialized(profileId)

        val currentEntries = getAllRawEntries(profileId)
        val beforeSize = currentEntries.size
        Log.d(
            TAG,
            "removeProgress start contentId=$contentId season=$season episode=$episode entriesBefore=$beforeSize"
        )

        val keysToRemove = if (season != null && episode != null) {
            // Remove specific episode progress + the series-level entry
            // so the item disappears from continue watching
            setOf(episodeKey(contentId, season, episode), contentId)
        } else {
            // Remove all progress for this content
            getAllRawEntries(profileId).keys.filterTo(linkedSetOf()) { key ->
                key == contentId || key.startsWith("${contentId}_s")
            }
        }

        if (keysToRemove.isEmpty()) return
        if (season != null && episode != null) {
            val key = episodeKey(contentId, season, episode)
            removeKeys(profileId, keysToRemove)
            val existsAfter = getAllRawEntries(profileId).containsKey(key)
            Log.d(TAG, "removeProgress episodeKey=$key existsAfter=$existsAfter")
        } else {
            Log.d(TAG, "removeProgress removingKeys=${keysToRemove.joinToString()}")
            removeKeys(profileId, keysToRemove)
        }
        Log.d(TAG, "removeProgress complete contentId=$contentId entriesAfter=${getAllRawEntries(profileId).size}")
    }

    /**
     * Remove watch progress for multiple episodes in a single DataStore transaction.
     */
    suspend fun removeProgressBatch(contentId: String, episodes: List<Pair<Int, Int>>) {
        if (episodes.isEmpty()) return
        val profileId = profileManager.activeProfileId.value
        ensureProfileInitialized(profileId)

        val keysToRemove = linkedSetOf<String>()
        episodes.forEach { (season, episode) ->
            keysToRemove += episodeKey(contentId, season, episode)
        }
        keysToRemove += contentId
        removeKeys(profileId, keysToRemove)
        Log.d(TAG, "removeProgressBatch contentId=$contentId removed=${episodes.size} episodes entriesAfter=${getAllRawEntries(profileId).size}")
    }

    /**
     * Mark content as completed
     */
    suspend fun markAsCompleted(progress: WatchProgress) {
        val profileId = profileManager.activeProfileId.value
        ensureProfileInitialized(profileId)
        val rawEntries = getAllRawEntries(profileId)

        // If the incoming duration is a dummy sentinel (<= 1ms), check for an
        // existing local entry with a real duration from prior playback.
        // This creates a proper completed entry that syncs correctly cross-device.
        val effectiveDuration = if (progress.duration <= 1L) {
            rawEntries[createKey(progress)]?.duration?.takeIf { it > 1L } ?: progress.duration
        } else {
            progress.duration
        }

        saveProgress(
            progress.copy(
                position = effectiveDuration,
                duration = effectiveDuration,
                lastWatched = System.currentTimeMillis()
            )
        )
    }

    /**
     * Mark multiple items as completed in a single DataStore transaction.
     */
    suspend fun markAsCompletedBatch(progressList: List<WatchProgress>) {
        if (progressList.isEmpty()) return
        val profileId = profileManager.activeProfileId.value
        ensureProfileInitialized(profileId)
        val rawEntries = getAllRawEntries(profileId)
        val now = System.currentTimeMillis()
        val completed = progressList.map { progress ->
            val effectiveDuration = if (progress.duration <= 1L) {
                rawEntries[createKey(progress)]?.duration?.takeIf { it > 1L } ?: progress.duration
            } else {
                progress.duration
            }
            progress.copy(
                position = effectiveDuration,
                duration = effectiveDuration,
                lastWatched = now
            )
        }
        saveProgressBatch(completed)
    }

    /**
     * Returns the raw key->WatchProgress map from DataStore (for sync push).
     */
    suspend fun getAllRawEntries(): Map<String, WatchProgress> {
        val profileId = profileManager.activeProfileId.value
        ensureProfileInitialized(profileId)
        return getAllRawEntries(profileId)
    }

    /**
     * Merges remote entries into local storage. Newer lastWatched wins per key.
     */
    suspend fun mergeRemoteEntries(remoteEntries: Map<String, WatchProgress>) {
        val profileId = profileManager.activeProfileId.value
        ensureProfileInitialized(profileId)

        Log.d(TAG, "mergeRemoteEntries: ${remoteEntries.size} remote entries")

        val local = getAllRawEntries(profileId).toMutableMap()
        Log.d(TAG, "mergeRemoteEntries: ${local.size} existing local entries")
        if (remoteEntries.isNotEmpty()) {
            // Remove local entries that no longer exist on remote
            val removedKeys = local.keys - remoteEntries.keys
            removedKeys.forEach { key ->
                local.remove(key)
                Log.d(TAG, "  removed key=$key (not in remote)")
            }
        }

        remoteEntries.forEach { (key, remote) ->
            val existing = local[key]
            if (existing == null || remote.lastWatched > existing.lastWatched) {
                local[key] = mergeDisplayMetadata(remote, existing)
                Log.d(TAG, "  merged key=$key (existing=${existing != null})")
            } else {
                Log.d(TAG, "  skipped key=$key (local is newer)")
            }
        }

        val pruned = pruneOldItems(local)
        Log.d(TAG, "mergeRemoteEntries: ${pruned.size} entries after prune, writing to DataStore")
        writeExactEntries(profileId, pruned)
    }

    suspend fun replaceWithRemoteEntries(remoteEntries: Map<String, WatchProgress>) {
        val profileId = profileManager.activeProfileId.value
        ensureProfileInitialized(profileId)

        Log.d(TAG, "replaceWithRemoteEntries: ${remoteEntries.size} remote entries")

        val current = getAllRawEntries(profileId)
        if (remoteEntries.isEmpty() && current.isNotEmpty()) {
            Log.w(TAG, "replaceWithRemoteEntries: remote empty while local has ${current.size} entries; preserving local watch progress")
            return
        }

        val merged = remoteEntries.mapValues { (key, remote) ->
            mergeDisplayMetadata(remote, current[key])
        }.toMutableMap()
        val pruned = pruneOldItems(merged)
        Log.d(TAG, "replaceWithRemoteEntries: ${pruned.size} entries after prune, writing to DataStore")
        writeExactEntries(profileId, pruned)
    }

    /**
     * Clear all watch progress
     */
    suspend fun clearAll() {
        val profileId = profileManager.activeProfileId.value
        ensureProfileInitialized(profileId)
        repeat(WatchProgressShardStoreFactory.SHARD_COUNT) { shardId ->
            shardStore(profileId, shardId).updateData {
                WatchProgressCorruptionTracker.clear(shardFactory.storeName(profileId, shardId))
                WatchProgressShard.getDefaultInstance()
            }
        }
        clearLegacyStore(profileId)
    }

    private suspend fun ensureProfileInitialized(profileId: Int) {
        if (initializedProfiles.contains(profileId)) return
        initializationMutex.withLock {
            if (initializedProfiles.contains(profileId)) return

            val existingEntries = getAllRawEntries(profileId, ensureInitialized = false)
            if (existingEntries.isEmpty()) {
                migrateLegacyStore(profileId)
            }
            initializedProfiles.add(profileId)
        }
    }

    private fun observeRawEntries(profileId: Int): Flow<Map<String, WatchProgress>> {
        val shardFlows = List(WatchProgressShardStoreFactory.SHARD_COUNT) { shardId ->
            shardStore(profileId, shardId).data
        }
        return combine(shardFlows) { shards ->
            val merged = LinkedHashMap<String, WatchProgress>()
            shards.forEach { shard ->
                shard.entriesList.forEach { record ->
                    merged[record.key] = record.toDomain()
                }
            }
            merged.toMap()
        }.distinctUntilChanged()
    }

    private suspend fun getAllRawEntries(
        profileId: Int,
        ensureInitialized: Boolean = true
    ): Map<String, WatchProgress> {
        if (ensureInitialized) {
            ensureProfileInitialized(profileId)
        }

        val merged = LinkedHashMap<String, WatchProgress>()
        repeat(WatchProgressShardStoreFactory.SHARD_COUNT) { shardId ->
            val shard = shardStore(profileId, shardId).data.first()
            shard.entriesList.forEach { record ->
                merged[record.key] = record.toDomain()
            }
        }
        return merged
    }

    private suspend fun migrateLegacyStore(profileId: Int) {
        val preferences = legacyFactory.get(profileId, LEGACY_FEATURE).data.first()
        val json = preferences[legacyWatchProgressKey]
        if (json.isNullOrBlank()) return

        val legacyEntries = parseProgressMap(json)
        if (legacyEntries.isEmpty()) {
            clearLegacyStore(profileId)
            return
        }

        Log.d(TAG, "Migrating ${legacyEntries.size} legacy watch progress entries for profile $profileId")
        writeExactEntries(profileId, legacyEntries)
        clearLegacyStore(profileId)
    }

    private suspend fun clearLegacyStore(profileId: Int) {
        legacyFactory.get(profileId, LEGACY_FEATURE).edit { preferences ->
            preferences.remove(legacyWatchProgressKey)
        }
    }

    private suspend fun upsertEntries(profileId: Int, progressList: List<WatchProgress>) {
        val grouped = progressList.groupBy { shardIdForKey(createKey(it)) }
        grouped.forEach { (shardId, shardEntries) ->
            shardStore(profileId, shardId).updateData { current ->
                val byKey = current.entriesList.associateByTo(linkedMapOf()) { it.key }
                shardEntries.forEach { progress ->
                    byKey[createKey(progress)] = progress.toProto(createKey(progress))
                }
                WatchProgressCorruptionTracker.clear(shardFactory.storeName(profileId, shardId))
                shardFromEntries(byKey.values)
            }
        }
    }

    private suspend fun removeKeys(profileId: Int, keys: Collection<String>) {
        val groupedKeys = keys.groupBy(::shardIdForKey)
        groupedKeys.forEach { (shardId, shardKeys) ->
            shardStore(profileId, shardId).updateData { current ->
                val remaining = current.entriesList.filterNot { it.key in shardKeys.toSet() }
                WatchProgressCorruptionTracker.clear(shardFactory.storeName(profileId, shardId))
                shardFromEntries(remaining)
            }
        }
    }

    private suspend fun writeExactEntries(profileId: Int, entries: Map<String, WatchProgress>) {
        val grouped = entries.entries.groupBy { shardIdForKey(it.key) }
        repeat(WatchProgressShardStoreFactory.SHARD_COUNT) { shardId ->
            val shardEntries = grouped[shardId].orEmpty().map { (key, progress) ->
                progress.toProto(key)
            }
            shardStore(profileId, shardId).updateData {
                WatchProgressCorruptionTracker.clear(shardFactory.storeName(profileId, shardId))
                shardFromEntries(shardEntries)
            }
        }
    }

    private fun shardFromEntries(entries: Collection<WatchProgressRecord>): WatchProgressShard {
        return WatchProgressShard.newBuilder()
            .addAllEntries(entries.sortedBy { it.key })
            .build()
    }

    private fun shardStore(profileId: Int, shardId: Int): DataStore<WatchProgressShard> {
        return shardFactory.get(profileId, shardId)
    }

    private fun shardIdForKey(key: String): Int {
        return (key.hashCode() and Int.MAX_VALUE) % WatchProgressShardStoreFactory.SHARD_COUNT
    }

    private fun createKey(progress: WatchProgress): String {
        return if (progress.season != null && progress.episode != null) {
            episodeKey(progress.contentId, progress.season, progress.episode)
        } else {
            progress.contentId
        }
    }

    private fun episodeKey(contentId: String, season: Int, episode: Int): String {
        return "${contentId}_s${season}e${episode}"
    }

    private fun mergeDisplayMetadata(remote: WatchProgress, existing: WatchProgress?): WatchProgress {
        if (existing == null) return remote
        return remote.copy(
            name = existing.name.takeIf { it.isNotBlank() } ?: remote.name.takeIf { it.isNotBlank() } ?: existing.name,
            poster = existing.poster ?: remote.poster,
            backdrop = existing.backdrop ?: remote.backdrop,
            logo = existing.logo ?: remote.logo,
            episodeTitle = existing.episodeTitle ?: remote.episodeTitle,
            addonBaseUrl = remote.addonBaseUrl ?: existing.addonBaseUrl
        )
    }

    private fun WatchProgress.toProto(key: String): WatchProgressRecord {
        return WatchProgressRecord.newBuilder()
            .setKey(key)
            .setContentId(contentId)
            .setContentType(contentType)
            .setName(name)
            .setPoster(poster.orEmpty())
            .setBackdrop(backdrop.orEmpty())
            .setLogo(logo.orEmpty())
            .setVideoId(videoId)
            .apply {
                season?.let(::setSeason)
                episode?.let(::setEpisode)
                episodeTitle?.let(::setEpisodeTitle)
                setPosition(position)
                setDuration(duration)
                setLastWatched(lastWatched)
                addonBaseUrl?.let(::setAddonBaseUrl)
                progressPercent?.let(::setProgressPercent)
                setSource(source)
                traktPlaybackId?.let(::setTraktPlaybackId)
                traktMovieId?.let(::setTraktMovieId)
                traktShowId?.let(::setTraktShowId)
                traktEpisodeId?.let(::setTraktEpisodeId)
            }
            .build()
    }

    private fun WatchProgressRecord.toDomain(): WatchProgress {
        return WatchProgress(
            contentId = contentId,
            contentType = contentType,
            name = name,
            poster = poster.takeIf { it.isNotBlank() },
            backdrop = backdrop.takeIf { it.isNotBlank() },
            logo = logo.takeIf { it.isNotBlank() },
            videoId = videoId.ifBlank { contentId },
            season = if (hasSeason()) season else null,
            episode = if (hasEpisode()) episode else null,
            episodeTitle = episodeTitle.takeIf { it.isNotBlank() },
            position = position,
            duration = duration,
            lastWatched = lastWatched,
            addonBaseUrl = addonBaseUrl.takeIf { it.isNotBlank() },
            progressPercent = if (hasProgressPercent()) progressPercent else null,
            source = source.ifBlank { WatchProgress.SOURCE_LOCAL },
            traktPlaybackId = if (hasTraktPlaybackId()) traktPlaybackId else null,
            traktMovieId = if (hasTraktMovieId()) traktMovieId else null,
            traktShowId = if (hasTraktShowId()) traktShowId else null,
            traktEpisodeId = if (hasTraktEpisodeId()) traktEpisodeId else null
        )
    }

    private fun parseProgressMap(json: String): Map<String, WatchProgress> {
        return try {
            // Parse entry-by-entry so one malformed value doesn't wipe the entire map.
            val root = gson.fromJson(json, JsonObject::class.java) ?: return emptyMap()
            val parsed = mutableMapOf<String, WatchProgress>()
            root.entrySet().forEach { (key, value) ->
                runCatching {
                    parseWatchProgressFromJson(value)
                }.onSuccess { watchProgress ->
                    if (watchProgress != null) parsed[key] = watchProgress
                }.onFailure {
                    Log.w(TAG, "Skipping malformed watch progress entry for key=$key")
                }
            }
            parsed
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to parse progress data", exception)
            // Backward compatibility with previously stored direct WatchProgress payloads.
            runCatching {
                val fallbackType = object : TypeToken<Map<String, WatchProgress>>() {}.type
                gson.fromJson<Map<String, WatchProgress>>(json, fallbackType) ?: emptyMap()
            }.getOrElse { emptyMap() }
        }
    }

    private fun parseWatchProgressFromJson(value: JsonElement): WatchProgress? {
        val obj = when {
            value.isJsonObject -> value.asJsonObject
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                runCatching { gson.fromJson(value.asString, JsonObject::class.java) }.getOrNull()
            }
            else -> null
        } ?: return null

        val contentId = obj.getString("contentId", "content_id")?.takeIf { it.isNotBlank() } ?: return null
        val contentType = obj.getString("contentType", "content_type")?.takeIf { it.isNotBlank() } ?: return null
        val videoId = obj.getString("videoId", "video_id")?.takeIf { it.isNotBlank() } ?: contentId
        val lastWatched = obj.getLong("lastWatched", "last_watched") ?: return null

        return WatchProgress(
            contentId = contentId,
            contentType = contentType,
            name = obj.getString("name").orEmpty(),
            poster = obj.getString("poster"),
            backdrop = obj.getString("backdrop"),
            logo = obj.getString("logo"),
            videoId = videoId,
            season = obj.getInt("season"),
            episode = obj.getInt("episode"),
            episodeTitle = obj.getString("episodeTitle", "episode_title"),
            position = obj.getLong("position") ?: 0L,
            duration = obj.getLong("duration") ?: 0L,
            lastWatched = lastWatched,
            addonBaseUrl = obj.getString("addonBaseUrl", "addon_base_url"),
            progressPercent = obj.getFloat("progressPercent", "progress_percent"),
            source = obj.getString("source")?.takeIf { it.isNotBlank() } ?: WatchProgress.SOURCE_LOCAL,
            traktPlaybackId = obj.getLong("traktPlaybackId", "trakt_playback_id"),
            traktMovieId = obj.getInt("traktMovieId", "trakt_movie_id"),
            traktShowId = obj.getInt("traktShowId", "trakt_show_id"),
            traktEpisodeId = obj.getInt("traktEpisodeId", "trakt_episode_id")
        )
    }

    private fun JsonObject.getString(vararg keys: String): String? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            return runCatching { value.asString }.getOrNull()
        }
        return null
    }

    private fun JsonObject.getLong(vararg keys: String): Long? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            runCatching { value.asLong }.getOrNull()?.let { return it }
            runCatching { value.asDouble.toLong() }.getOrNull()?.let { return it }
            runCatching { value.asString.toLong() }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.getInt(vararg keys: String): Int? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            runCatching { value.asInt }.getOrNull()?.let { return it }
            runCatching { value.asDouble.toInt() }.getOrNull()?.let { return it }
            runCatching { value.asString.toInt() }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.getFloat(vararg keys: String): Float? {
        keys.forEach { key ->
            val value = this.get(key) ?: return@forEach
            if (value.isJsonNull) return@forEach
            runCatching { value.asFloat }.getOrNull()?.let { return it }
            runCatching { value.asDouble.toFloat() }.getOrNull()?.let { return it }
            runCatching { value.asString.toFloat() }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun pruneOldItems(map: MutableMap<String, WatchProgress>): Map<String, WatchProgress> {
        return map
    }
}
