package com.nuvio.tv.ui.screens.player

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Metadata
import androidx.media3.exoplayer.audio.AudioSink
import com.nuvio.tv.core.player.dvmkv.TrueHdAtmos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DebugAudioFormatTest {
    @Test
    fun `confirmed TrueHD Atmos appears on input and TrueHD passthrough only`() {
        val source = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
            .setMetadata(Metadata(TrueHdAtmos.INSTANCE))
            .setChannelCount(8).setSampleRate(48000).build()
        assertEquals("Dolby Atmos TrueHD (true-hd) 7.1", DebugAudioFormat.describeInput(source))
        val passthrough = AudioSink.AudioTrackConfig(C.ENCODING_DOLBY_TRUEHD, 48000,
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, false, true, 32768)
        assertEquals("TrueHD · 7.1 (8 channels) · 48 kHz · Atmos",
            DebugAudioFormat.describeOutput(passthrough, source))
        val converted = AudioSink.AudioTrackConfig(C.ENCODING_E_AC3, 48000,
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, false, true, 32768)
        assertEquals("DDP (E-AC-3) · 7.1 (8 channels) · 48 kHz",
            DebugAudioFormat.describeOutput(converted, source))
    }

    @Test
    fun `TrueHD source does not get mistaken for the DDP AudioTrack`() {
        val source = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
            .setChannelCount(8).setSampleRate(96000).build()
        val output = AudioSink.AudioTrackConfig(C.ENCODING_E_AC3, 48000,
            AudioFormat.CHANNEL_OUT_5POINT1, false, true, 32768)
        assertEquals("Dolby TrueHD (true-hd) 7.1", DebugAudioFormat.describeInput(source))
        assertEquals("DDP (E-AC-3) · 5.1 (6 channels) · 48 kHz", DebugAudioFormat.describeOutput(output))
    }

    @Test
    fun `PCM 7 point 1 is labelled PCM and eight channels`() {
        val output = AudioSink.AudioTrackConfig(C.ENCODING_PCM_16BIT, 48000,
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, false, false, 32768)
        assertEquals("PCM 16-bit · 7.1 (8 channels) · 48 kHz", DebugAudioFormat.describeOutput(output))
    }

    @Test
    fun `six channels without LFE are not labelled 5 point 1`() {
        assertEquals("PCM · 6 channels · 44.1 kHz", DebugAudioFormat.describe("PCM", 6, 44100, false))
    }

    @Test
    fun `unknown format remains unknown and EAC3 does not imply Atmos`() {
        assertEquals(null, DebugAudioFormat.describeInput(null))
        assertEquals(null, DebugAudioFormat.describeInput(Format.Builder().build()))
        val output = AudioSink.AudioTrackConfig(C.ENCODING_E_AC3, 48000,
            AudioFormat.CHANNEL_OUT_5POINT1, false, false, 32768)
        assertFalse(DebugAudioFormat.describeOutput(output).contains("Atmos"))
    }
    @Test
    fun `JOC confirms Atmos without inventing height channels`() {
        val source = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
            .setChannelCount(6).setSampleRate(48000).build()
        assertEquals("Dolby Atmos DD+ (eac3-joc) 5.1", DebugAudioFormat.describeInput(source))
    }

    @Test
    fun `TrueHD codec and Atmos filename do not prove Atmos`() {
        val source = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
            .setLabel("TrueHD Atmos 7.1.4").setChannelCount(8).setSampleRate(48000).build()
        assertEquals("Dolby TrueHD (true-hd) 7.1", DebugAudioFormat.describeInput(source))
    }

    @Test
    fun `JOC passthrough shows source bed even when AudioTrack opens with eight channels`() {
        val source = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
            .setChannelCount(6).setSampleRate(48000).build()
        val output = AudioSink.AudioTrackConfig(C.ENCODING_E_AC3_JOC, 48000,
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, false, true, 32768)
        assertEquals("DDP (E-AC-3) · 5.1 (6 channels) · 48 kHz · Atmos",
            DebugAudioFormat.describeOutput(output, source))
        assertEquals("DDP (E-AC-3) · ? ch · 48 kHz · Atmos", DebugAudioFormat.describeOutput(output))
    }

    @Test
    fun `plain EAC3 output keeps AudioTrack layout and omits source Atmos`() {
        val source = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
            .setChannelCount(6).setSampleRate(48000).build()
        val output = AudioSink.AudioTrackConfig(C.ENCODING_E_AC3, 48000,
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, false, true, 32768)
        assertEquals("DDP (E-AC-3) · 7.1 (8 channels) · 48 kHz",
            DebugAudioFormat.describeOutput(output, source))
    }
}
