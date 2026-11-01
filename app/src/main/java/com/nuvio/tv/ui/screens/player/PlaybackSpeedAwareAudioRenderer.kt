package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

internal class PlaybackSpeedAwareAudioRenderer(
    private val rendererContext: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    private val playbackSpeedAwareAudioSink: PlaybackSpeedAwareAudioSink
) : MediaCodecAudioRenderer(
    rendererContext,
    codecAdapterFactory,
    mediaCodecSelector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    playbackSpeedAwareAudioSink
) {

    private var hasPrimedSinkSinceReset: Boolean = false
    private var readyWaitStartRealtimeMs: Long = C.TIME_UNSET

    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        super.onEnabled(joining, mayRenderStartOfStream)
        hasPrimedSinkSinceReset = false
        readyWaitStartRealtimeMs = C.TIME_UNSET
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        super.onPositionReset(positionUs, joining)
        playbackSpeedAwareAudioSink.onRendererPositionReset(positionUs)
        hasPrimedSinkSinceReset = false
        readyWaitStartRealtimeMs = C.TIME_UNSET
    }

    override fun onDisabled() {
        hasPrimedSinkSinceReset = false
        readyWaitStartRealtimeMs = C.TIME_UNSET
        super.onDisabled()
    }

    override fun onReset() {
        hasPrimedSinkSinceReset = false
        readyWaitStartRealtimeMs = C.TIME_UNSET
        super.onReset()
    }

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        playbackSpeedAwareAudioSink.setUpstreamTrackFormat(formatHolder.format)
        return super.onInputFormatChanged(formatHolder)
    }

    /**
     * Prevents Media3's `MediaCodecRenderer.isReady()` (`isSourceReady()`) from transitioning
     * `ExoPlayerImplInternal` to `STATE_READY` as soon as a single compressed packet enters
     * `SampleQueue`, before the Dolby Atmos decoder (2-3 syncframe JOC lookahead) and
     * `DefaultAudioSink` (`initializeAudioTrack()` + initial buffer prefill) have actually primed.
     */
    override fun isReady(): Boolean {
        if (playbackSpeedAwareAudioSink.hasPendingData()) {
            if (playbackSpeedAwareAudioSink.isPrimedForReady() || hasReadStreamToEnd()) {
                hasPrimedSinkSinceReset = true
                readyWaitStartRealtimeMs = C.TIME_UNSET
                return true
            }
            if (hasPrimedSinkSinceReset) {
                return true
            }
            return isPrimeWaitTimedOut()
        }
        if (!super.isReady()) {
            return false
        }
        if (hasPrimedSinkSinceReset || hasReadStreamToEnd()) {
            return true
        }
        return isPrimeWaitTimedOut()
    }

    private fun isPrimeWaitTimedOut(): Boolean {
        val nowMs = SystemClock.elapsedRealtime()
        if (readyWaitStartRealtimeMs == C.TIME_UNSET) {
            readyWaitStartRealtimeMs = nowMs
            return false
        }
        if (nowMs - readyWaitStartRealtimeMs >= MAX_AUDIO_SINK_PRIME_WAIT_MS) {
            hasPrimedSinkSinceReset = true
            readyWaitStartRealtimeMs = C.TIME_UNSET
            return true
        }
        return false
    }

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
        if (!MimeTypes.isAudio(format.sampleMimeType)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        val formatHasDrm = format.cryptoType != C.CRYPTO_TYPE_NONE
        val supportsFormatDrm = supportsFormatDrm(format)
        val forcePcm = playbackSpeedAwareAudioSink.shouldForcePcmForFormat(format)

        var audioOffloadSupport = AUDIO_OFFLOAD_NOT_SUPPORTED
        if (supportsFormatDrm && (!formatHasDrm || MediaCodecUtil.getDecryptOnlyDecoderInfo() != null)) {
            audioOffloadSupport = getRendererAudioOffloadSupport(format, forcePcm)
            if (!forcePcm && playbackSpeedAwareAudioSink.supportsFormat(format)) {
                return RendererCapabilities.create(
                    C.FORMAT_HANDLED,
                    ADAPTIVE_NOT_SEAMLESS,
                    TUNNELING_SUPPORTED,
                    audioOffloadSupport
                )
            }
        }
        if (MimeTypes.AUDIO_RAW == format.sampleMimeType && !playbackSpeedAwareAudioSink.supportsFormat(format)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        if (!playbackSpeedAwareAudioSink.supportsFormat(
                Util.getPcmFormat(C.ENCODING_PCM_16BIT, format.channelCount, format.sampleRate)
            )
        ) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        val decoderInfos = getDecoderInfos(mediaCodecSelector, format, false)
        if (decoderInfos.isEmpty()) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        if (!supportsFormatDrm) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        }
        var decoderInfo = decoderInfos[0]
        var isFormatSupported = decoderInfo.isFormatSupported(format)
        var isPreferredDecoder = true
        if (!isFormatSupported) {
            for (i in 1 until decoderInfos.size) {
                val otherDecoderInfo = decoderInfos[i]
                if (otherDecoderInfo.isFormatSupported(format)) {
                    decoderInfo = otherDecoderInfo
                    isFormatSupported = true
                    isPreferredDecoder = false
                    break
                }
            }
        }
        val formatSupport = if (isFormatSupported) C.FORMAT_HANDLED else C.FORMAT_EXCEEDS_CAPABILITIES
        val adaptiveSupport =
            if (isFormatSupported && decoderInfo.isSeamlessAdaptationSupported(format)) ADAPTIVE_SEAMLESS
            else ADAPTIVE_NOT_SEAMLESS
        val hardwareAccelerationSupport =
            if (decoderInfo.hardwareAccelerated) HARDWARE_ACCELERATION_SUPPORTED
            else HARDWARE_ACCELERATION_NOT_SUPPORTED
        val decoderSupport = if (isPreferredDecoder) DECODER_SUPPORT_PRIMARY else DECODER_SUPPORT_FALLBACK
        return RendererCapabilities.create(
            formatSupport,
            adaptiveSupport,
            TUNNELING_SUPPORTED,
            hardwareAccelerationSupport,
            decoderSupport,
            audioOffloadSupport
        )
    }

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean
    ): List<MediaCodecInfo> {
        val decoderInfos = if (!playbackSpeedAwareAudioSink.shouldForcePcmForFormat(format) && playbackSpeedAwareAudioSink.supportsFormat(format)) {
            MediaCodecUtil.getDecryptOnlyDecoderInfo()?.let(::listOf)
                ?: MediaCodecUtil.getDecoderInfosSoftMatch(
                    mediaCodecSelector,
                    format,
                    requiresSecureDecoder,
                    false
                )
        } else {
            MediaCodecUtil.getDecoderInfosSoftMatch(
                mediaCodecSelector,
                format,
                requiresSecureDecoder,
                false
            )
        }
        return MediaCodecUtil.getDecoderInfosSortedByFormatSupport(decoderInfos, format)
    }

    override fun shouldUseBypass(format: Format): Boolean {
        if (playbackSpeedAwareAudioSink.shouldForcePcmForFormat(format)) {
            return false
        }
        return super.shouldUseBypass(format)
    }

    private fun getRendererAudioOffloadSupport(format: Format, forcePcm: Boolean): Int {
        if (forcePcm) {
            return AUDIO_OFFLOAD_NOT_SUPPORTED
        }
        val audioSinkOffloadSupport = playbackSpeedAwareAudioSink.getFormatOffloadSupport(format)
        if (!audioSinkOffloadSupport.isFormatSupported) {
            return AUDIO_OFFLOAD_NOT_SUPPORTED
        }
        var audioOffloadSupport = AUDIO_OFFLOAD_SUPPORTED
        if (audioSinkOffloadSupport.isGaplessSupported) {
            audioOffloadSupport = audioOffloadSupport or AUDIO_OFFLOAD_GAPLESS_SUPPORTED
        }
        if (audioSinkOffloadSupport.isSpeedChangeSupported) {
            audioOffloadSupport = audioOffloadSupport or AUDIO_OFFLOAD_SPEED_CHANGE_SUPPORTED
        }
        return audioOffloadSupport
    }

    private companion object {
        const val MAX_AUDIO_SINK_PRIME_WAIT_MS = 350L
    }
}
