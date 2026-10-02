package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Vc1DecoderSelectorTest {

    private fun decoder(name: String, mimeType: String): MediaCodecInfo =
        MediaCodecInfo.newInstance(name, mimeType, mimeType, null, true, false, true, false, false)

    private fun deviceWith(vararg decoders: MediaCodecInfo): MediaCodecSelector =
        MediaCodecSelector { mimeType, _, _ -> decoders.filter { it.mimeType == mimeType } }

    private fun MediaCodecSelector.namesFor(mimeType: String): List<String> =
        getDecoderInfos(mimeType, false, false).map { it.name }

    @Test
    fun vc1DecoderListedAsWvc1_isUsed() {
        val device = deviceWith(decoder("OMX.vendor.vc1.decoder", MimeTypes.VIDEO_VC1))

        val selector = Vc1DecoderSelector.wrap(device, softwareRendererAvailable = true)

        assertEquals(listOf("OMX.vendor.vc1.decoder"), selector.namesFor(MimeTypes.VIDEO_VC1))
    }

    @Test
    fun vc1DecoderListedOnlyAsVideoVc1_isFoundForAWvc1Track() {
        val device = deviceWith(decoder("c2.amlogic.vc1.decoder", "video/vc1"))

        val selector = Vc1DecoderSelector.wrap(device, softwareRendererAvailable = true)

        val found = selector.getDecoderInfos(MimeTypes.VIDEO_VC1, false, false)
        assertEquals(listOf("c2.amlogic.vc1.decoder"), found.map { it.name })
        assertEquals("video/vc1", found.single().codecMimeType)
    }

    @Test
    fun vc1WithNoDecoderOnTheDevice_isLeftToTheSoftwareRenderer() {
        val device = deviceWith(decoder("c2.android.avc.decoder", MimeTypes.VIDEO_H264))

        val selector = Vc1DecoderSelector.wrap(device, softwareRendererAvailable = true)

        assertTrue(selector.namesFor(MimeTypes.VIDEO_VC1).isEmpty())
        assertTrue(selector.namesFor("video/vc1").isEmpty())
    }

    @Test
    fun wmv_staysWithTheSoftwareRendererWhileItIsAvailable() {
        val device = deviceWith(
            decoder("c2.vendor.wmv3.decoder", "video/x-ms-wmv"),
            decoder("c2.vendor.wmv1.decoder", "video/x-ms-wmv1")
        )

        val selector = Vc1DecoderSelector.wrap(device, softwareRendererAvailable = true)

        assertTrue(selector.namesFor("video/x-ms-wmv").isEmpty())
        assertTrue(selector.namesFor("video/x-ms-wmv1").isEmpty())
    }

    @Test
    fun deviceOnly_hidesNothing() {
        val device = deviceWith(
            decoder("c2.amlogic.vc1.decoder", "video/vc1"),
            decoder("c2.vendor.wmv3.decoder", "video/x-ms-wmv")
        )

        val selector = Vc1DecoderSelector.wrap(device, softwareRendererAvailable = false)

        assertEquals(listOf("c2.amlogic.vc1.decoder"), selector.namesFor(MimeTypes.VIDEO_VC1))
        assertEquals(listOf("c2.vendor.wmv3.decoder"), selector.namesFor("video/x-ms-wmv"))
    }

    @Test
    fun forcedSoftware_hidesTheDeviceVc1Decoder() {
        val device = deviceWith(decoder("c2.amlogic.vc1.decoder", "video/vc1"))

        val selector = Vc1DecoderSelector.wrap(device, softwareRendererAvailable = true, forceSoftware = true)

        assertTrue(selector.namesFor(MimeTypes.VIDEO_VC1).isEmpty())
        assertTrue(selector.namesFor("video/vc1").isEmpty())
    }

    @Test
    fun forcedSoftware_withoutTheSoftwareRenderer_keepsTheDeviceDecoder() {
        val device = deviceWith(decoder("c2.amlogic.vc1.decoder", "video/vc1"))

        val selector = Vc1DecoderSelector.wrap(device, softwareRendererAvailable = false, forceSoftware = true)

        assertEquals(listOf("c2.amlogic.vc1.decoder"), selector.namesFor(MimeTypes.VIDEO_VC1))
    }

    @Test
    fun otherFormats_goStraightToTheBaseSelector() {
        val device = deviceWith(
            decoder("c2.android.avc.decoder", MimeTypes.VIDEO_H264),
            decoder("c2.amlogic.vc1.decoder", "video/vc1")
        )

        val selector = Vc1DecoderSelector.wrap(device, softwareRendererAvailable = true)

        assertEquals(listOf("c2.android.avc.decoder"), selector.namesFor(MimeTypes.VIDEO_H264))
        assertTrue(selector.namesFor(MimeTypes.VIDEO_H265).isEmpty())
    }

    @Test
    fun listedVc1DecoderRatedExceedsCapabilities_countsAsHandled() {
        val exceeds = RendererCapabilities.create(
            C.FORMAT_EXCEEDS_CAPABILITIES,
            RendererCapabilities.ADAPTIVE_NOT_SEAMLESS,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
            RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED,
            RendererCapabilities.DECODER_SUPPORT_PRIMARY
        )

        val support = Vc1DecoderSelector.hardwareFirstSupport(MimeTypes.VIDEO_VC1, exceeds)

        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(support))
        assertEquals(RendererCapabilities.ADAPTIVE_NOT_SEAMLESS, RendererCapabilities.getAdaptiveSupport(support))
        assertEquals(RendererCapabilities.TUNNELING_NOT_SUPPORTED, RendererCapabilities.getTunnelingSupport(support))
        assertEquals(
            RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED,
            RendererCapabilities.getHardwareAccelerationSupport(support)
        )
        assertEquals(RendererCapabilities.DECODER_SUPPORT_PRIMARY, RendererCapabilities.getDecoderSupport(support))
    }

    @Test
    fun supportIsLeftAlone_withoutADecoderAndForOtherFormats() {
        val noDecoder = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        val exceeds = RendererCapabilities.create(C.FORMAT_EXCEEDS_CAPABILITIES)
        val handled = RendererCapabilities.create(C.FORMAT_HANDLED)

        assertEquals(noDecoder, Vc1DecoderSelector.hardwareFirstSupport(MimeTypes.VIDEO_VC1, noDecoder))
        assertEquals(handled, Vc1DecoderSelector.hardwareFirstSupport(MimeTypes.VIDEO_VC1, handled))
        assertEquals(exceeds, Vc1DecoderSelector.hardwareFirstSupport(MimeTypes.VIDEO_H265, exceeds))
        assertEquals(exceeds, Vc1DecoderSelector.hardwareFirstSupport("video/x-ms-wmv", exceeds))
        assertEquals(exceeds, Vc1DecoderSelector.hardwareFirstSupport(null, exceeds))
    }
}
