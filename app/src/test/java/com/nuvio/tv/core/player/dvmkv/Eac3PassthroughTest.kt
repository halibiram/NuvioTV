package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.ui.screens.player.PlaybackSpeedAwareAudioSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Eac3PassthroughTest {
    @Test
    fun sixChannelTransportKeepsTheOriginalSevenOneJocStream() {
        val sample = jocAccessUnit()
        val source = Eac3Surround.refineFormat(ddpFormat(), sample, 0, sample.size)
        val delegate = RecordingSink(6)
        val sink = PlaybackSpeedAwareAudioSink(delegate.audioSink)

        assertTrue(sink.supportsFormat(source))
        assertEquals(listOf(8, 6), delegate.probes)
        sink.configure(source, 0, null)
        assertSame(source, sink.activeInputFormat)
        assertEquals(8, sink.activeInputFormat?.channelCount)
        assertEquals(MimeTypes.AUDIO_E_AC3_JOC, delegate.configured?.sampleMimeType)
        assertEquals(6, delegate.configured?.channelCount)
        assertTrue(sink.handleBuffer(ByteBuffer.wrap(sample), 0, 1))
        assertArrayEquals(sample, delegate.written)
    }

    @Test
    fun eightChannelTransportRemainsPreferred() {
        val sample = jocAccessUnit()
        val source = Eac3Surround.refineFormat(ddpFormat(), sample, 0, sample.size)
        val delegate = RecordingSink(8)
        val sink = PlaybackSpeedAwareAudioSink(delegate.audioSink)

        assertTrue(sink.supportsFormat(source))
        assertEquals(listOf(8), delegate.probes)
        sink.configure(source, 0, null)
        assertSame(source, delegate.configured)
    }

    @Test
    fun unverifiedSevenOneAndDeniedPassthroughDoNotUseTheSixChannelProfile() {
        val delegate = RecordingSink(6)
        val sink = PlaybackSpeedAwareAudioSink(delegate.audioSink)
        assertFalse(sink.supportsFormat(ddpFormat().buildUpon().setChannelCount(8).build()))
        assertEquals(listOf(8), delegate.probes)

        val sample = jocAccessUnit()
        val source = Eac3Surround.refineFormat(ddpFormat(), sample, 0, sample.size)
        sink.setPassthroughPolicy(AudioPassthroughPolicy(allowEac3 = false))
        assertFalse(sink.supportsFormat(source))
        assertEquals(listOf(8), delegate.probes)
    }

    private fun ddpFormat(): Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3)
        .setChannelCount(6).setSampleRate(48_000).build()

    private fun jocAccessUnit(): ByteArray = ByteArray(5120).also { data ->
        // The base and dependent headers reported on PR #47.
        "0b778a522430e1fc10ec92500003f05955e1861861ff".chunked(2).forEachIndexed { i, byte ->
            data[i] = byte.toInt(16).toByte()
        }
        "0b7744ff3a87e091a0020808800d00000012220cc30c30ff".chunked(2).forEachIndexed { i, byte ->
            data[2560 + i] = byte.toInt(16).toByte()
        }
    }

    private class RecordingSink(private val maxChannels: Int) {
        val probes = mutableListOf<Int>()
        var configured: Format? = null
        var written: ByteArray? = null

        val audioSink = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getFormatSupport" -> {
                    val channels = (args[0] as Format).channelCount
                    probes += channels
                    if (channels <= maxChannels) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                    else AudioSink.SINK_FORMAT_UNSUPPORTED
                }
                "supportsFormat" -> (args[0] as Format).channelCount <= maxChannels
                "configure" -> { configured = args[0] as Format; null }
                "handleBuffer" -> {
                    val buffer = args[0] as ByteBuffer
                    written = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    true
                }
                else -> error("Unexpected call to ${method.name}")
            }
        } as AudioSink
    }
}
