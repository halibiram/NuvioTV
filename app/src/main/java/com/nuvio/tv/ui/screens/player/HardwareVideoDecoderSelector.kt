package com.nuvio.tv.ui.screens.player

import android.media.MediaCodecList
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import java.nio.ByteBuffer

private val HARDWARE_FIRST_VIDEO_TYPES = setOf(
    MimeTypes.VIDEO_H264,
    MimeTypes.VIDEO_H265,
    MimeTypes.VIDEO_VP9,
    MimeTypes.VIDEO_AV1,
    MimeTypes.VIDEO_DOLBY_VISION,
)

private val SOFTWARE_DECODER_PREFIXES = listOf("omx.google.", "c2.android.", "c2.google.", "omx.ffmpeg.")

/**
 * Android's software decoders are left out only when a hardware decoder supports this exact format
 * (profile, level, size), so a stream beyond the hardware still plays in software.
 */
internal fun <T> hardwareFirstVideoDecoders(
    mimeType: String?,
    decoders: List<T>,
    isSoftware: (T) -> Boolean,
    supportsFormat: (T) -> Boolean,
): List<T> {
    if (mimeType !in HARDWARE_FIRST_VIDEO_TYPES) return decoders
    val hardware = decoders.filterNot(isSoftware)
    return if (hardware.any(supportsFormat)) hardware else decoders
}

internal fun hardwareFirstVideoDecoders(format: Format, decoders: List<MediaCodecInfo>): List<MediaCodecInfo> =
    hardwareFirstVideoDecoders(format.sampleMimeType, decoders, ::isSoftwareDecoder) {
        runCatching { it.isFormatSupported(format) }.getOrDefault(false)
    }

/** The platform video renderer with [hardwareFirstVideoDecoders] applied to its decoder list. */
internal class HardwareFirstVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> =
        hardwareFirstVideoDecoders(format, super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder))

    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format,
    ): Boolean {
        // releaseOutputBuffer can block this thread on a display present fence that never
        // signals (seen after a long pause on Realtek). The render loop's elapsedRealtimeUs
        // stays at the value from before that wait, so VideoFrameReleaseControl treats every
        // following frame in the same loop as late and drops it. Cap the credited wait.
        val elapsedForReleaseUs = elapsedRealtimeUsForFrameRelease(
            sampledElapsedRealtimeUs = elapsedRealtimeUs,
            nowElapsedRealtimeUs = SystemClock.elapsedRealtime() * 1000L,
        )
        return super.processOutputBuffer(
            positionUs,
            elapsedForReleaseUs,
            codec,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            bufferPresentationTimeUs,
            isDecodeOnlyBuffer,
            isLastBuffer,
            format,
        )
    }
}

/**
 * Wall time since [sampledElapsedRealtimeUs] that may be treated as playback advance.
 * One frame period: a blocked [android.media.MediaCodec.releaseOutputBuffer] must not
 * be subtracted in full from the next frame's earliness.
 */
internal fun elapsedRealtimeUsForFrameRelease(
    sampledElapsedRealtimeUs: Long,
    nowElapsedRealtimeUs: Long,
    maxCreditUs: Long = MAX_RENDER_LOOP_ELAPSED_CREDIT_US,
): Long {
    val stallUs = nowElapsedRealtimeUs - sampledElapsedRealtimeUs
    if (stallUs <= maxCreditUs) return sampledElapsedRealtimeUs
    return nowElapsedRealtimeUs - maxCreditUs
}

private const val MAX_RENDER_LOOP_ELAPSED_CREDIT_US = 30_000L

internal fun isSoftwareDecoder(info: MediaCodecInfo): Boolean =
    info.softwareOnly || hasSoftwareDecoderName(info.name)

internal fun hasSoftwareDecoderName(name: String): Boolean {
    val lower = name.lowercase()
    return SOFTWARE_DECODER_PREFIXES.any { lower.startsWith(it) }
}

/** Extension decoders (libdav1d, libgav1, ffmpeg) are always software. */
internal fun isSoftwareVideoDecoderName(name: String, softwareOnlyNames: Set<String>): Boolean {
    val lower = name.lowercase()
    return lower in softwareOnlyNames ||
        hasSoftwareDecoderName(lower) ||
        lower.startsWith("lib") ||
        lower.startsWith("ffmpeg")
}

internal val softwareOnlyDecoderNames: Set<String> by lazy {
    if (Build.VERSION.SDK_INT < 29) return@lazy emptySet()
    runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.isSoftwareOnly }
            .map { it.name.lowercase() }
            .toSet()
    }.getOrDefault(emptySet())
}
