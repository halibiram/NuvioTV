package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val episodeDescription: String? = null,
    val thumbnail: String?,
    val released: String? = null,
    val hasAired: Boolean = true,
    val airDateLabel: String? = null,
    val lastWatched: Long,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val sortTimestamp: Long,
    val releaseTimestamp: Long? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val contentLanguage: String? = null
)

data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float?,
    val episodeThumbnail: String? = null,
    val episodeDescription: String? = null,
    val episodeImdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val contentLanguage: String? = null
)

@Singleton
class ContinueWatchingEnrichmentCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "CwEnrichCache"
        private const val THROTTLE_MS = 1_000L
    }

    private val gson = Gson()
    private val mutex = Mutex()
    @Volatile private var lastNextUpWriteMs = 0L
    @Volatile private var lastInProgressWriteMs = 0L
    private val lastNextUpJsonByProfile = ConcurrentHashMap<Int, String>()
    private val lastInProgressJsonByProfile = ConcurrentHashMap<Int, String>()

    /** Incremented when cache is cleared; observers can collect to trigger refresh. */
    private val _cacheCleared = kotlinx.coroutines.flow.MutableStateFlow(0)
    val cacheCleared: kotlinx.coroutines.flow.StateFlow<Int> = _cacheCleared

    /** Incremented on every successful snapshot write; channel sync observes this. */
    private val _snapshotVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val snapshotVersion: kotlinx.coroutines.flow.StateFlow<Int> = _snapshotVersion

    // --- Next Up snapshot cache ---

    private fun nextUpFile(): File {
        val profileId = profileManager.activeProfileId.value
        val dir = File(context.filesDir, "cw_enrichment")
        dir.mkdirs()
        return File(dir, "nextup_${profileId}.json")
    }

    suspend fun getNextUpSnapshot(): List<CachedNextUpItem> = readSnapshot(
        file = { nextUpFile() },
        type = object : TypeToken<List<CachedNextUpItem>>() {}.type
    )

    /**
     * @param force bypass the time throttle. Identical payloads are still skipped so a
     * focused Continue Watching row does not rewrite the same JSON on every pipeline pass.
     */
    suspend fun saveNextUpSnapshot(items: List<CachedNextUpItem>, force: Boolean = false) {
        saveSnapshot(
            items = items,
            force = force,
            lastWriteMs = { lastNextUpWriteMs },
            updateLastWriteMs = { lastNextUpWriteMs = it },
            jsonByProfile = lastNextUpJsonByProfile,
            file = { nextUpFile() }
        )
    }

    // --- In-progress snapshot cache ---

    private fun inProgressFile(): File {
        val profileId = profileManager.activeProfileId.value
        val dir = File(context.filesDir, "cw_enrichment")
        dir.mkdirs()
        return File(dir, "inprogress_${profileId}.json")
    }

    suspend fun getInProgressSnapshot(): List<CachedInProgressItem> = readSnapshot(
        file = { inProgressFile() },
        type = object : TypeToken<List<CachedInProgressItem>>() {}.type
    )

    /**
     * @param force bypass the time throttle. Identical payloads are still skipped.
     */
    suspend fun saveInProgressSnapshot(items: List<CachedInProgressItem>, force: Boolean = false) {
        saveSnapshot(
            items = items,
            force = force,
            lastWriteMs = { lastInProgressWriteMs },
            updateLastWriteMs = { lastInProgressWriteMs = it },
            jsonByProfile = lastInProgressJsonByProfile,
            file = { inProgressFile() }
        )
    }

    /**
     * Deletes all CW enrichment cache files for the active profile.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val profileId = profileManager.activeProfileId.value
                nextUpFile().delete()
                inProgressFile().delete()
                lastNextUpJsonByProfile.remove(profileId)
                lastInProgressJsonByProfile.remove(profileId)
                lastNextUpWriteMs = 0L
                lastInProgressWriteMs = 0L
                Log.d(TAG, "Cleared CW enrichment cache for profile ${profileManager.activeProfileId.value}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear CW enrichment cache: ${e.message}")
            }
        }
        _cacheCleared.value++
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> readSnapshot(file: () -> File, type: java.lang.reflect.Type): T =
        withContext(Dispatchers.IO) {
            val text = try {
                mutex.withLock {
                    val target = file()
                    if (!target.exists()) null else target.readText()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read CW cache: ${e.message}")
                return@withContext emptyList<Any>() as T
            }
            if (text == null) return@withContext emptyList<Any>() as T
            withContext(Dispatchers.Default) {
                try {
                    gson.fromJson<T>(text, type) ?: emptyList<Any>() as T
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse CW cache: ${e.message}")
                    emptyList<Any>() as T
                }
            }
        }

    private suspend fun saveSnapshot(
        items: List<*>,
        force: Boolean,
        lastWriteMs: () -> Long,
        updateLastWriteMs: (Long) -> Unit,
        jsonByProfile: ConcurrentHashMap<Int, String>,
        file: () -> File
    ) {
        // Queue in call order. Gson runs after the turn is taken, so a newer
        // snapshot cannot be overwritten by an older one that finished encoding first.
        mutex.withLock {
            val profileId = profileManager.activeProfileId.value
            if (!force && System.currentTimeMillis() - lastWriteMs() < THROTTLE_MS) return@withLock
            val json = withContext(Dispatchers.Default) { gson.toJson(items) }
            val target = file()
            if (jsonByProfile[profileId] == json && target.exists()) return@withLock
            try {
                atomicWrite(target, json)
                updateLastWriteMs(System.currentTimeMillis())
                jsonByProfile[profileId] = json
                _snapshotVersion.value++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write CW cache: ${e.message}")
            }
        }
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // renameTo can fail on some filesystems; fall back to copy+delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

}
