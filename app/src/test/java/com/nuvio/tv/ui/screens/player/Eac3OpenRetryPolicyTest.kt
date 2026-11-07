package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Eac3OpenRetryPolicyTest {
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
        val policy = Eac3OpenRetryPolicy()
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
