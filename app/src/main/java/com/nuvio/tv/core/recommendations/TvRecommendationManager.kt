package com.nuvio.tv.core.recommendations

import android.content.Context
import android.content.pm.PackageManager
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level coordinator that orchestrates channel creation, program publishing,
 * and Watch Next row updates for Android TV Home Screen recommendations.
 *
 * All public methods are safe to call from any dispatcher — heavy work is
 * dispatched to [Dispatchers.IO] internally.
 */
@Singleton
class TvRecommendationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channelManager: ChannelManager,
    private val programBuilder: ProgramBuilder,
    private val dataStore: RecommendationDataStore,
    private val watchProgressRepository: WatchProgressRepository
) {

    /** Serializes channel-update operations to avoid races from multiple triggers. */
    private val mutex = Mutex()

    /** Tracks the last set of trending items to avoid redundant ContentProvider writes. */
    @Volatile
    private var lastTrendingSignature: String? = null

    /** Tracks the last set of new release items to avoid redundant ContentProvider writes. */
    @Volatile
    private var lastNewReleasesSignature: String? = null

    // ────────────────────────────────────────────────────────────────
    //  Public API
    // ────────────────────────────────────────────────────────────────

    /**
     * One-time initialization — creates channels if they don't exist yet.
     * Called from [NuvioApplication.onCreate].
     */
    suspend fun initializeChannels() {
        if (!isTvDevice()) return
        withContext(Dispatchers.IO) {
            try {
                // Clean up obsolete/legacy channels by aggressively sweeping all channels
                // owned by the app except the valid ones
                val validIds = listOf(
                    RecommendationConstants.CHANNEL_NEW_RELEASES,
                    RecommendationConstants.CHANNEL_TRENDING
                )
                channelManager.cleanupLegacyChannels(validIds)

                channelManager.getOrCreateChannel(
                    RecommendationConstants.CHANNEL_NEW_RELEASES,
                    RecommendationConstants.CHANNEL_DISPLAY_NEW_RELEASES
                )
                channelManager.getOrCreateChannel(
                    RecommendationConstants.CHANNEL_TRENDING,
                    RecommendationConstants.CHANNEL_DISPLAY_TRENDING
                )
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Updates the **New Releases** channel with new movies and series combined.
     * Called from [HomeViewModel] after catalog rows are loaded.
     * Skips redundant writes when the item set hasn't changed.
     */
    suspend fun updateNewReleases(items: List<MetaPreview>) {
        if (!shouldRun()) return
        val trimmed = items.take(RecommendationConstants.MAX_NEW_RELEASES_ITEMS)
        val signature = trimmed.joinToString("|") { it.id }
        if (signature == lastNewReleasesSignature) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val channelId = channelManager.getOrCreateChannel(
                        RecommendationConstants.CHANNEL_NEW_RELEASES,
                        RecommendationConstants.CHANNEL_DISPLAY_NEW_RELEASES
                    ) ?: return@withContext

                    channelManager.clearProgramsForChannel(channelId)

                    val programs = trimmed
                        .map { programBuilder.buildTrendingProgram(channelId, it) }

                    channelManager.insertPrograms(programs)
                    lastNewReleasesSignature = signature
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Updates the **Watch Next** system row with the user's in-progress items.
     * Performs a full clear-and-rebuild to ensure no stale entries remain.
     */
    suspend fun updateWatchNext() {
        if (!shouldRun()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    // Clear ALL our Watch Next entries first to remove stale ones
                    programBuilder.clearAllWatchNextPrograms()

                    val items = deduplicateByContent(
                        watchProgressRepository.continueWatching.first()
                    ).take(RecommendationConstants.MAX_WATCH_NEXT_ITEMS)

                    for (progress in items) {
                        val program = programBuilder.buildWatchNextProgram(progress)
                        val internalId = "wn_${progress.contentId}"
                        programBuilder.upsertWatchNextProgram(program, internalId)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Convenience method called when a single progress entry is saved/updated.
     * Refreshes Continue Watching channel + Watch Next row.
     * Both are fully rebuilt to ensure no stale entries remain.
     */
    suspend fun onProgressUpdated(progress: WatchProgress) {
        if (!shouldRun()) return
        updateWatchNext()
    }

    /**
     * Publishes Trending items to the dedicated Trending channel.
     * Called from [HomeViewModel] after catalog rows are loaded.
     * Skips redundant writes when the item set hasn't changed.
     */
    suspend fun updateTrending(items: List<MetaPreview>) {
        if (!shouldRun()) return
        val trimmed = items.take(RecommendationConstants.MAX_TRENDING_ITEMS)
        val signature = trimmed.joinToString("|") { it.id }
        if (signature == lastTrendingSignature) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val channelId = channelManager.getOrCreateChannel(
                        RecommendationConstants.CHANNEL_TRENDING,
                        RecommendationConstants.CHANNEL_DISPLAY_TRENDING
                    ) ?: return@withContext

                    channelManager.clearProgramsForChannel(channelId)

                    val programs = trimmed
                        .map { programBuilder.buildTrendingProgram(channelId, it) }

                    channelManager.insertPrograms(programs)
                    lastTrendingSignature = signature
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Full sync — updates all channels.  Called by [TvRecommendationWorker].
     */
    suspend fun syncAllChannels() {
        if (!shouldRun()) return
        initializeChannels()
        updateWatchNext()
        // Note: New Releases and Trending are updated from HomeViewModel where the
        // required meta / catalog data is already available.
    }

    /**
     * Removes all channels and Watch Next entries created by this app.
     */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            channelManager.deleteChannel(RecommendationConstants.CHANNEL_NEW_RELEASES)
            channelManager.deleteChannel(RecommendationConstants.CHANNEL_TRENDING)
            programBuilder.clearAllWatchNextPrograms()
            lastTrendingSignature = null
            lastNewReleasesSignature = null
        }
    }

    /**
     * Called when a watch progress entry is removed by the user.
     * Removes the Watch Next entry.
     */
    suspend fun onProgressRemoved(contentId: String) {
        if (!shouldRun()) return
        withContext(Dispatchers.IO) {
            try {
                programBuilder.removeWatchNextProgram("wn_$contentId")
            } catch (_: Exception) {
            }
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────

    /**
     * Deduplicates progress entries per contentId, keeping only the most
     * recently watched entry for each content item. This prevents showing
     * multiple episodes of the same series in Continue Watching / Watch Next.
     */
    private fun deduplicateByContent(items: List<WatchProgress>): List<WatchProgress> {
        return items
            .sortedByDescending { it.lastWatched }
            .distinctBy { it.contentId }
    }

    private suspend fun shouldRun(): Boolean =
        isTvDevice() && dataStore.isEnabled()

    private fun isTvDevice(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
