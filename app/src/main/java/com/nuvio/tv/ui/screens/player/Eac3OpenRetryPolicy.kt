package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException

internal class Eac3OpenRetryPolicy {
    private var failedStreamRoute: Pair<String, String?>? = null

    fun shouldLimitToFiveOne(streamUrl: String, routeKey: String?): Boolean =
        failedStreamRoute == (streamUrl to routeKey)

    fun recordOpenFailure(channelCount: Int, streamUrl: String, routeKey: String?): Boolean {
        if (channelCount != 8 || shouldLimitToFiveOne(streamUrl, routeKey)) return false
        failedStreamRoute = streamUrl to routeKey
        return true
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun failedTranscodedEac3Output(error: PlaybackException, ffmpegRendererName: String?): Format? {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return null
    val rendererError = error as? ExoPlaybackException ?: return null
    if (ffmpegRendererName == null || rendererError.rendererName != ffmpegRendererName) return null
    // DecoderAudioRenderer attaches the sink's output format to initialization/configuration errors.
    // The FFmpeg renderer only outputs E-AC-3 when the encoder is active.
    return rendererError.rendererFormat?.takeIf { it.sampleMimeType == MimeTypes.AUDIO_E_AC3 }
}
