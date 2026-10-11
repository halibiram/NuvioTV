package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.Renderer
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegVideoRendererOrderTest {

    private class FakeFfmpegVideoRenderer : Renderer by mockk<Renderer>(relaxed = true)
    private class FakeMediaCodecVideoRenderer : Renderer by mockk<Renderer>(relaxed = true)
    private class FakeLibgav1VideoRenderer : Renderer by mockk<Renderer>(relaxed = true)

    @Test
    fun detectsFfmpegVideoRendererByClassName() {
        assertTrue(isFfmpegVideoRenderer(FakeFfmpegVideoRenderer()))
        assertFalse(isFfmpegVideoRenderer(FakeMediaCodecVideoRenderer()))
        assertFalse(isFfmpegVideoRenderer(FakeLibgav1VideoRenderer()))
    }

    @Test
    fun preferModeOrder_putsMediaCodecAheadOfFfmpeg() {
        val ffmpeg = FakeFfmpegVideoRenderer()
        val av1 = FakeLibgav1VideoRenderer()
        val mediaCodec = FakeMediaCodecVideoRenderer()
        val renderers = mutableListOf<Renderer>(av1, ffmpeg, mediaCodec)

        moveFfmpegVideoRenderersLast(renderers)

        assertEquals(3, renderers.size)
        assertSame(av1, renderers[0])
        assertSame(mediaCodec, renderers[1])
        assertSame(ffmpeg, renderers[2])
    }

    @Test
    fun ffmpegAlreadyLast_keepsOrder() {
        val mediaCodec = FakeMediaCodecVideoRenderer()
        val ffmpeg = FakeFfmpegVideoRenderer()
        val renderers = mutableListOf<Renderer>(mediaCodec, ffmpeg)

        moveFfmpegVideoRenderersLast(renderers)

        assertEquals(listOf(mediaCodec, ffmpeg), renderers)
    }

    @Test
    fun noFfmpegRenderer_leavesListAlone() {
        val mediaCodec = FakeMediaCodecVideoRenderer()
        val av1 = FakeLibgav1VideoRenderer()
        val renderers = mutableListOf<Renderer>(av1, mediaCodec)

        moveFfmpegVideoRenderersLast(renderers)

        assertEquals(listOf(av1, mediaCodec), renderers)
    }
}
