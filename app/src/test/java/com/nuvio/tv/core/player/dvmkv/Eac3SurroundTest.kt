package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class Eac3SurroundTest {
    @Test
    fun recognizesJocInMatroskaSamplesWithoutAnExtensionFrame() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setChannelCount(6)
            .setSampleRate(48000)
            .setLanguage("pt")
            .setLabel("Português")
            .build()
        val samples = listOf(
            // E-AC-3 JOC headers from the reported MKV and the MP4 control.
            accessUnit(3072, "0b7705ff3f86fffa49008c20808800d00000000800000012020", 0, ""),
            accessUnit(1792, "0b77037f3f87e03e49008e20808800d400000000400000008210", 0, "")
        )
        samples.forEach { sample ->
            val result = Eac3Surround.refineFormat(format, sample, 0, sample.size)
            assertEquals(MimeTypes.AUDIO_E_AC3_JOC, result.sampleMimeType)
            assertEquals(6, result.channelCount)
            assertEquals(48000, result.sampleRate)
            assertEquals("pt", result.language)
            assertEquals("Português", result.label)
            val padded = ByteArray(13) + sample
            assertEquals(MimeTypes.AUDIO_E_AC3_JOC,
                Eac3Surround.refineFormat(format, padded, 13, padded.size).sampleMimeType)
            assertSame(format, Eac3Surround.refineFormat(format, sample, 0, sample.size - 1))
        }
    }

    @Test
    fun recognizesJocInTheDependentFrameOfASevenOneTrack() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setChannelCount(6).setSampleRate(48000).setLanguage("en").setLabel("Surround").build()
        // Headers reported on PR #47. Pad each syncframe to its declared size for header parsing.
        val sample = accessUnit(
            2560, "0b778a522430e1fc10ec92500003f05955e1861861ff",
            2560, "0b7744ff3a87e091a0020808800d00000012220cc30c30ff"
        )
        val prefixed = ByteArray(13) + sample + ByteArray(9)
        val result = Eac3Surround.refineFormat(format, prefixed, 13, 13 + sample.size)
        assertEquals(MimeTypes.AUDIO_E_AC3_JOC, result.sampleMimeType)
        assertEquals(8, result.channelCount)
        assertEquals(48000, result.sampleRate)
        assertEquals("en", result.language)
        assertEquals("Surround", result.label)
        assertSame(format, Eac3Surround.refineFormat(format, prefixed, 13, 13 + sample.size - 1))
        assertSame(format, Eac3Surround.refineFormat(format, sample, 2560, sample.size))
    }

    @Test
    fun keepsOrdinaryDdpAndRejectsMissingOrMalformedAudioHeaders() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setChannelCount(6).build()
        val plain = accessUnit(4096, "0b7707ff3f87c00000842108", 0, "")
        assertSame(format, Eac3Surround.refineFormat(format, plain, 0, plain.size))
        assertSame(format, Eac3Surround.refineFormat(format, ByteArray(20), 0, 20))
        assertSame(format, Eac3Surround.refineFormat(format, byteArrayOf(0x0b, 0x77), 0, 2))
    }

    @Test
    fun detectsSurroundExtensionAfterAc3OrEac3Base() {
        val hybrid = accessUnit(2560, "0b77486b2430e1fffdcdd924", 4096, "0b7747ff3a87fff1a0008200")
        val eac3 = accessUnit(1720, "0b77035b3f67ff810420568000020d00", 1352, "0b7742a33a67e011a000021000000021")
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setChannelCount(6).build()
        assertSame(format, Eac3Surround.refineFormat(format, eac3, 0, 1720))
        assertSame(format, Eac3Surround.refineFormat(format, eac3, 0, eac3.size - 1))
        for (sample in listOf(hybrid, eac3)) {
            val refined = Eac3Surround.refineFormat(format, sample, 0, sample.size)
            assertEquals(MimeTypes.AUDIO_E_AC3, refined.sampleMimeType)
            assertEquals(8, refined.channelCount)
        }
    }

    private fun accessUnit(baseSize: Int, baseHeader: String, extensionSize: Int, extensionHeader: String) =
        ByteArray(baseSize + extensionSize).also { data ->
            baseHeader.chunked(2).forEachIndexed { index, value ->
                data[index] = value.toInt(16).toByte()
            }
            extensionHeader.chunked(2).forEachIndexed { index, value ->
                data[baseSize + index] = value.toInt(16).toByte()
            }
        }
}
