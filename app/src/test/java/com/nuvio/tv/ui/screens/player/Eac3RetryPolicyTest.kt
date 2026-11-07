package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.decoder.ffmpeg.FfmpegDecoderException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class Eac3RetryPolicyTest {
    private val ffmpegName = "FfmpegAudioRenderer"

    @Test
    fun failedFfmpegOpenReportsTheTranscodedLayout() {
        for (channels in listOf(8, 6)) {
            val output = outputFormat(channels)
            assertEquals(output, failedTranscodedEac3Output(rendererError(output), ffmpegName))
        }
    }

    @Test
    fun configurationRefusalAlsoReportsTheTranscodedLayout() {
        val output = outputFormat(8)
        val error = rendererError(output, cause = AudioSink.ConfigurationException("unsupported", output))
        assertEquals(output, failedTranscodedEac3Output(error, ffmpegName))
    }

    @Test
    fun nativePassthroughFailureDoesNotEnterTheTranscodeRetry() {
        val error = rendererError(outputFormat(8), rendererName = "MediaCodecAudioRenderer")
        assertNull(failedTranscodedEac3Output(error, ffmpegName))
        assertNull(failedTranscodedEac3Output(error, null))
    }

    @Test
    fun pcmOpenAndEac3WriteFailuresDoNotEnterTheTranscodeRetry() {
        val pcm = outputFormat(8).buildUpon().setSampleMimeType(MimeTypes.AUDIO_RAW).build()
        assertNull(failedTranscodedEac3Output(rendererError(pcm), ffmpegName))
        val output = outputFormat(8)
        val writeError = rendererError(
            output,
            errorCode = PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            cause = AudioSink.WriteException(-1, output, false)
        )
        assertNull(failedTranscodedEac3Output(writeError, ffmpegName))
    }

    @Test
    fun oneRetryCapsOnlyTheFailedStreamAndRoute() {
        val policy = Eac3RetryPolicy()
        assertFalse(policy.recordOpenFailure(6, "movie", "hdmi"))
        assertFalse(policy.shouldLimitToFiveOne("movie", "hdmi"))
        assertTrue(policy.recordOpenFailure(8, "movie", "hdmi"))
        assertTrue(policy.shouldLimitToFiveOne("movie", "hdmi"))
        assertFalse(policy.recordOpenFailure(8, "movie", "hdmi"))
        assertFalse(policy.shouldLimitToFiveOne("episode", "hdmi"))
        assertFalse(policy.shouldLimitToFiveOne("movie", "optical"))
        assertTrue(policy.recordOpenFailure(8, "movie", "optical"))
        assertTrue(policy.shouldLimitToFiveOne("movie", "optical"))
    }

    @Test
    fun decoderLayoutMismatchAndOpenFailureShareOneRetry() {
        val policy = Eac3RetryPolicy()
        assertTrue(policy.recordFiveOneRetry("movie", "hdmi"))
        assertTrue(policy.shouldLimitToFiveOne("movie", "hdmi"))
        assertFalse(policy.recordOpenFailure(8, "movie", "hdmi"))
        assertFalse(policy.recordFiveOneRetry("movie", "hdmi"))
    }

    @Test
    fun onlyTheFfmpegLayoutMismatchIsRecognizedAsRecoverableHere() {
        val factory = runCatching {
            FfmpegDecoderException::class.java.getDeclaredMethod("eac3LayoutMismatch")
        }.getOrNull()
        assumeNotNull(factory) // Stock decoder AAR has no E-AC-3 encoder.
        val cause = factory!!.apply { isAccessible = true }.invoke(null) as Throwable
        val decoderFormat = outputFormat(8).buildUpon().setSampleMimeType(MimeTypes.AUDIO_TRUEHD).build()
        assertTrue(
            Eac3TranscodingSupport.isLayoutMismatch(
                rendererError(
                    decoderFormat,
                    errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                    cause = RuntimeException(cause)
                )
            )
        )
        assertFalse(Eac3TranscodingSupport.isLayoutMismatch(rendererError(decoderFormat, cause = cause)))
        val otherFfmpegError = FfmpegDecoderException::class.java.getDeclaredConstructor(String::class.java)
            .apply { isAccessible = true }
            .newInstance("Unrelated FFmpeg failure")
        assertFalse(
            Eac3TranscodingSupport.isLayoutMismatch(
                rendererError(
                    decoderFormat,
                    errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                    cause = otherFfmpegError
                )
            )
        )
    }

    private fun outputFormat(channels: Int): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
        .setChannelCount(channels)
        .setSampleRate(48_000)
        .build()

    private fun rendererError(
        output: Format,
        rendererName: String = ffmpegName,
        errorCode: Int = PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        cause: Throwable = AudioSink.InitializationException("refused", 0, output, false, null)
    ): ExoPlaybackException = ExoPlaybackException.createForRenderer(
        cause, rendererName, 0, output, C.FORMAT_HANDLED, null, false, errorCode
    )
}
