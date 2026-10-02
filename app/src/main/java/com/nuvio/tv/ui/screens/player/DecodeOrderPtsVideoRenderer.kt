package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.nuvio.tv.core.player.DecodeOrderTimestamps
import java.nio.ByteBuffer

/**
 * MediaCodec video renderer that repairs presentation times for VC-1 and WMV tracks.
 *
 * Matroska (VFW mode) and ASF carry decode-order times for them, so a hardware decoder hands back
 * display-order pictures with times that step backwards and roughly one frame in three is dropped as late.
 * See [DecodeOrderTimestamps]. Every other format passes through untouched.
 *
 * Buffer state advances only when super consumes the buffer: a held (early) buffer is offered again on the
 * next render pass.
 */
internal class DecodeOrderPtsVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder
) : MediaCodecVideoRenderer(builder) {

    private var inputFormat: Format? = null
    private var timestamps: DecodeOrderTimestamps? = null
    private var consumed = 0L
    private var loggedEngage = false

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val evaluation = super.onInputFormatChanged(formatHolder)
        val format = formatHolder.format
        if (format != null && !sameTrackKind(format, inputFormat)) {
            timestamps = if (Vc1VideoFormatHeuristics.isVc1OrWmvMime(format.sampleMimeType)) {
                DecodeOrderTimestamps(DecodeOrderTimestamps.VC1_MAX_REORDER_FRAMES)
            } else {
                null
            }
            consumed = 0L
            loggedEngage = false
        }
        inputFormat = format
        return evaluation
    }

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        super.onQueueInputBuffer(buffer)
        timestamps?.onInputQueued(buffer.timeUs)
    }

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
        format: Format
    ): Boolean {
        val repair = timestamps
        val presentationTimeUs = repair?.outputTimeUs(bufferPresentationTimeUs) ?: bufferPresentationTimeUs
        // The caller judged "before the seek position" on the decode-order label.
        val decodeOnly = if (repair != null && repair.engaged) {
            presentationTimeUs < lastResetPositionUs
        } else {
            isDecodeOnlyBuffer
        }
        val wasConsumed = super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            presentationTimeUs,
            decodeOnly,
            isLastBuffer,
            format
        )
        if (wasConsumed && repair != null) {
            repair.onOutputConsumed(bufferPresentationTimeUs)
            logProgress(repair, format)
        }
        return wasConsumed
    }

    // Every codec flush and release: seeks, and the drop-to-keyframe path when playback falls far behind.
    override fun resetCodecStateForFlush() {
        timestamps?.flush()
        super.resetCodecStateForFlush()
    }

    private fun logProgress(repair: DecodeOrderTimestamps, format: Format) {
        consumed++
        if (repair.engaged && !loggedEngage) {
            loggedEngage = true
            Log.i(
                TAG,
                "engaged: decode-order times detected, mime=${format.sampleMimeType} " +
                    "container=${format.containerMimeType} frame=$consumed"
            )
        }
        if (repair.engaged && consumed % LOG_EVERY == 0L) {
            Log.i(TAG, "processed=$consumed repaired=${repair.repaired} discarded=${repair.discarded}")
        }
    }

    private fun sameTrackKind(a: Format, b: Format?): Boolean =
        b != null && a.sampleMimeType == b.sampleMimeType && a.containerMimeType == b.containerMimeType

    private companion object {
        const val TAG = "PTS_REPAIR"
        const val LOG_EVERY = 500L
    }
}
