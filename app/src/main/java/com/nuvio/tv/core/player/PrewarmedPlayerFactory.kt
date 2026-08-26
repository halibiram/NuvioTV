package com.nuvio.tv.core.player

/**
 * Builds a content ExoPlayer that PlayerScreen can claim.
 *
 * [prepare] constructs the player, wraps the source in PreloadMediaSource, and
 * starts sample-queue prefetch. It does not call ExoPlayer.prepare(), so the HW
 * decoder stays free until a surface exists.
 */
interface PrewarmedPlayerFactory {
    suspend fun prepare(request: PlaybackPrewarmMediaRequest): PlaybackPrewarmEngineSnapshot?
    fun release(snapshot: PlaybackPrewarmEngineSnapshot)
}
