package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegDecoderException
import com.nuvio.tv.core.util.findCause

/** Keep stock prebuilt AAR builds valid; E-AC-3 needs the rebuilt decoder module/AAR. */
internal object Eac3TranscodingSupport {
    private val layoutMismatchMethod by lazy {
        runCatching {
            FfmpegDecoderException::class.java.getMethod("isEac3LayoutMismatch")
        }.getOrNull()
    }

    val available: Boolean by lazy {
        runCatching {
            FfmpegAudioRenderer::class.java.getMethod("setPreferEac3Transcoding", Boolean::class.javaPrimitiveType)
            FfmpegAudioRenderer::class.java.getMethod("setForceEac3FiveOne", Boolean::class.javaPrimitiveType)
            true
        }.getOrDefault(false)
    }

    fun isLayoutMismatch(error: PlaybackException): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false
        val cause = error.findCause<FfmpegDecoderException>() ?: return false
        return layoutMismatchMethod?.invoke(cause) == true
    }
}

internal fun FfmpegAudioRenderer.applyEac3Transcoding(enabled: Boolean, forceFiveOne: Boolean) {
    if (!Eac3TranscodingSupport.available) return
    javaClass.getMethod("setPreferEac3Transcoding", Boolean::class.javaPrimitiveType).invoke(this, enabled)
    javaClass.getMethod("setForceEac3FiveOne", Boolean::class.javaPrimitiveType).invoke(this, forceFiveOne)
}
