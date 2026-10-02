package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** Hardware first for VC-1: the device's own decoder when it lists one, the FFmpeg video renderer when it does not. */
internal object Vc1DecoderSelector {

    // Amlogic registers its VC-1 decoder under this name instead of video/wvc1.
    private const val VIDEO_VC1_ALTERNATE = "video/vc1"

    /**
     * VC-1 is looked up under both MIME names. WMV 7/8/9 stays with the FFmpeg video renderer whenever that
     * renderer is part of the player ([softwareRendererAvailable]); without it nothing is hidden from MediaCodec.
     * [forceSoftware] sends VC-1 there as well, for a stream whose device decoder would not start.
     */
    fun wrap(
        base: MediaCodecSelector,
        softwareRendererAvailable: Boolean,
        forceSoftware: Boolean = false
    ): MediaCodecSelector {
        return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            when {
                Vc1VideoFormatHeuristics.isVc1Mime(mimeType) && !(forceSoftware && softwareRendererAvailable) ->
                    base.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder).ifEmpty {
                        base.getDecoderInfos(
                            alternateVc1Mime(mimeType), requiresSecureDecoder, requiresTunnelingDecoder
                        )
                    }
                softwareRendererAvailable && Vc1VideoFormatHeuristics.isVc1OrWmvMime(mimeType) -> emptyList()
                else -> base.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            }
        }
    }

    /**
     * A decoder found under the other VC-1 name never matches the track's own MIME string, so the stock check
     * rates the track "exceeds capabilities" and it would go to the FFmpeg renderer, which claims it as handled.
     * A listed VC-1 decoder counts as handling the track instead.
     */
    fun hardwareFirstSupport(sampleMimeType: String?, support: Int): Int {
        if (!Vc1VideoFormatHeuristics.isVc1Mime(sampleMimeType) ||
            RendererCapabilities.getFormatSupport(support) != C.FORMAT_EXCEEDS_CAPABILITIES
        ) {
            return support
        }
        return (support and RendererCapabilities.FORMAT_SUPPORT_MASK.inv()) or C.FORMAT_HANDLED
    }

    private fun alternateVc1Mime(mimeType: String): String =
        if (mimeType.equals(MimeTypes.VIDEO_VC1, ignoreCase = true)) VIDEO_VC1_ALTERNATE else MimeTypes.VIDEO_VC1
}
