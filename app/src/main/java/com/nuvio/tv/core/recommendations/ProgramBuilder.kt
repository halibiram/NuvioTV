package com.nuvio.tv.core.recommendations

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.NextUpInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds [PreviewProgram] and [WatchNextProgram] instances from domain models.
 */
@Singleton
class ProgramBuilder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ProgramBuilder"
    }

    // ────────────────────────────────────────────────────────────────
    //  Continue Watching → PreviewProgram
    // ────────────────────────────────────────────────────────────────

    fun buildContinueWatchingProgram(
        channelId: Long,
        progress: WatchProgress
    ): PreviewProgram {
        val isMovie = progress.contentType == "movie"
        val programType = if (isMovie) {
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        } else {
            TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
        }

        val description = if (!isMovie && progress.season != null && progress.episode != null) {
            buildString {
                append("S${progress.season}E${progress.episode}")
                progress.episodeTitle?.let { append(" · $it") }
            }
        } else {
            null
        }

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(programType)
            .setTitle(progress.name)
            .setInternalProviderId("cw_${progress.contentId}_${progress.videoId}")
            .setIntentUri(buildPlayUri(progress))
            .setLive(false)

        description?.let { builder.setDescription(it) }
        progress.poster?.let { builder.setPosterArtUri(Uri.parse(it)) }
        progress.backdrop?.let { builder.setThumbnailUri(Uri.parse(it)) }

        if (!isMovie) {
            progress.season?.let { builder.setSeasonNumber(it.toString()) }
            progress.episode?.let { builder.setEpisodeNumber(it.toString()) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        // Progress bar on the card
        if (progress.duration > 0) {
            builder.setLastPlaybackPositionMillis(progress.position.toInt())
            builder.setDurationMillis(progress.duration.toInt())
        }

        return builder.build()
    }

    // ────────────────────────────────────────────────────────────────
    //  Next Up → PreviewProgram
    // ────────────────────────────────────────────────────────────────

    fun buildNextUpProgram(
        channelId: Long,
        nextUp: NextUpInfo
    ): PreviewProgram {
        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
            .setTitle(nextUp.name)
            .setDescription("S${nextUp.season}E${nextUp.episode}" +
                    (nextUp.episodeTitle?.let { " · $it" } ?: ""))
            .setSeasonNumber(nextUp.season.toString())
            .setEpisodeNumber(nextUp.episode.toString())
            .setInternalProviderId("nu_${nextUp.contentId}_s${nextUp.season}e${nextUp.episode}")
            .setIntentUri(buildNextUpPlayUri(nextUp))
            .setLive(false)

        nextUp.episodeTitle?.let { builder.setEpisodeTitle(it) }
        nextUp.poster?.let { builder.setPosterArtUri(Uri.parse(it)) }
        (nextUp.thumbnail ?: nextUp.backdrop)?.let { builder.setThumbnailUri(Uri.parse(it)) }

        return builder.build()
    }

    // ────────────────────────────────────────────────────────────────
    //  Trending → PreviewProgram
    // ────────────────────────────────────────────────────────────────

    fun buildTrendingProgram(
        channelId: Long,
        item: MetaPreview
    ): PreviewProgram {
        val programType = when (item.type.toApiString()) {
            "series" -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
            else -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
        }

        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(programType)
            .setTitle(item.name)
            .setInternalProviderId("tr_${item.id}")
            .setIntentUri(buildDetailUri(item.id, item.type.toApiString()))
            .setLive(false)

        item.description?.let { builder.setDescription(it) }
        item.poster?.let { builder.setPosterArtUri(Uri.parse(it)) }
        item.background?.let { builder.setThumbnailUri(Uri.parse(it)) }
        item.releaseInfo?.let { builder.setReleaseDate(it) }
        item.genres.firstOrNull()?.let { builder.setGenre(it) }

        return builder.build()
    }

    // ────────────────────────────────────────────────────────────────
    //  Watch Next Row (system-managed row)
    // ────────────────────────────────────────────────────────────────

    fun buildWatchNextProgram(progress: WatchProgress): WatchNextProgram {
        val isMovie = progress.contentType == "movie"
        val programType = if (isMovie) {
            TvContractCompat.WatchNextPrograms.TYPE_MOVIE
        } else {
            TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
        }

        val builder = WatchNextProgram.Builder()
            .setType(programType)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setTitle(progress.name)
            .setLastEngagementTimeUtcMillis(progress.lastWatched)
            .setInternalProviderId("wn_${progress.contentId}")
            .setIntentUri(buildPlayUri(progress))

        progress.poster?.let { builder.setPosterArtUri(Uri.parse(it)) }
        progress.backdrop?.let { builder.setThumbnailUri(Uri.parse(it)) }

        if (progress.duration > 0) {
            builder.setLastPlaybackPositionMillis(progress.position.toInt())
            builder.setDurationMillis(progress.duration.toInt())
        }

        if (!isMovie) {
            progress.season?.let { builder.setSeasonNumber(it.toString()) }
            progress.episode?.let { builder.setEpisodeNumber(it.toString()) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        return builder.build()
    }

    // ────────────────────────────────────────────────────────────────
    //  Watch Next insert / update helpers
    // ────────────────────────────────────────────────────────────────

    /**
     * Adds or updates a program in the system Watch Next row.
     */
    fun upsertWatchNextProgram(program: WatchNextProgram, internalId: String) {
        try {
            val existingId = findWatchNextByInternalId(internalId)
            if (existingId != null) {
                val uri = TvContractCompat.buildWatchNextProgramUri(existingId)
                context.contentResolver.update(uri, program.toContentValues(), null, null)
                Log.d(TAG, "Updated Watch Next program: $internalId")
            } else {
                context.contentResolver.insert(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    program.toContentValues()
                )
                Log.d(TAG, "Inserted Watch Next program: $internalId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upsert Watch Next program: $internalId", e)
        }
    }

    /**
     * Removes a program from the Watch Next row by its internal provider id.
     */
    fun removeWatchNextProgram(internalId: String) {
        try {
            val existingId = findWatchNextByInternalId(internalId) ?: return
            val uri = TvContractCompat.buildWatchNextProgramUri(existingId)
            context.contentResolver.delete(uri, null, null)
            Log.d(TAG, "Removed Watch Next program: $internalId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove Watch Next program: $internalId", e)
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Deep-link URI builders
    // ────────────────────────────────────────────────────────────────

    private fun buildPlayUri(progress: WatchProgress): Uri =
        Uri.Builder()
            .scheme(RecommendationConstants.DEEP_LINK_SCHEME)
            .authority(RecommendationConstants.DEEP_LINK_HOST)
            .appendPath(RecommendationConstants.DEEP_LINK_PATH_PLAY)
            .appendPath(progress.contentId)
            .appendQueryParameter(RecommendationConstants.PARAM_CONTENT_TYPE, progress.contentType)
            .appendQueryParameter(RecommendationConstants.PARAM_VIDEO_ID, progress.videoId)
            .apply {
                progress.season?.let {
                    appendQueryParameter(RecommendationConstants.PARAM_SEASON, it.toString())
                }
                progress.episode?.let {
                    appendQueryParameter(RecommendationConstants.PARAM_EPISODE, it.toString())
                }
                appendQueryParameter(
                    RecommendationConstants.PARAM_RESUME_POSITION,
                    progress.position.toString()
                )
            }
            .build()

    private fun buildNextUpPlayUri(nextUp: NextUpInfo): Uri =
        Uri.Builder()
            .scheme(RecommendationConstants.DEEP_LINK_SCHEME)
            .authority(RecommendationConstants.DEEP_LINK_HOST)
            .appendPath(RecommendationConstants.DEEP_LINK_PATH_PLAY)
            .appendPath(nextUp.contentId)
            .appendQueryParameter(RecommendationConstants.PARAM_CONTENT_TYPE, nextUp.contentType)
            .appendQueryParameter(RecommendationConstants.PARAM_VIDEO_ID, nextUp.videoId)
            .appendQueryParameter(RecommendationConstants.PARAM_SEASON, nextUp.season.toString())
            .appendQueryParameter(RecommendationConstants.PARAM_EPISODE, nextUp.episode.toString())
            .build()

    private fun buildDetailUri(contentId: String, type: String): Uri =
        Uri.Builder()
            .scheme(RecommendationConstants.DEEP_LINK_SCHEME)
            .authority(RecommendationConstants.DEEP_LINK_HOST)
            .appendPath(RecommendationConstants.DEEP_LINK_PATH_DETAIL)
            .appendPath(contentId)
            .appendQueryParameter(RecommendationConstants.PARAM_CONTENT_TYPE, type)
            .build()

    // ────────────────────────────────────────────────────────────────
    //  Watch Next query helper
    // ────────────────────────────────────────────────────────────────

    private fun findWatchNextByInternalId(internalId: String): Long? {
        var cursor: android.database.Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    TvContractCompat.WatchNextPrograms._ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )
            cursor?.let {
                while (it.moveToNext()) {
                    val idIdx = it.getColumnIndex(
                        TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                    )
                    if (idIdx >= 0 && it.getString(idIdx) == internalId) {
                        val pkIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                        if (pkIdx >= 0) return it.getLong(pkIdx)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Watch Next programs", e)
            null
        } finally {
            cursor?.close()
        }
    }
}
