package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import com.nuvio.tv.ui.screens.player.iec.IecPassthroughAudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassthroughWaterLevelPacerTest {

    @Test
    fun mime_matchesEveryPassthroughFormat() {
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_AC3))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_E_AC3))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_E_AC3_JOC))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_AC4))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_DTS))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_DTS_HD))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_DTS_EXPRESS))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_DTS_X))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime("audio/vnd.dts.hd;profile=lbr"))
        assertTrue(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_TRUEHD))
        assertFalse(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_RAW))
        assertFalse(PassthroughWaterLevelPacer.isPassthroughMime(MimeTypes.AUDIO_AAC))
        assertFalse(PassthroughWaterLevelPacer.isPassthroughMime(null))
    }

    @Test
    fun clamp_ignoresCurrentPositionNotSet_andAnchorsOnTheFirstRealPosition() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        pacer.onPlay(nowMs = 1_000L)
        assertEquals(
            AudioSink.CURRENT_POSITION_NOT_SET,
            pacer.clampPositionUs(AudioSink.CURRENT_POSITION_NOT_SET, nowMs = 1_000L, playbackSpeed = 1f)
        )
        assertEquals(C.TIME_UNSET, pacer.clampPositionUs(C.TIME_UNSET, nowMs = 1_000L, playbackSpeed = 1f))
        assertEquals(5_000_000L, pacer.clampPositionUs(5_000_000L, nowMs = 1_000L, playbackSpeed = 1f))
        assertEquals(5_010_000L, pacer.clampPositionUs(5_010_000L, nowMs = 1_010L, playbackSpeed = 1f))
    }

    @Test
    fun dtsAndAc3_useTwoHundredMsWaterLevel() {
        val dts = PassthroughWaterLevelPacer()
        dts.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        assertEquals(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US, dts.writeAheadCeilingUs())

        val ac3 = PassthroughWaterLevelPacer()
        ac3.onFormat(mime(MimeTypes.AUDIO_AC3))
        assertEquals(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US, ac3.writeAheadCeilingUs())
    }

    @Test
    fun trueHd_usesLooserCapWithoutIecPacker() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        assertEquals(PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US, pacer.writeAheadCeilingUs())
    }

    @Test
    fun iecPacked_matchesTheOneSecondTrackBuffer() {
        assertEquals(
            IecPassthroughAudioSink.IEC_BUFFER_TARGET_MS * 1_000L,
            PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US
        )
    }

    @Test
    fun iecPacked_trueHdAndDtsHd_useTheOneSecondCeiling() {
        val trueHd = PassthroughWaterLevelPacer()
        trueHd.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        trueHd.setIecPacked(true)
        assertEquals(PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US, trueHd.writeAheadCeilingUs())

        val dtsHd = PassthroughWaterLevelPacer()
        dtsHd.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        dtsHd.setIecPacked(true)
        assertEquals(PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US, dtsHd.writeAheadCeilingUs())
    }

    @Test
    fun iecPacked_prestart_fillsOneSecondThenRejects() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        pacer.setIecPacked(true)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        assertTrue(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US, 0L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US + 1L, 0L, 1f))
    }

    @Test
    fun iecPacked_playing_keepsOneSecondAheadOfWallClock() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        pacer.setIecPacked(true)
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        assertTrue(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US, 0L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US + 1L, 0L, 1f))
        assertTrue(
            pacer.shouldAcceptBuffer(
                500_000L + PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US,
                500L,
                1f
            )
        )
        assertFalse(
            pacer.shouldAcceptBuffer(
                500_000L + PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US + 1L,
                500L,
                1f
            )
        )
    }

    @Test
    fun iecPacked_pause_doesNotGrowTheOneSecondWindow() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        pacer.setIecPacked(true)
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onPause(400L)
        val pausedLimit = 400_000L + PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US
        assertTrue(pacer.shouldAcceptBuffer(pausedLimit, 20_000L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(pausedLimit + 1L, 20_000L, 1f))
    }

    @Test
    fun leavingIec_restoresTheRawCeiling() {
        val trueHd = PassthroughWaterLevelPacer()
        trueHd.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        trueHd.setIecPacked(true)
        assertEquals(PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US, trueHd.writeAheadCeilingUs())
        trueHd.setIecPacked(false)
        assertEquals(PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US, trueHd.writeAheadCeilingUs())

        val dts = PassthroughWaterLevelPacer()
        dts.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        dts.setIecPacked(true)
        dts.setIecPacked(false)
        assertEquals(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US, dts.writeAheadCeilingUs())
    }

    @Test
    fun iecPacked_reportedPositionStillCannotRunAheadOfWrittenPts() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        pacer.setIecPacked(true)
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onBufferAccepted(0L)
        assertTrue(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US, 0L, 1f))
        pacer.onBufferAccepted(PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US)
        assertEquals(0L, pacer.clampPositionUs(0L, 0L, 1f))
        assertEquals(
            PassthroughWaterLevelPacer.POSITION_LEAD_SLACK_US,
            pacer.clampPositionUs(5_000_000L, 0L, 1f)
        )
    }

    @Test
    fun prestart_allowsWaterThenRejectsSprint() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        assertTrue(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US, 0L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US + 1L, 0L, 1f))
    }

    @Test
    fun playing_acceptsRealtimeAndRejectsByteSprint() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_E_AC3))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        assertTrue(pacer.shouldAcceptBuffer(1_000_000L, 1_000L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(5_000_000L, 1_000L, 1f))
    }

    @Test
    fun trueHd_allowsEightHundredMsWhereDtsWouldReject() {
        val trueHd = PassthroughWaterLevelPacer()
        trueHd.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        assertTrue(trueHd.shouldAcceptBuffer(0L, 0L, 1f))
        assertTrue(trueHd.shouldAcceptBuffer(PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US, 0L, 1f))
        assertFalse(trueHd.shouldAcceptBuffer(PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US + 1L, 0L, 1f))

        val dts = PassthroughWaterLevelPacer()
        dts.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        assertTrue(dts.shouldAcceptBuffer(0L, 0L, 1f))
        assertFalse(dts.shouldAcceptBuffer(PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US, 0L, 1f))
    }

    @Test
    fun position_capsAtWrittenPtsAndWall() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onBufferAccepted(0L)
        assertEquals(0L, pacer.clampPositionUs(0L, 0L, 1f))
        assertTrue(pacer.shouldAcceptBuffer(100_000L, 100L, 1f))
        pacer.onBufferAccepted(100_000L)
        val clamped = pacer.clampPositionUs(2_000_000L, 100L, 1f)
        assertEquals(100_000L + PassthroughWaterLevelPacer.POSITION_LEAD_SLACK_US, clamped)
    }

    @Test
    fun position_clampsWallLeadAndPreservesLag() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_AC3))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onBufferAccepted(0L)
        assertTrue(pacer.shouldAcceptBuffer(200_000L, 200L, 1f))
        pacer.onBufferAccepted(200_000L)
        assertEquals(0L, pacer.clampPositionUs(0L, 0L, 1f))
        val clamped = pacer.clampPositionUs(2_000_000L, 200L, 1f)
        assertEquals(200_000L + PassthroughWaterLevelPacer.POSITION_LEAD_SLACK_US, clamped)
        assertEquals(50_000L, pacer.clampPositionUs(50_000L, 200L, 1f))
    }

    @Test
    fun pause_doesNotAdvanceAllowedWindow() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_AC3))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onPause(500L)
        val pausedLimit = 500_000L + PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US
        assertTrue(pacer.shouldAcceptBuffer(pausedLimit, 10_000L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(pausedLimit + 1L, 10_000L, 1f))
    }

    @Test
    fun timelineReset_relatchesPtsWhilePlaying() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(60_000_000L, 0L, 1f))
        pacer.onTimelineReset(5_000L)
        assertTrue(pacer.shouldAcceptBuffer(90_000_000L, 5_000L, 1f))
        assertFalse(
            pacer.shouldAcceptBuffer(
                90_000_000L + PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US + 1L,
                5_000L,
                1f
            )
        )
    }

    @Test
    fun onFormat_whilePlaying_resetsWallOriginAndRelatchesContinuingPts() {
        val pacer = PassthroughWaterLevelPacer()
        val playedMs = 60_000L
        val continuingPtsUs = 60_000_000L
        pacer.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onBufferAccepted(0L)
        assertTrue(pacer.shouldAcceptBuffer(continuingPtsUs, playedMs, 1f))
        pacer.onBufferAccepted(continuingPtsUs)

        pacer.onFormat(mime(MimeTypes.AUDIO_AC3), playedMs)

        assertTrue(pacer.shouldAcceptBuffer(continuingPtsUs, playedMs, 1f))
        pacer.onBufferAccepted(continuingPtsUs)
        val ceiling = PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US
        assertTrue(pacer.shouldAcceptBuffer(continuingPtsUs + ceiling, playedMs, 1f))
        assertFalse(pacer.shouldAcceptBuffer(continuingPtsUs + ceiling + 1L, playedMs, 1f))
        assertFalse(pacer.shouldAcceptBuffer(continuingPtsUs + playedMs * 1_000L, playedMs, 1f))
    }

    @Test
    fun onFormat_whilePlaying_rejectsARestartedPtsSprint() {
        val pacer = PassthroughWaterLevelPacer()
        val playedMs = 60_000L
        pacer.onFormat(mime(MimeTypes.AUDIO_DTS_HD))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        assertTrue(pacer.shouldAcceptBuffer(playedMs * 1_000L, playedMs, 1f))
        pacer.onFormat(mime(MimeTypes.AUDIO_AC3), playedMs)

        assertTrue(pacer.shouldAcceptBuffer(0L, playedMs, 1f))
        val ceiling = PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US
        assertTrue(pacer.shouldAcceptBuffer(ceiling, playedMs, 1f))
        assertFalse(pacer.shouldAcceptBuffer(ceiling + 1L, playedMs, 1f))
        assertFalse(pacer.shouldAcceptBuffer(1_000_000L, playedMs, 1f))
        assertFalse(pacer.shouldAcceptBuffer(playedMs * 1_000L, playedMs, 1f))
    }

    @Test
    fun onFormat_whilePlaying_pauseFreezesOnlyTheNewWindow() {
        val pacer = PassthroughWaterLevelPacer()
        val playedMs = 60_000L
        pacer.onFormat(mime(MimeTypes.AUDIO_AC3))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onFormat(mime(MimeTypes.AUDIO_AC3), playedMs)
        assertTrue(pacer.shouldAcceptBuffer(0L, playedMs, 1f))
        pacer.onPause(playedMs + 100L)

        val pausedLimit = 100_000L + PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US
        assertTrue(pacer.shouldAcceptBuffer(pausedLimit, playedMs + 30_000L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(pausedLimit + 1L, playedMs + 30_000L, 1f))
    }

    @Test
    fun onFormat_whilePaused_dropsTheFrozenWindow() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_AC3))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onPause(5_000L)
        pacer.onFormat(mime(MimeTypes.AUDIO_E_AC3))

        assertTrue(pacer.shouldAcceptBuffer(0L, 50_000L, 1f))
        val ceiling = PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US
        assertTrue(pacer.shouldAcceptBuffer(ceiling, 50_000L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(ceiling + 1L, 50_000L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(5_000_000L, 50_000L, 1f))

        pacer.onPlay(50_000L)
        assertTrue(pacer.shouldAcceptBuffer(200_000L + ceiling, 50_200L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(200_000L + ceiling + 1L, 50_200L, 1f))
    }

    @Test
    fun onFormat_whilePlaying_positionClampStartsFromTheNewOrigin() {
        val pacer = PassthroughWaterLevelPacer()
        val playedMs = 60_000L
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onBufferAccepted(0L)
        assertEquals(0L, pacer.clampPositionUs(0L, 0L, 1f))
        assertTrue(pacer.shouldAcceptBuffer(playedMs * 1_000L, playedMs, 1f))
        pacer.onBufferAccepted(playedMs * 1_000L)

        pacer.onFormat(mime(MimeTypes.AUDIO_AC3), playedMs)

        assertEquals(0L, pacer.clampPositionUs(0L, playedMs, 1f))
        assertEquals(
            PassthroughWaterLevelPacer.POSITION_LEAD_SLACK_US,
            pacer.clampPositionUs(5_000_000L, playedMs, 1f)
        )
        assertTrue(pacer.shouldAcceptBuffer(0L, playedMs, 1f))
        pacer.onBufferAccepted(0L)
        assertTrue(
            pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US, playedMs, 1f)
        )
        pacer.onBufferAccepted(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US)
        assertEquals(
            PassthroughWaterLevelPacer.POSITION_LEAD_SLACK_US,
            pacer.clampPositionUs(playedMs * 1_000L, playedMs, 1f)
        )
    }

    @Test
    fun onFormat_whilePlaying_usesTheNewMimeCeilingNotTheOldSession() {
        val pacer = PassthroughWaterLevelPacer()
        val playedMs = 60_000L
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        pacer.setIecPacked(false)
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onFormat(mime(MimeTypes.AUDIO_AC3), playedMs)

        assertTrue(pacer.shouldAcceptBuffer(0L, playedMs, 1f))
        assertTrue(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US, playedMs, 1f))
        assertFalse(
            pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US + 1L, playedMs, 1f)
        )
        assertFalse(
            pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US, playedMs, 1f)
        )
    }

    @Test
    fun onFormat_whilePlaying_keepsTheIecCeilingButNotTheSession() {
        val pacer = PassthroughWaterLevelPacer()
        val playedMs = 60_000L
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        pacer.setIecPacked(true)
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onFormat(mime(MimeTypes.AUDIO_DTS_HD), playedMs)
        assertEquals(PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US, pacer.writeAheadCeilingUs())

        assertTrue(pacer.shouldAcceptBuffer(0L, playedMs, 1f))
        assertTrue(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US, playedMs, 1f))
        assertFalse(
            pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US + 1L, playedMs, 1f)
        )
        assertFalse(pacer.shouldAcceptBuffer(playedMs * 1_000L, playedMs, 1f))
    }

    @Test
    fun onFormat_whilePlaying_doubleSpeedDoesNotMultiplyTheOldSession() {
        val pacer = PassthroughWaterLevelPacer()
        val playedMs = 60_000L
        pacer.onFormat(mime(MimeTypes.AUDIO_E_AC3))
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onFormat(mime(MimeTypes.AUDIO_E_AC3), playedMs)

        assertTrue(pacer.shouldAcceptBuffer(0L, playedMs, 2f))
        val ceiling = PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US
        assertTrue(pacer.shouldAcceptBuffer(ceiling, playedMs, 2f))
        assertFalse(pacer.shouldAcceptBuffer(ceiling + 1L, playedMs, 2f))
        assertFalse(pacer.shouldAcceptBuffer(playedMs * 2_000L, playedMs, 2f))
    }

    @Test
    fun sink_ac3AndDtsHdArePaced_pcmIsNot() {
        val ac3Sink = PlaybackSpeedAwareAudioSink(RecordingSink())
        ac3Sink.configure(mime(MimeTypes.AUDIO_AC3), 0, null)
        val buf = java.nio.ByteBuffer.allocate(32)
        assertTrue(ac3Sink.handleBuffer(buf, 0L, 1))
        assertFalse(
            ac3Sink.handleBuffer(buf, PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US + 1_000L, 1)
        )

        val pcmSink = PlaybackSpeedAwareAudioSink(RecordingSink())
        pcmSink.configure(mime(MimeTypes.AUDIO_RAW), 0, null)
        assertTrue(pcmSink.handleBuffer(buf, 0L, 1))
        assertTrue(
            pcmSink.handleBuffer(buf, PassthroughWaterLevelPacer.MAX_WATER_LEVEL_US + 1_000L, 1)
        )
    }

    @Test
    fun rawTrueHd_lowBitrateInitialFill_acceptsUpToTrackBufferThenPaces() {
        val pacer = PassthroughWaterLevelPacer()
        val trackBufferBytes = 2 * 61_440 // 122,880 B RAW TrueHD cap
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        pacer.setInitialFillBytes(trackBufferBytes)

        // ~400 kbps quiet TrueHD (~50 KB/s): 100 ms chunks carry 5,000 B, so 800 ms is only 40 KB.
        var ptsUs = 0L
        var totalBytes = 0
        while (totalBytes < trackBufferBytes) {
            assertTrue("Expected accept while filling initial buffer ($totalBytes B)", pacer.shouldAcceptBuffer(ptsUs, 0L, 1f))
            pacer.onBufferAccepted(ptsUs, 5_000)
            totalBytes += 5_000
            ptsUs += 100_000L
        }
        assertTrue("Low-bitrate initial fill crosses 800 ms ceiling", ptsUs > PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US)

        // Once the initial track buffer is filled, normal content-time pacing takes over.
        assertFalse(pacer.shouldAcceptBuffer(ptsUs, 0L, 1f))

        // Playing wall time lets writes resume once buffered content drains back to the 800 ms ceiling.
        pacer.onPlay(0L)
        val resumeWallMs = (ptsUs - PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US) / 1_000L
        assertFalse(pacer.shouldAcceptBuffer(ptsUs, resumeWallMs - 1L, 1f))
        assertTrue(pacer.shouldAcceptBuffer(ptsUs, resumeWallMs, 1f))

        // Position clamp still prevents HAL sprints even after a multi-second quiet initial fill.
        assertEquals(0L, pacer.clampPositionUs(0L, 0L, 1f))
        assertEquals(
            100_000L + PassthroughWaterLevelPacer.POSITION_LEAD_SLACK_US,
            pacer.clampPositionUs(2_000_000L, 100L, 1f)
        )
    }

    @Test
    fun rawTrueHd_highBitrate_enforcesEightHundredMsOnceBufferFilled() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        pacer.setInitialFillBytes(122_880)

        // 4 Mbps TrueHD (~500 KB/s): 300 ms already exceeds 122,880 B.
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onBufferAccepted(0L, 150_000)
        assertTrue(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US, 0L, 1f))
        assertFalse(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.TRUEHD_WRITE_AHEAD_US + 1L, 0L, 1f))
    }

    @Test
    fun rawPassthrough_afterTimelineReset_refillsInitialTrackBuffer() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        pacer.setInitialFillBytes(10_000)
        pacer.onPlay(0L)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onBufferAccepted(0L, 10_000)
        assertFalse(pacer.shouldAcceptBuffer(1_500_000L, 0L, 1f))

        // Seek / flush calls onTimelineReset: acceptedBytes resets while initialFillBytes stays armed.
        pacer.onTimelineReset(5_000L)
        assertTrue(pacer.shouldAcceptBuffer(25_000_000L, 5_000L, 1f))
        pacer.onBufferAccepted(25_000_000L, 4_000)
        assertTrue(pacer.shouldAcceptBuffer(26_000_000L, 5_000L, 1f))
        pacer.onBufferAccepted(26_000_000L, 6_000)
        assertFalse(pacer.shouldAcceptBuffer(26_500_000L, 5_000L, 1f))
    }

    @Test
    fun iecPacked_ignoresInitialFillBytes() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        pacer.setIecPacked(true)
        pacer.setInitialFillBytes(122_880)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onBufferAccepted(0L, 1_000)
        assertFalse(pacer.shouldAcceptBuffer(PassthroughWaterLevelPacer.IEC_WRITE_AHEAD_US + 1L, 0L, 1f))
    }

    @Test
    fun partialWrites_advanceInitialFillProgress() {
        val pacer = PassthroughWaterLevelPacer()
        pacer.onFormat(mime(MimeTypes.AUDIO_TRUEHD))
        pacer.setInitialFillBytes(8_000)
        assertTrue(pacer.shouldAcceptBuffer(0L, 0L, 1f))
        pacer.onBytesConsumed(5_000)
        assertTrue(pacer.shouldAcceptBuffer(1_000_000L, 0L, 1f))
        pacer.onBytesConsumed(3_000)
        assertFalse(pacer.shouldAcceptBuffer(1_200_000L, 0L, 1f))
    }

    @Test
    fun sink_rawPassthroughInitialFill_wiredThroughConfigureAndFlush() {
        var trackBufferBytes = 10_000
        val sink = PlaybackSpeedAwareAudioSink(
            sink = RecordingSink(),
            rawPassthroughBufferBytesProvider = { trackBufferBytes }
        )
        sink.configure(mime(MimeTypes.AUDIO_TRUEHD), 0, null)

        val chunk = java.nio.ByteBuffer.allocate(5_000)
        assertTrue(sink.handleBuffer(chunk, 0L, 1))
        assertTrue(sink.handleBuffer(chunk, 1_200_000L, 1))
        // Now 10,000 B accepted -> initial fill satisfied -> next buffer past 800 ms is paced.
        assertFalse(sink.handleBuffer(chunk, 1_500_000L, 1))

        // After flush, the recreated track can fill its initial buffer again.
        sink.flush()
        assertTrue(sink.handleBuffer(chunk, 25_000_000L, 1))
        assertTrue(sink.handleBuffer(chunk, 26_200_000L, 1))
        assertFalse(sink.handleBuffer(chunk, 26_500_000L, 1))

        // Reconfiguring to a non-passthrough track clears the initial fill target.
        trackBufferBytes = 0
        sink.configure(mime(MimeTypes.AUDIO_RAW), 0, null)
        assertTrue(sink.handleBuffer(chunk, 30_000_000L, 1))
    }

    private fun mime(sampleMimeType: String): Format {
        return Format.Builder()
            .setSampleMimeType(sampleMimeType)
            .setChannelCount(8)
            .setSampleRate(48_000)
            .build()
    }

    private class RecordingSink : AudioSink {
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean = true
        override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
            AudioOffloadSupport.DEFAULT_UNSUPPORTED
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
        override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = Unit
        override fun play() = Unit
        override fun handleDiscontinuity() = Unit
        override fun handleBuffer(
            buffer: java.nio.ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean = true
        override fun playToEndOfStream() = Unit
        override fun isEnded(): Boolean = false
        override fun hasPendingData(): Boolean = false
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
        override fun getSkipSilenceEnabled(): Boolean = false
        override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) = Unit
        override fun getAudioAttributes(): androidx.media3.common.AudioAttributes? = null
        override fun setAudioSessionId(audioSessionId: Int) = Unit
        override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) = Unit
        override fun enableTunnelingV21() = Unit
        override fun disableTunneling() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun reset() = Unit
        override fun release() = Unit
    }
}
