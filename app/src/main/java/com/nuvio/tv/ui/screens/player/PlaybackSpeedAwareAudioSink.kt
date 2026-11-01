package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.lang.reflect.Method
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Audio sink wrapper that forces a decode-to-PCM path when:
 * - Playback speed != 1x for bitstream formats that cannot be tempo-adjusted in passthrough, or
 * - Bluetooth media output is active (Media3 policy: Bluetooth only supports PCM).
 *
 * Bluetooth cannot carry TrueHD / Atmos / DTS-HD passthrough. Forcing PCM lets MediaCodec/FFmpeg
 * decode to the format the BT stack actually accepts; the system then encodes to SBC/AAC/aptX/LDAC.
 *
 * Also guards Dolby Atmos / surround startup, in-buffer seeks, and out-of-buffer seeks against
 * three Media3 + Android Audio HAL issues:
 * 1. Premature `STATE_READY` before the Dolby JOC lookahead / `AudioTrack` buffer is primed.
 * 2. Initial 2-3 syncframe (64-96ms) clock burst jump or playhead-zero stall while
 *    `AudioTimestampPoller` is in `STATE_INITIALIZING`, which otherwise pushes video frames 2-3
 *    past `VideoFrameReleaseControl`'s -30ms drop threshold.
 * 3. In-buffer seek race condition in `DefaultAudioSink.flush()`: Media3 releases the old
 *    `AudioTrack` asynchronously after a 20ms delay on `ExoPlayer:AudioTrackReleaseThread` while
 *    immediately setting `audioTrack = null`. On in-buffer seeks where audio packets are already
 *    in RAM, `handleBuffer()` immediately creates and starts a new direct/offload `AudioTrack`
 *    while the old `AudioTrack`'s delayed `flush()` / `release()` is still executing in the
 *    hardware offload HAL (e.g. `rtk_audio_offload`), wiping out the newly primed Atmos frames
 *    and causing `dspFrames = 0` underflow + 2-3 dropped video frames.
 */
internal class PlaybackSpeedAwareAudioSink(
    sink: AudioSink,
    initialForcePcm: Boolean = false,
    forcePcmForBluetooth: Boolean = false,
    private val elapsedRealtimeMsProvider: () -> Long = SystemClock::elapsedRealtime,
    private val hasPendingReleasesProvider: () -> Boolean = Companion::queryDefaultAudioSinkPendingReleases
) : ForwardingAudioSink(sink) {

    // Set when the sink is built with forcePcm (error recovery). Don't clear on speed reset.
    private val startedWithForcedPcm: Boolean = initialForcePcm

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var forcePcmForCurrentSession: Boolean = initialForcePcm

    @Volatile
    private var bluetoothForcePcm: Boolean = forcePcmForBluetooth

    @Volatile
    private var currentInputFormat: Format? = null

    @Volatile
    private var upstreamTrackFormat: Format? = null

    @Volatile
    private var listener: AudioSink.Listener? = null

    @Volatile
    private var isSinkPlaying: Boolean = false

    @Volatile
    private var buffersHandledSinceReset: Int = 0

    @Volatile
    private var accessUnitsHandledSinceReset: Int = 0

    @Volatile
    private var sinkFullOnHandleBuffer: Boolean = false

    @Volatile
    private var firstBufferPresentationTimeUs: Long = C.TIME_UNSET

    @Volatile
    private var lastHandledPresentationTimeUs: Long = C.TIME_UNSET

    @Volatile
    private var rendererResetPositionUs: Long = C.TIME_UNSET

    @Volatile
    private var playStartElapsedRealtimeMs: Long = C.TIME_UNSET

    @Volatile
    private var postResetClockSmoothingActive: Boolean = true

    @Volatile
    private var lastSmoothedPositionUs: Long = C.TIME_UNSET

    @Volatile
    private var hasActiveAudioTrack: Boolean = false

    @Volatile
    private var awaitingPostFlushTrackRelease: Boolean = false

    @Volatile
    private var lastFlushElapsedRealtimeMs: Long = C.TIME_UNSET

    @Volatile
    private var lastAudioTrackReleasedElapsedMs: Long = C.TIME_UNSET

    fun setInitialPlaybackSpeed(speed: Float) {
        playbackSpeed = normalizeSpeed(speed)
        markPcmFallbackIfNeeded(upstreamTrackFormat ?: currentInputFormat, playbackSpeed)
    }

    fun setUpstreamTrackFormat(format: Format?) {
        upstreamTrackFormat = format
        markPcmFallbackIfNeeded(format ?: currentInputFormat, playbackSpeed)
    }

    fun onRendererPositionReset(positionUs: Long) {
        rendererResetPositionUs = positionUs
        postResetClockSmoothingActive = true
        lastSmoothedPositionUs = C.TIME_UNSET
    }

    fun isPrimedForReady(): Boolean {
        if (awaitingPostFlushTrackRelease) {
            return false
        }
        if (!hasPendingData()) {
            return false
        }
        if (sinkFullOnHandleBuffer) {
            return true
        }
        val requiredAccessUnits = if (isDolbyAtmosOrSurroundFormat()) {
            MIN_DOLBY_PRIME_ACCESS_UNITS
        } else {
            1
        }
        return accessUnitsHandledSinceReset >= requiredAccessUnits
    }

    /**
     * Update Bluetooth policy without rebuilding the player.
     * Call [notifyAudioProcessingRequirementChanged] after a change so Media3 reselects
     * decode-to-PCM vs passthrough on the live renderer.
     *
     * @return true when the effective PCM/passthrough policy changed.
     */
    fun setBluetoothForcePcm(enabled: Boolean): Boolean {
        val wasBluetoothForce = bluetoothForcePcm
        val wasSessionForce = forcePcmForCurrentSession
        bluetoothForcePcm = enabled
        if (enabled) {
            forcePcmForCurrentSession = true
        } else if (!startedWithForcedPcm && playbackSpeed == 1f) {
            // Session was not built as PCM-only; leaving Bluetooth can restore passthrough.
            forcePcmForCurrentSession = false
        }
        return wasBluetoothForce != bluetoothForcePcm || wasSessionForce != forcePcmForCurrentSession
    }

    fun isBluetoothForcePcm(): Boolean = bluetoothForcePcm

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() {
                listener.onPositionDiscontinuity()
            }

            override fun onUnderrun(
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long
            ) {
                listener.onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
            }

            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
                listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
            }

            override fun onOffloadBufferEmptying() {
                listener.onOffloadBufferEmptying()
            }

            override fun onOffloadBufferFull() {
                listener.onOffloadBufferFull()
            }

            override fun onAudioSinkError(audioSinkError: Exception) {
                listener.onAudioSinkError(audioSinkError)
            }

            override fun onAudioCapabilitiesChanged() {
                listener.onAudioCapabilitiesChanged()
            }

            override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) {
                hasActiveAudioTrack = true
                awaitingPostFlushTrackRelease = false
                listener.onAudioTrackInitialized(audioTrackConfig)
            }

            override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) {
                lastAudioTrackReleasedElapsedMs = elapsedRealtimeMsProvider()
                listener.onAudioTrackReleased(audioTrackConfig)
            }

            override fun onSilenceSkipped() {
                listener.onSilenceSkipped()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                listener.onAudioSessionIdChanged(audioSessionId)
            }
        })
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        currentInputFormat = inputFormat
        if (inputFormat.sampleMimeType != MimeTypes.AUDIO_RAW) {
            upstreamTrackFormat = inputFormat
        }
        resetPrimingAndClockState(preserveResetPosition = true)
        markPcmFallbackIfNeeded(upstreamTrackFormat ?: inputFormat, playbackSpeed)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun play() {
        super.play()
        isSinkPlaying = true
        if (playStartElapsedRealtimeMs == C.TIME_UNSET && !awaitingPostFlushTrackRelease) {
            playStartElapsedRealtimeMs = elapsedRealtimeMsProvider()
        }
    }

    override fun pause() {
        isSinkPlaying = false
        playStartElapsedRealtimeMs = C.TIME_UNSET
        super.pause()
    }

    override fun handleDiscontinuity() {
        postResetClockSmoothingActive = true
        lastSmoothedPositionUs = C.TIME_UNSET
        super.handleDiscontinuity()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (shouldDeferBufferForPendingFlushRelease()) {
            return false
        }
        if (firstBufferPresentationTimeUs == C.TIME_UNSET) {
            firstBufferPresentationTimeUs = presentationTimeUs
        }
        val isNewBuffer = presentationTimeUs != lastHandledPresentationTimeUs
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (handled) {
            hasActiveAudioTrack = true
            if (isNewBuffer) {
                lastHandledPresentationTimeUs = presentationTimeUs
                buffersHandledSinceReset += 1
                accessUnitsHandledSinceReset += encodedAccessUnitCount.coerceAtLeast(1)
            }
            if (isSinkPlaying && playStartElapsedRealtimeMs == C.TIME_UNSET) {
                playStartElapsedRealtimeMs = elapsedRealtimeMsProvider()
            }
        } else if (hasPendingData()) {
            hasActiveAudioTrack = true
            sinkFullOnHandleBuffer = true
        }
        return handled
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val rawPositionUs = super.getCurrentPositionUs(sourceEnded)
        if (rawPositionUs == AudioSink.CURRENT_POSITION_NOT_SET || sourceEnded) {
            return rawPositionUs
        }
        if (!postResetClockSmoothingActive || !isDolbyAtmosOrSurroundFormat()) {
            return rawPositionUs
        }
        val playStartMs = playStartElapsedRealtimeMs
        if (playStartMs == C.TIME_UNSET) {
            return rawPositionUs
        }
        val anchorUs = when {
            rendererResetPositionUs != C.TIME_UNSET -> rendererResetPositionUs
            firstBufferPresentationTimeUs != C.TIME_UNSET -> firstBufferPresentationTimeUs.coerceAtLeast(0L)
            else -> {
                postResetClockSmoothingActive = false
                return rawPositionUs
            }
        }
        val elapsedSincePlayMs = (elapsedRealtimeMsProvider() - playStartMs).coerceAtLeast(0L)
        if (elapsedSincePlayMs >= CLOCK_SMOOTHING_WINDOW_MS) {
            postResetClockSmoothingActive = false
            return if (lastSmoothedPositionUs != C.TIME_UNSET) {
                maxOf(lastSmoothedPositionUs, rawPositionUs)
            } else {
                rawPositionUs
            }
        }
        val speed = playbackSpeed.takeIf { it > 0f } ?: 1f
        val wallClockElapsedUs = (elapsedSincePlayMs * 1000.0 * speed).toLong()
        val expectedWallPositionUs = anchorUs + wallClockElapsedUs

        // If rawPositionUs is far from the expected position, this is a coarse container seek
        // rather than a 2-3 frame Dolby Atmos startup/seek burst; let the clock snap immediately.
        if (abs(rawPositionUs - expectedWallPositionUs) > MAX_SMOOTHABLE_DRIFT_US) {
            postResetClockSmoothingActive = false
            return rawPositionUs
        }

        val maxAllowedPositionUs =
            anchorUs + INITIAL_CLOCK_UNSTICK_US + (wallClockElapsedUs * 115L) / 100L
        val minAllowedPositionUs = if (elapsedSincePlayMs >= MIN_UNSTICK_DELAY_MS && hasPendingData()) {
            anchorUs + minOf(wallClockElapsedUs, MAX_SYNTHETIC_ADVANCE_US)
        } else {
            anchorUs
        }

        val smoothedUs = rawPositionUs.coerceIn(
            minAllowedPositionUs.coerceAtMost(maxAllowedPositionUs),
            maxAllowedPositionUs
        )
        val monotonicUs = if (lastSmoothedPositionUs != C.TIME_UNSET) {
            maxOf(lastSmoothedPositionUs, smoothedUs)
        } else {
            smoothedUs
        }
        lastSmoothedPositionUs = monotonicUs
        return monotonicUs
    }

    override fun flush() {
        val hadActiveTrack = hasActiveAudioTrack || buffersHandledSinceReset > 0
        super.flush()
        resetPrimingAndClockState(preserveResetPosition = false)
        hasActiveAudioTrack = false
        if (hadActiveTrack && isDolbyAtmosOrSurroundFormat()) {
            awaitingPostFlushTrackRelease = true
            lastFlushElapsedRealtimeMs = elapsedRealtimeMsProvider()
            lastAudioTrackReleasedElapsedMs = C.TIME_UNSET
        } else {
            awaitingPostFlushTrackRelease = false
            if (isSinkPlaying) {
                playStartElapsedRealtimeMs = elapsedRealtimeMsProvider()
            }
        }
    }

    override fun reset() {
        val hadActiveTrack = hasActiveAudioTrack || buffersHandledSinceReset > 0
        isSinkPlaying = false
        super.reset()
        resetPrimingAndClockState(preserveResetPosition = false)
        hasActiveAudioTrack = false
        if (hadActiveTrack && isDolbyAtmosOrSurroundFormat()) {
            awaitingPostFlushTrackRelease = true
            lastFlushElapsedRealtimeMs = elapsedRealtimeMsProvider()
            lastAudioTrackReleasedElapsedMs = C.TIME_UNSET
        } else {
            awaitingPostFlushTrackRelease = false
        }
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        playbackSpeed = normalizeSpeed(playbackParameters.speed)
        var shouldNotify = markPcmFallbackIfNeeded(upstreamTrackFormat ?: currentInputFormat, playbackSpeed)
        // Going above 1x latches forcePcm for the session. Clear it when back at 1.0x
        // so passthrough can recover (unless recovery built us with forcePcm).
        if (playbackSpeed == 1f && forcePcmForCurrentSession && !startedWithForcedPcm) {
            forcePcmForCurrentSession = false
            shouldNotify = true
        }
        super.setPlaybackParameters(playbackParameters)
        if (shouldNotify) {
            listener?.onAudioCapabilitiesChanged()
        }
    }

    fun notifyAudioProcessingRequirementChanged() {
        listener?.onAudioCapabilitiesChanged()
    }

    override fun getFormatSupport(format: Format): Int {
        if (shouldRejectDirectPlayback(format)) {
            return AudioSink.SINK_FORMAT_UNSUPPORTED
        }
        return super.getFormatSupport(format)
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        if (shouldRejectDirectPlayback(format)) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
        return super.getFormatOffloadSupport(format)
    }

    fun shouldForcePcmForFormat(format: Format): Boolean {
        return shouldRejectDirectPlayback(format)
    }

    private fun shouldDeferBufferForPendingFlushRelease(): Boolean {
        if (!awaitingPostFlushTrackRelease) {
            return false
        }
        if (!isDolbyAtmosOrSurroundFormat() || buffersHandledSinceReset > 0) {
            awaitingPostFlushTrackRelease = false
            return false
        }
        val flushMs = lastFlushElapsedRealtimeMs
        if (flushMs == C.TIME_UNSET) {
            awaitingPostFlushTrackRelease = false
            return false
        }
        val nowMs = elapsedRealtimeMsProvider()
        val elapsedSinceFlushMs = (nowMs - flushMs).coerceAtLeast(0L)
        if (elapsedSinceFlushMs >= MAX_DOLBY_FLUSH_REINIT_WAIT_MS) {
            awaitingPostFlushTrackRelease = false
            return false
        }
        if (hasPendingReleasesProvider()) {
            return true
        }
        val releaseMs = lastAudioTrackReleasedElapsedMs
        if (releaseMs != C.TIME_UNSET &&
            (nowMs - releaseMs).coerceAtLeast(0L) < POST_RELEASE_HAL_TEARDOWN_MS
        ) {
            return true
        }
        if (elapsedSinceFlushMs < MIN_DOLBY_FLUSH_REINIT_DELAY_MS) {
            return true
        }
        awaitingPostFlushTrackRelease = false
        return false
    }

    private fun resetPrimingAndClockState(preserveResetPosition: Boolean) {
        buffersHandledSinceReset = 0
        accessUnitsHandledSinceReset = 0
        sinkFullOnHandleBuffer = false
        firstBufferPresentationTimeUs = C.TIME_UNSET
        lastHandledPresentationTimeUs = C.TIME_UNSET
        if (!preserveResetPosition) {
            rendererResetPositionUs = C.TIME_UNSET
        }
        playStartElapsedRealtimeMs = C.TIME_UNSET
        postResetClockSmoothingActive = true
        lastSmoothedPositionUs = C.TIME_UNSET
    }

    internal fun isDolbyAtmosOrSurroundFormat(): Boolean {
        val format = upstreamTrackFormat ?: currentInputFormat ?: return false
        return isDolbyFormat(format)
    }

    private fun isDolbyFormat(format: Format): Boolean {
        val mimeType = format.sampleMimeType
        if (mimeType != null && (
                mimeType == MimeTypes.AUDIO_E_AC3 ||
                    mimeType == MimeTypes.AUDIO_E_AC3_JOC ||
                    mimeType == MimeTypes.AUDIO_AC3 ||
                    mimeType == MimeTypes.AUDIO_AC4 ||
                    mimeType == MimeTypes.AUDIO_TRUEHD
                )
        ) {
            return true
        }
        val codecs = format.codecs
        if (codecs != null) {
            return codecs.contains("ec-3", ignoreCase = true) ||
                codecs.contains("ac-3", ignoreCase = true) ||
                codecs.contains("ac-4", ignoreCase = true) ||
                codecs.contains("truehd", ignoreCase = true) ||
                codecs.contains("mlp", ignoreCase = true)
        }
        return false
    }

    private fun shouldRejectDirectPlayback(format: Format): Boolean {
        if (!isEncodedPassthroughCandidate(format)) {
            return false
        }
        // Bluetooth: always decode to PCM (Media3 DEFAULT_AUDIO_CAPABILITIES policy).
        if (bluetoothForcePcm || forcePcmForCurrentSession) {
            return true
        }
        // Non-1x speed cannot be applied to bitstream passthrough tracks.
        return playbackSpeed != 1f
    }

    private fun markPcmFallbackIfNeeded(format: Format?, speed: Float): Boolean {
        if (format == null || !isEncodedPassthroughCandidate(format)) {
            return false
        }
        if (bluetoothForcePcm) {
            val wasForcingPcm = forcePcmForCurrentSession
            forcePcmForCurrentSession = true
            return !wasForcingPcm
        }
        if (speed == 1f) {
            return false
        }
        val wasForcingPcm = forcePcmForCurrentSession
        forcePcmForCurrentSession = true
        return !wasForcingPcm
    }

    private fun normalizeSpeed(speed: Float): Float {
        return speed.takeIf { it > 0f } ?: 1f
    }

    /**
     * Formats that devices may try to play via passthrough/offload and that Bluetooth cannot carry.
     * Matches Media3 surround encodings that need decode-to-PCM on A2DP/LE Audio.
     */
    private fun isEncodedPassthroughCandidate(format: Format): Boolean {
        val mimeType = format.sampleMimeType
        if (mimeType != null && (
                mimeType == MimeTypes.AUDIO_E_AC3 ||
                    mimeType == MimeTypes.AUDIO_E_AC3_JOC ||
                    mimeType == MimeTypes.AUDIO_AC3 ||
                    mimeType == MimeTypes.AUDIO_AC4 ||
                    mimeType == MimeTypes.AUDIO_TRUEHD ||
                    mimeType == MimeTypes.AUDIO_DTS ||
                    mimeType == MimeTypes.AUDIO_DTS_HD ||
                    mimeType == MimeTypes.AUDIO_DTS_EXPRESS ||
                    mimeType.startsWith("audio/vnd.dts")
                )
        ) {
            return true
        }
        val codecs = format.codecs
        if (codecs != null) {
            return codecs.contains("ac-3", ignoreCase = true) ||
                codecs.contains("ac-4", ignoreCase = true) ||
                codecs.contains("ec-3", ignoreCase = true) ||
                codecs.contains("dts", ignoreCase = true) ||
                codecs.contains("truehd", ignoreCase = true) ||
                codecs.contains("dtshd", ignoreCase = true)
        }
        return false
    }

    private companion object {
        // 3 E-AC3-JOC syncframes = 3 * 32ms = 96ms (~2.3 video frames at 24fps), matching
        // the Dolby JOC decoder lookahead and HDMI MAT / MS12 HAL initial burst depth.
        const val MIN_DOLBY_PRIME_ACCESS_UNITS = 3
        const val CLOCK_SMOOTHING_WINDOW_MS = 1_000L
        const val MAX_SMOOTHABLE_DRIFT_US = 300_000L
        const val INITIAL_CLOCK_UNSTICK_US = 1_000L
        const val MIN_UNSTICK_DELAY_MS = 4L
        const val MAX_SYNTHETIC_ADVANCE_US = 2_000L

        // Media3 DefaultAudioSink.flush() schedules AudioTrack.flush() + release() on
        // ExoPlayer:AudioTrackReleaseThread with a 20ms delay (~55-80ms total to ~AudioTrack),
        // followed by hardware offload HAL close-async teardown (~65-100ms in rtk_audio_offload).
        const val MIN_DOLBY_FLUSH_REINIT_DELAY_MS = 175L
        const val POST_RELEASE_HAL_TEARDOWN_MS = 85L
        const val MAX_DOLBY_FLUSH_REINIT_WAIT_MS = 230L

        private val hasPendingReleasesMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
            runCatching {
                DefaultAudioSink::class.java
                    .getDeclaredMethod("hasPendingAudioTrackReleases")
                    .apply { isAccessible = true }
            }.getOrNull()
        }

        fun queryDefaultAudioSinkPendingReleases(): Boolean {
            return runCatching {
                hasPendingReleasesMethod?.invoke(null) as? Boolean ?: false
            }.getOrDefault(false)
        }
    }
}
