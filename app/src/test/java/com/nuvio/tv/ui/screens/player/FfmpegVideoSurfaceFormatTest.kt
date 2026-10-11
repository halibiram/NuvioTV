package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegVideoSurfaceFormatTest {

    private data class MappedRenderer(val name: String, val type: Int, val groups: TrackGroupArray)

    private val vc1Group = TrackGroup("vc1", Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VC1).build())
    private val hevcGroup = TrackGroup("hevc", Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build())
    private val audioGroup = TrackGroup("audio", Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).build())

    private fun mappedTrackInfo(vararg renderers: MappedRenderer): MappingTrackSelector.MappedTrackInfo {
        val info = mockk<MappingTrackSelector.MappedTrackInfo>()
        every { info.rendererCount } returns renderers.size
        renderers.forEachIndexed { index, renderer ->
            every { info.getRendererName(index) } returns renderer.name
            every { info.getRendererType(index) } returns renderer.type
            every { info.getTrackGroups(index) } returns renderer.groups
        }
        return info
    }

    private fun tracks(vararg groups: Pair<TrackGroup, Boolean>): Tracks = Tracks(
        groups.map { (group, selected) ->
            Tracks.Group(group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
        }
    )

    private fun mapping(mediaCodecVideo: TrackGroupArray, ffmpegVideo: TrackGroupArray) = mappedTrackInfo(
        MappedRenderer("MediaCodecVideoRenderer", C.TRACK_TYPE_VIDEO, mediaCodecVideo),
        MappedRenderer("MediaCodecAudioRenderer", C.TRACK_TYPE_AUDIO, TrackGroupArray(audioGroup)),
        MappedRenderer("FfmpegVideoRenderer", C.TRACK_TYPE_VIDEO, ffmpegVideo)
    )

    @Test
    fun vc1OnFfmpegRenderer_isFfmpeg() {
        val info = mapping(mediaCodecVideo = TrackGroupArray.EMPTY, ffmpegVideo = TrackGroupArray(vc1Group))

        assertTrue(info.isFfmpegVideoSelected(tracks(vc1Group to true, audioGroup to true)))
    }

    @Test
    fun hevcOnMediaCodecRenderer_isNotFfmpeg() {
        val info = mapping(mediaCodecVideo = TrackGroupArray(hevcGroup), ffmpegVideo = TrackGroupArray.EMPTY)

        assertFalse(info.isFfmpegVideoSelected(tracks(hevcGroup to true, audioGroup to true)))
    }

    @Test
    fun unselectedFfmpegTrack_isNotFfmpeg() {
        val info = mapping(mediaCodecVideo = TrackGroupArray(hevcGroup), ffmpegVideo = TrackGroupArray(vc1Group))

        assertFalse(info.isFfmpegVideoSelected(tracks(hevcGroup to true, vc1Group to false, audioGroup to true)))
    }

    @Test
    fun noVideoSelected_isNotFfmpeg() {
        val info = mapping(mediaCodecVideo = TrackGroupArray.EMPTY, ffmpegVideo = TrackGroupArray(vc1Group))

        assertFalse(info.isFfmpegVideoSelected(tracks(vc1Group to false, audioGroup to true)))
    }
}
