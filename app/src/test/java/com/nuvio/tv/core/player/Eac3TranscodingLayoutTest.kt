package com.nuvio.tv.core.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import java.lang.reflect.Method
import java.util.function.Predicate
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class Eac3TranscodingLayoutTest {
    private lateinit var eligibility: Method
    private lateinit var layout: Method

    @Before fun requiresRebuiltEac3Decoder() {
        val methods = runCatching {
            val renderer = FfmpegAudioRenderer::class.java
            renderer.getMethod("canEncodeEac3", Int::class.javaPrimitiveType) to
                renderer.getMethod("eac3OutputChannels", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        }.getOrNull()
        assumeTrue("The stock prebuilt decoder has no E-AC-3 encoder controls", methods != null)
        eligibility = methods!!.first
        layout = methods.second
    }

    @Test fun onlySupportedMultichannelLayoutsEnterTheEncoder() {
        for (channels in listOf(-1, 0, 1, 2, 3, 4, 5, 7, 10)) {
            assertEquals("Unsupported channels: $channels", false, eligibility.invoke(null, channels))
        }
        assertEquals(true, eligibility.invoke(null, 6))
        assertEquals(true, eligibility.invoke(null, 8))
    }
    @Test fun fiveOneAndSevenOneArePreservedUnlessExplicitlyLimited() {
        assertEquals(6, layout.invoke(null, 6, false))
        assertEquals(8, layout.invoke(null, 8, false))
        assertEquals(6, layout.invoke(null, 8, true))
        assertEquals(6, layout.invoke(null, 6, true))
    }

    @Test fun sevenOneIsPreferredWhenAccepted() {
        assertOutput(inputChannels = 8, acceptedChannels = setOf(6, 8), expectedProbes = listOf(8), expectedOutput = 8)
    }

    @Test fun sevenOneFallsBackToFiveOneWhenOnlySixChannelsAreAccepted() {
        assertOutput(inputChannels = 8, acceptedChannels = setOf(6), expectedProbes = listOf(8, 6), expectedOutput = 6)
    }

    @Test fun noEac3OutputWhenBothLayoutsAreRejected() {
        assertOutput(inputChannels = 8, acceptedChannels = emptySet(), expectedProbes = listOf(8, 6), expectedOutput = null)
    }

    @Test fun fiveOneInputNeverTriesSevenOne() {
        assertOutput(inputChannels = 6, acceptedChannels = setOf(8), expectedProbes = listOf(6), expectedOutput = null)
    }

    @Test fun explicitFiveOneLimitSkipsSevenOne() {
        assertOutput(
            inputChannels = 8,
            forceFiveOne = true,
            acceptedChannels = setOf(6, 8),
            expectedProbes = listOf(6),
            expectedOutput = 6
        )
    }

    private fun assertOutput(
        inputChannels: Int,
        forceFiveOne: Boolean = false,
        acceptedChannels: Set<Int>,
        expectedProbes: List<Int>,
        expectedOutput: Int?
    ) {
        val selector = FfmpegAudioRenderer::class.java.getDeclaredMethod(
            "selectEac3Output",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Predicate::class.java
        ).apply { isAccessible = true }
        val probes = mutableListOf<Int>()
        val supportsOutput = Predicate<Format> { candidate ->
            assertEquals(MimeTypes.AUDIO_E_AC3, candidate.sampleMimeType)
            assertEquals(48000, candidate.sampleRate)
            probes += candidate.channelCount
            candidate.channelCount in acceptedChannels
        }
        val output = selector.invoke(null, inputChannels, forceFiveOne, supportsOutput) as Format?
        assertEquals(expectedProbes, probes)
        assertEquals(expectedOutput, output?.channelCount)
    }
}
