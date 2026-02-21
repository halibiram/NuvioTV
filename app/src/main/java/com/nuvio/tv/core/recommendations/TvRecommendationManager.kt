package com.nuvio.tv.core.recommendations

import android.content.Context
import android.content.pm.PackageManager
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.screens.home.NextUpInfo
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
                channelManager.getOrCreateChannel(
                    RecommendationConstants.CHANNEL_CONTINUE_WATCHING,
                    RecommendationConstants.CHANNEL_DISPLAY_CONTINUE_WATCHING
                )
                channelManager.getOrCreateChannel(
                    RecommendationConstants.CHANNEL_NEXT_UP,
                    RecommendationConstants.CHANNEL_DISPLAY_NEXT_UP
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
     * Updates the **Continue Watching** channel with the latest in-progress items.
     */
    suspend fun updateContinueWatching() {
        if (!shouldRun()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val channelId = channelManager.getOrCreateChannel(
                        RecommendationConstants.CHANNEL_CONTINUE_WATCHING,
                        RecommendationConstants.CHANNEL_DISPLAY_CONTINUE_WATCHING
                    ) ?: return@withContext

                    val items = watchProgressRepository.continueWatching
                        .first()
                        .take(RecommendationConstants.MAX_CONTINUE_WATCHING_ITEMS)

                    // Replace all programs in one shot
                    channelManager.clearProgramsForChannel(channelId)

                    val programs = items.map { progress ->
                        programBuilder.buildContinueWatchingProgram(channelId, progress)
                    }
                    channelManager.insertPrograms(programs)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Updates the **Watch Next** system row with the user's in-progress items.
     */
    suspend fun updateWatchNext() {
        if (!shouldRun()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val items = watchProgressRepository.continueWatching
                        .first()
                        .take(RecommendationConstants.MAX_WATCH_NEXT_ITEMS)

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
     */
    suspend fun onProgressUpdated(progress: WatchProgress) {
        if (!shouldRun()) return
        // Refresh the full Continue Watching channel (acquires mutex internally)
        updateContinueWatching()
        // Update the individual Watch Next entry separately
        withContext(Dispatchers.IO) {
            try {
                val program = programBuilder.buildWatchNextProgram(progress)
                val internalId = "wn_${progress.contentId}"
                programBuilder.upsertWatchNextProgram(program, internalId)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Publishes Next Up items to their dedicated channel.
     * Must be called with already-resolved [NextUpInfo] items (the caller is
     * responsible for the meta-data look-ups required to determine next episodes).
     */
    suspend fun updateNextUp(nextUpItems: List<NextUpInfo>) {
        if (!shouldRun()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val channelId = channelManager.getOrCreateChannel(
                        RecommendationConstants.CHANNEL_NEXT_UP,
                        RecommendationConstants.CHANNEL_DISPLAY_NEXT_UP
                    ) ?: return@withContext

                    channelManager.clearProgramsForChannel(channelId)

                    val programs = nextUpItems
                        .take(RecommendationConstants.MAX_NEXT_UP_ITEMS)
                        .map { programBuilder.buildNextUpProgram(channelId, it) }

                    channelManager.insertPrograms(programs)
                } catch (_: Exception) {
                }
            }
        }
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
        updateContinueWatching()
        updateWatchNext()
        // Note: Next Up and Trending are updated from HomeViewModel where the
        // required meta / catalog data is already available.
    }

    /**
     * Removes all channels and Watch Next entries created by this app.
     */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            channelManager.deleteChannel(RecommendationConstants.CHANNEL_CONTINUE_WATCHING)
            channelManager.deleteChannel(RecommendationConstants.CHANNEL_NEXT_UP)
            channelManager.deleteChannel(RecommendationConstants.CHANNEL_TRENDING)
            programBuilder.clearAllWatchNextPrograms()
            lastTrendingSignature = null
        }
    }

    /**
     * Called when a watch progress entry is removed by the user.
     * Refreshes the Continue Watching channel and removes the Watch Next entry.
     */
    suspend fun onProgressRemoved(contentId: String) {
        if (!shouldRun()) return
        updateContinueWatching()
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

    private suspend fun shouldRun(): Boolean =
        isTvDevice() && dataStore.isEnabled()

    private fun isTvDevice(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
