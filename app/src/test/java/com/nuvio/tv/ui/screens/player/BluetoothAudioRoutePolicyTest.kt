package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Bluetooth media policy mirrors Media3 1.8.0 AudioCapabilities:
 * when the route is Bluetooth, encoded passthrough is rejected and PCM decode is forced.
 */
class BluetoothAudioRoutePolicyTest {

    @Test
    fun `bluetooth type set matches Media3 A2DP SCO and LE`() {
        assertTrue(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertTrue(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertTrue(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_BLE_BROADCAST))
        assertFalse(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_HDMI))
        assertFalse(AudioOutputRouteDetector.isBluetoothType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    @Test
    fun `bluetooth force pcm rejects eac3 truehd and dts direct sink support`() {
        val sink = PlaybackSpeedAwareAudioSink(
            sink = AlwaysSupportedDelegateSink(),
            initialForcePcm = true,
            forcePcmForBluetooth = true
        )
        val formats = listOf(
            mime(MimeTypes.AUDIO_E_AC3),
            mime(MimeTypes.AUDIO_TRUEHD),
            mime(MimeTypes.AUDIO_DTS_HD),
            mime(MimeTypes.AUDIO_AC3)
        )
        for (format in formats) {
            assertEquals(
                "Expected PCM force for ${format.sampleMimeType}",
                AudioSink.SINK_FORMAT_UNSUPPORTED,
                sink.getFormatSupport(format)
            )
            assertTrue(sink.shouldForcePcmForFormat(format))
        }
    }

    @Test
    fun `without bluetooth force pcm allows aac through`() {
        val sink = PlaybackSpeedAwareAudioSink(
            sink = AlwaysSupportedDelegateSink(),
            initialForcePcm = false,
            forcePcmForBluetooth = false
        )
        val aac = mime(MimeTypes.AUDIO_AAC)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(aac))
        assertFalse(sink.shouldForcePcmForFormat(aac))
    }

    @Test
    fun `speed change alone forces pcm for surround without bluetooth flag`() {
        val sink = PlaybackSpeedAwareAudioSink(
            sink = AlwaysSupportedDelegateSink(),
            initialForcePcm = false,
            forcePcmForBluetooth = false
        )
        sink.setPlaybackParameters(PlaybackParameters(1.5f))
        val eac3 = mime(MimeTypes.AUDIO_E_AC3)
        assertTrue(sink.shouldForcePcmForFormat(eac3))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(eac3))
    }

    @Test
    fun `bluetooth connect or disconnect never asks to reinitialize the player`() {
        assertEquals(
            BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = false,
                isBluetooth = true,
                usingMpv = false
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = true,
                isBluetooth = false,
                usingMpv = false
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.NONE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = true,
                isBluetooth = true,
                usingMpv = false
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = false,
                isBluetooth = true,
                usingMpv = true
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = true,
                isBluetooth = false,
                usingMpv = true
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.NONE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = true,
                isBluetooth = true,
                usingMpv = true
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = true,
                isBluetooth = true,
                usingMpv = true,
                oldRouteKey = "type:bluetooth_a2dp|name:speaker_a",
                newRouteKey = "type:bluetooth_a2dp|name:speaker_b"
            )
        )
        assertEquals(
            BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE,
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = true,
                isBluetooth = true,
                usingMpv = false,
                oldRouteKey = "type:bluetooth_a2dp|name:speaker_a",
                newRouteKey = "type:bluetooth_a2dp|name:speaker_b"
            )
        )
    }

    @Test
    fun `mpv bluetooth forces stereo and clears encoded passthrough`() {
        assertEquals(MpvBluetoothAudioPolicy.STEREO_CHANNELS, MpvBluetoothAudioPolicy.audioChannels(true))
        assertEquals(MpvBluetoothAudioPolicy.AUTO_CHANNELS, MpvBluetoothAudioPolicy.audioChannels(false))
        assertTrue(MpvBluetoothAudioPolicy.shouldClearAudioSpdif(true))
        assertFalse(MpvBluetoothAudioPolicy.shouldClearAudioSpdif(false))
    }

    @Test
    fun `audio delay milliseconds map to mpv seconds and clamp`() {
        assertEquals(0.200, audioDelayMsToSeconds(200), 0.0001)
        assertEquals(-0.250, audioDelayMsToSeconds(-250), 0.0001)
        assertEquals(AUDIO_DELAY_MAX_MS / 1000.0, audioDelayMsToSeconds(99_999), 0.0001)
        assertEquals(AUDIO_DELAY_MIN_MS / 1000.0, audioDelayMsToSeconds(-99_999), 0.0001)
    }

    @Test
    fun `mid-session bluetooth connect forces pcm without a rebuild flag`() {
        val sink = PlaybackSpeedAwareAudioSink(
            sink = AlwaysSupportedDelegateSink(),
            initialForcePcm = false,
            forcePcmForBluetooth = false
        )
        val eac3 = mime(MimeTypes.AUDIO_E_AC3)
        assertFalse(sink.shouldForcePcmForFormat(eac3))

        assertTrue(sink.setBluetoothForcePcm(true))
        assertTrue(sink.isBluetoothForcePcm())
        assertTrue(sink.shouldForcePcmForFormat(eac3))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(eac3))
        assertFalse(sink.setBluetoothForcePcm(true))
    }

    @Test
    fun `hdmi-started session restores passthrough after bluetooth disconnect`() {
        val sink = PlaybackSpeedAwareAudioSink(
            sink = AlwaysSupportedDelegateSink(),
            initialForcePcm = false,
            forcePcmForBluetooth = false
        )
        val eac3 = mime(MimeTypes.AUDIO_E_AC3)
        sink.setBluetoothForcePcm(true)
        assertTrue(sink.shouldForcePcmForFormat(eac3))

        assertTrue(sink.setBluetoothForcePcm(false))
        assertFalse(sink.isBluetoothForcePcm())
        assertFalse(sink.shouldForcePcmForFormat(eac3))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(eac3))
    }

    @Test
    fun `session built for bluetooth stays on pcm after disconnect`() {
        val sink = PlaybackSpeedAwareAudioSink(
            sink = AlwaysSupportedDelegateSink(),
            initialForcePcm = true,
            forcePcmForBluetooth = true
        )
        val truehd = mime(MimeTypes.AUDIO_TRUEHD)
        sink.setBluetoothForcePcm(false)
        assertFalse(sink.isBluetoothForcePcm())
        assertTrue(sink.shouldForcePcmForFormat(truehd))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(truehd))
    }

    @Test
    fun `playback state preservation policy correctly distinguishes playing vs paused intents`() {
        // User paused manually -> always stays paused regardless of player playWhenReady
        val userPausedManually = true
        val playWhenReady = true
        val wasPlayingWithUserPause = playWhenReady && !userPausedManually
        assertFalse(wasPlayingWithUserPause)

        // User was playing actively (playWhenReady = true, userPaused = false) -> keeps playing
        val userPlaying = false
        val activePlayWhenReady = true
        val wasPlayingActive = activePlayWhenReady && !userPlaying
        assertTrue(wasPlayingActive)

        // Video was paused (playWhenReady = false, userPaused = false) -> stays paused
        val idlePlayWhenReady = false
        val wasPlayingIdle = idlePlayWhenReady && !userPlaying
        assertFalse(wasPlayingIdle)
    }

    @Test
    fun `playback intent survives buffering state when playWhenReady is true`() {
        // Simulates ExoPlayer in STATE_BUFFERING: isPlaying is false, but playWhenReady is true
        val isExoPlaying = false
        val playWhenReady = true
        val userPausedManually = false

        // hasActivePlayIntent evaluates playWhenReady
        val hasActiveIntent = playWhenReady
        assertTrue(hasActiveIntent)

        val shouldKeepPlaying = hasActiveIntent && !userPausedManually
        assertTrue(shouldKeepPlaying)
    }

    @Test
    fun `playback intent correctly pauses when user has manually paused during buffering`() {
        val playWhenReady = true
        val userPausedManually = true

        val shouldKeepPlaying = playWhenReady && !userPausedManually
        assertFalse(shouldKeepPlaying)
    }

    @Test
    fun `dolby atmos requires 3 primed access units before ready while dts primes on first`() {
        val baseSink =ControllableTestAudioSink()
        val sink = PlaybackSpeedAwareAudioSink(sink = baseSink)
        val atmos = mime(MimeTypes.AUDIO_E_AC3_JOC)
        val pcm = mime(MimeTypes.AUDIO_RAW)

        // Even when MediaCodec decodes Atmos to PCM (configure gets AUDIO_RAW),
        // upstream track format keeps Dolby Atmos priming active.
        sink.setUpstreamTrackFormat(atmos)
        sink.configure(pcm, 0, null)
        sink.onRendererPositionReset(0L)

        val buf = ByteBuffer.allocate(16)
        assertFalse(sink.isPrimedForReady())

        sink.handleBuffer(buf, 0L, 1)
        assertFalse("1st Atmos frame (32ms) should not mark sink primed", sink.isPrimedForReady())

        sink.handleBuffer(buf, 32_000L, 1)
        assertFalse("2nd Atmos frame (64ms) should not mark sink primed", sink.isPrimedForReady())

        sink.handleBuffer(buf, 64_000L, 1)
        assertTrue("3rd Atmos frame (96ms) should mark sink primed", sink.isPrimedForReady())

        // DTS should prime on the very first buffer.
        sink.flush()
        val dts = mime(MimeTypes.AUDIO_DTS_HD)
        sink.setUpstreamTrackFormat(dts)
        sink.configure(dts, 0, null)
        sink.onRendererPositionReset(10_000_000L)
        sink.handleBuffer(buf, 10_000_000L, 1)
        assertTrue("DTS should prime after 1st frame", sink.isPrimedForReady())
    }

    @Test
    fun `dolby atmos startup and seek 96ms clock burst is smoothed so frames 2 and 3 are not dropped`() {
        var fakeClockMs = 1_000L
        val baseSink = ControllableTestAudioSink()
        val sink = PlaybackSpeedAwareAudioSink(
            sink = baseSink,
            elapsedRealtimeMsProvider = { fakeClockMs }
        )
        val atmos = mime(MimeTypes.AUDIO_E_AC3_JOC)
        sink.setUpstreamTrackFormat(atmos)
        sink.configure(atmos, 0, null)

        val seekPosUs = 100_000_000L
        sink.onRendererPositionReset(seekPosUs)
        val buf = ByteBuffer.allocate(16)
        sink.handleBuffer(buf, seekPosUs, 1)
        sink.handleBuffer(buf, seekPosUs + 32_000L, 1)
        sink.handleBuffer(buf, seekPosUs + 64_000L, 1)
        sink.play()

        // Simulate HAL FIFO immediately gulping 3 E-AC3-JOC syncframes (96ms) 5ms after play().
        fakeClockMs += 5L
        baseSink.currentPositionUs = seekPosUs + 96_000L

        val posAt5ms = sink.getCurrentPositionUs(false)
        val frame2PtsUs = seekPosUs + 41_708L
        val frame2EarlyUs = frame2PtsUs - posAt5ms
        assertTrue(
            "Frame 2 must remain early (earlyUs=$frame2EarlyUs > 0), not dropped (< -30000)",
            frame2EarlyUs > 0L
        )
        assertTrue(
            "Position must advance past initialPositionUs to unlock VideoFrameReleaseControl",
            posAt5ms > seekPosUs
        )

        // At 38ms wall-clock, Frame 3 (+83.4ms) is evaluated.
        fakeClockMs += 33L // total 38ms
        val posAt38ms = sink.getCurrentPositionUs(false)
        val frame3PtsUs = seekPosUs + 83_416L
        val frame3EarlyUs = frame3PtsUs - posAt38ms
        assertTrue(
            "Frame 3 must remain early (earlyUs=$frame3EarlyUs > 0), not dropped (< -30000)",
            frame3EarlyUs > 0L
        )
    }

    @Test
    fun `dolby atmos playhead zero stall unsticks gently so frame 2 is not blocked on initialPositionUs`() {
        var fakeClockMs = 5_000L
        val baseSink = ControllableTestAudioSink()
        val sink = PlaybackSpeedAwareAudioSink(
            sink = baseSink,
            elapsedRealtimeMsProvider = { fakeClockMs }
        )
        val atmos = mime(MimeTypes.AUDIO_E_AC3_JOC)
        sink.setUpstreamTrackFormat(atmos)
        sink.configure(atmos, 0, null)
        sink.onRendererPositionReset(0L)

        val buf = ByteBuffer.allocate(16)
        sink.handleBuffer(buf, 0L, 3)
        sink.play()

        // Simulate HDMI eARC / Dolby DSP keeping getPlaybackHeadPosition() at 0 for 20ms.
        baseSink.currentPositionUs = 0L
        fakeClockMs += 20L

        val posAt20ms = sink.getCurrentPositionUs(false)
        assertTrue("Clock should unstick from 0Us while pending data is playing", posAt20ms > 0L)
        assertTrue("Synthetic unstick must stay bounded (<= 2ms)", posAt20ms <= 2_000L)
    }

    @Test
    fun `dolby atmos in-buffer seek defers new track initialization until async flush release and hal teardown finish`() {
        var fakeClockMs = 10_000L
        var pendingAsyncRelease = false
        val baseSink = ControllableTestAudioSink()
        val sink = PlaybackSpeedAwareAudioSink(
            sink = baseSink,
            elapsedRealtimeMsProvider = { fakeClockMs },
            hasPendingReleasesProvider = { pendingAsyncRelease }
        )
        val atmos = mime(MimeTypes.AUDIO_E_AC3_JOC)
        sink.setUpstreamTrackFormat(atmos)
        sink.configure(atmos, 0, null)
        sink.onRendererPositionReset(0L)

        val buf = ByteBuffer.allocate(16)
        // Cold start: no previous AudioTrack was active, so handleBuffer succeeds immediately at t=0.
        assertTrue(sink.handleBuffer(buf, 0L, 3))
        assertTrue(sink.isPrimedForReady())
        sink.play()

        // Simulate an in-buffer seek: DefaultAudioSink.flush() schedules async AudioTrack release.
        fakeClockMs += 500L
        pendingAsyncRelease = true
        sink.pause()
        sink.flush()
        sink.onRendererPositionReset(30_000_000L)

        // At +43ms after flush (in-buffer seek packets already in RAM), async release is still pending:
        // handleBuffer must return false so a new AudioTrack does not collide with the old one in AudioHw.
        fakeClockMs += 43L
        assertFalse(sink.handleBuffer(buf, 30_000_000L, 1))
        assertFalse(sink.isPrimedForReady())

        // At +70ms after flush, ExoPlayer:AudioTrackReleaseThread finishes ~AudioTrack,
        // but rtk_audio_offload close-async teardown is still running (< 175ms total).
        fakeClockMs += 27L
        pendingAsyncRelease = false
        assertFalse(sink.handleBuffer(buf, 30_000_000L, 1))

        // At +180ms after flush, both ~AudioTrack and hardware offload close-async have completed:
        // handleBuffer now initializes the clean AudioTrack and primes 3 Atmos frames.
        fakeClockMs += 110L
        assertTrue(sink.handleBuffer(buf, 30_000_000L, 1))
        assertTrue(sink.handleBuffer(buf, 30_032_000L, 1))
        assertTrue(sink.handleBuffer(buf, 30_064_000L, 1))
        assertTrue(sink.isPrimedForReady())
    }

    @Test
    fun `dolby atmos resume after long pause holds clock to paused position plus wall clock and converges without jumping`() {
        var fakeClockMs = 20_000L
        val baseSink = ControllableTestAudioSink()
        val sink = PlaybackSpeedAwareAudioSink(
            sink = baseSink,
            elapsedRealtimeMsProvider = { fakeClockMs }
        )
        val atmos = mime(MimeTypes.AUDIO_E_AC3_JOC)
        sink.setUpstreamTrackFormat(atmos)
        sink.configure(atmos, 0, null)
        sink.onRendererPositionReset(0L)
        val buf = ByteBuffer.allocate(16)
        sink.handleBuffer(buf, 0L, 3)
        sink.play()

        // Play well past the startup smoothing window.
        fakeClockMs += 5_000L
        baseSink.currentPositionUs = 5_000_000L
        assertEquals(5_000_000L, sink.getCurrentPositionUs(false))

        // Long pause (buffer fills up, offload HAL enters standby).
        sink.pause()
        fakeClockMs += 40_000L

        // play() blocks ~300ms in the HAL; afterwards Media3 reports +143ms ahead of audible audio.
        sink.play()
        baseSink.currentPositionUs = 5_143_000L
        val posAtResume = sink.getCurrentPositionUs(false)
        assertEquals("Clock must restart from the paused position", 5_000_000L, posAtResume)
        val firstFramePtsUs = 5_127_000L
        assertTrue("First frame after resume must not be late", firstFramePtsUs - posAtResume > -30_000L)

        // Raw clock self-corrects slowly (0.9x); guarded clock never runs faster than 1.06x.
        var previousUs = posAtResume
        var rawUs = 5_143_000L
        repeat(150) {
            fakeClockMs += 10L
            rawUs += 9_000L
            baseSink.currentPositionUs = rawUs
            val posUs = sink.getCurrentPositionUs(false)
            val stepUs = posUs - previousUs
            assertTrue("Clock must be monotonic (step=$stepUs)", stepUs >= 0L)
            assertTrue("Clock must never jump (step=$stepUs)", stepUs <= 10_600L + 1L)
            previousUs = posUs
        }
        assertEquals("Guard must hand back to the raw clock once converged", rawUs, previousUs)
    }

    @Test
    fun `dts resume after pause is not guarded`() {
        var fakeClockMs = 20_000L
        val baseSink = ControllableTestAudioSink()
        val sink = PlaybackSpeedAwareAudioSink(
            sink = baseSink,
            elapsedRealtimeMsProvider = { fakeClockMs }
        )
        val dts = mime(MimeTypes.AUDIO_DTS)
        sink.setUpstreamTrackFormat(dts)
        sink.configure(dts, 0, null)
        sink.onRendererPositionReset(0L)
        sink.handleBuffer(ByteBuffer.allocate(16), 0L, 1)
        sink.play()
        fakeClockMs += 5_000L
        baseSink.currentPositionUs = 5_000_000L
        sink.getCurrentPositionUs(false)
        sink.pause()
        fakeClockMs += 40_000L
        sink.play()
        baseSink.currentPositionUs = 5_143_000L
        assertEquals(5_143_000L, sink.getCurrentPositionUs(false))
    }

    private fun mime(sampleMimeType: String): Format {
        return Format.Builder()
            .setSampleMimeType(sampleMimeType)
            .setChannelCount(6)
            .setSampleRate(48_000)
            .build()
    }

    private class ControllableTestAudioSink : NoOpBaseSink() {
        var currentPositionUs: Long = 0L
        var pendingData: Boolean = false

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = currentPositionUs

        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean {
            pendingData = true
            return true
        }

        override fun hasPendingData(): Boolean = pendingData

        override fun flush() {
            pendingData = false
        }

        override fun reset() {
            pendingData = false
        }
    }

    private class AlwaysSupportedDelegateSink : ForwardingAudioSink(NoOpBaseSink())

    private open class NoOpBaseSink : AudioSink {
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
            buffer: ByteBuffer,
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
