package com.nuvio.tv.core.player

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadMediaSource

/**
 * Rules for PreloadMediaSource prefetch during player construction.
 * ExoPlayer.prepare() stays on the claim path after a surface exists.
 */
@OptIn(UnstableApi::class)
internal object PlaybackPrewarmPreloadPolicy {
    const val TARGET_BUFFER_US = 3_000_000L

    fun canAttachPreload(playbackLooper: Looper?): Boolean {
        return playbackLooper != null && playbackLooper !== Looper.getMainLooper()
    }

    fun startPositionUs(resumePositionMs: Long): Long {
        val resume = resumePositionMs.coerceAtLeast(0L)
        return if (resume > 0L) resume * 1_000L else C.TIME_UNSET
    }

    fun shouldContinueLoading(bufferedDurationUs: Long): Boolean {
        return bufferedDurationUs < TARGET_BUFFER_US
    }
}

@OptIn(UnstableApi::class)
internal class DurationLimitedPreloadControl(
    private val targetBufferUs: Long = PlaybackPrewarmPreloadPolicy.TARGET_BUFFER_US
) : PreloadMediaSource.PreloadControl {
    override fun onSourcePrepared(mediaSource: PreloadMediaSource): Boolean = true

    override fun onTracksSelected(mediaSource: PreloadMediaSource): Boolean = true

    override fun onContinueLoadingRequested(
        mediaSource: PreloadMediaSource,
        bufferedDurationUs: Long
    ): Boolean = bufferedDurationUs < targetBufferUs

    override fun onUsedByPlayer(mediaSource: PreloadMediaSource) = Unit

    override fun onPreloadError(error: PreloadException, mediaSource: PreloadMediaSource) = Unit
}
