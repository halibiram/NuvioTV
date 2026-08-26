package com.nuvio.tv.core.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.nuvio.tv.domain.model.WatchProgress

data class PlaybackPrewarmStreamKey(
    val type: String,
    val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val contentId: String? = null
)

data class PlaybackPrewarmMediaRequest(
    val url: String,
    val headers: Map<String, String>,
    val filename: String? = null,
    val resumePositionMs: Long = 0L,
    val resumeProgress: WatchProgress? = null,
    val contentId: String? = null,
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val startFromBeginning: Boolean = false,
    val isTorrent: Boolean = false
)

data class PlaybackPrewarmSession(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val filename: String? = null,
    val mimeType: String? = null,
    val resumePositionMs: Long = 0L,
    val resumeProgress: WatchProgress? = null,
    val streamKey: PlaybackPrewarmStreamKey? = null,
    val afrComplete: Boolean = false,
    val prepareStarted: Boolean = false,
    val startedAtElapsedMs: Long = 0L
)

data class PlaybackPrewarmEngineSnapshot(
    val player: ExoPlayer,
    val mimeType: String?,
    val trackSelector: DefaultTrackSelector?,
    val useLibass: Boolean,
    val resumePositionMs: Long,
    val prepareStartedAtElapsedMs: Long
)

data class PlaybackPrewarmTicket(
    val session: PlaybackPrewarmSession,
    val engineSnapshot: PlaybackPrewarmEngineSnapshot?
)

sealed class PlaybackPrewarmClaimResult {
    data class Hit(val ticket: PlaybackPrewarmTicket) : PlaybackPrewarmClaimResult()
    data object Miss : PlaybackPrewarmClaimResult()
    data object Mismatch : PlaybackPrewarmClaimResult()
}

internal sealed class PlaybackPrewarmBeginResult {
    data object AlreadyWarm : PlaybackPrewarmBeginResult()
    data class Started(val previous: PlaybackPrewarmSession?) : PlaybackPrewarmBeginResult()
}
