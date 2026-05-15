package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.NuvioEngineConfig
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * Centralizes all Nuvio ExoPlayer performance enhancements behind a single toggle.
 *
 * When [enabled] is `true`, the helper applies:
 * - Large allocator segments (256 KB) with a 400 MB target buffer
 * - Extended buffer durations (200–280 s) with a 12 s back-buffer
 * - 50 Mbps initial bandwidth estimate
 * - Async MediaCodec queueing on API 31+
 * - Scrubbing mode for faster seeks (disables audio/metadata, boosts codec rate)
 * - In-buffer seek detection to suppress transient buffering UI
 * - HTTP/2 with an 8-connection pool for networking
 *
 * When [enabled] is `false`, stock ExoPlayer defaults are used everywhere.
 */
@androidx.media3.common.util.UnstableApi
object NuvioExoPlayerPerformanceHelper {

    /** Whether Nuvio performance enhancements are active. Set from [PlayerSettingsDataStore]. */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            applyEngineConfig(value)
        }

    // ─── Constants ────────────────────────────────────────────────────────────
    private const val NUVIO_ALLOCATOR_SEGMENT_SIZE = 256 * 1024        // 256 KB
    private const val NUVIO_TARGET_BUFFER_BYTES = 400 * 1024 * 1024    // 400 MB
    private const val NUVIO_MIN_BUFFER_MS = 200_000
    private const val NUVIO_MAX_BUFFER_MS = 280_000
    private const val NUVIO_BACK_BUFFER_MS = 12_000
    private const val NUVIO_INITIAL_BITRATE_ESTIMATE = 50_000_000L     // 50 Mbps

    private const val SEEK_BACK_BUFFER_THRESHOLD_MS = 10_000L
    private const val SEEK_BACKWARD_TOLERANCE_MS = 2_000L
    const val SEEK_SUPPRESS_TIMEOUT_MS = 800L

    // ─── LoadControl ──────────────────────────────────────────────────────────

    /**
     * Builds a [DefaultLoadControl] tuned for Nuvio performance when enabled,
     * or a standard ExoPlayer [DefaultLoadControl] when disabled.
     */
    fun buildLoadControl(): DefaultLoadControl {
        return if (enabled) {
            DefaultLoadControl.Builder()
                .setAllocator(DefaultAllocator(true, NUVIO_ALLOCATOR_SEGMENT_SIZE))
                .setTargetBufferBytes(NUVIO_TARGET_BUFFER_BYTES)
                .setBufferDurationsMs(
                    NUVIO_MIN_BUFFER_MS,
                    NUVIO_MAX_BUFFER_MS,
                    1_500,
                    1_500
                )
                .setBackBuffer(NUVIO_BACK_BUFFER_MS, true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setTargetBufferBytes(100 * 1024 * 1024)
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    70_000,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    5_000
                )
                .build()
        }
    }

    // ─── BandwidthMeter ───────────────────────────────────────────────────────

    /**
     * Builds a [DefaultBandwidthMeter] with an aggressive initial estimate when
     * enabled, or the platform default when disabled.
     */
    fun buildBandwidthMeter(context: Context): DefaultBandwidthMeter {
        return if (enabled) {
            DefaultBandwidthMeter.Builder(context)
                .setInitialBitrateEstimate(NUVIO_INITIAL_BITRATE_ESTIMATE)
                .build()
        } else {
            DefaultBandwidthMeter.Builder(context).build()
        }
    }

    // ─── RenderersFactory ─────────────────────────────────────────────────────

    /**
     * Enables async MediaCodec queueing on the given [factory] when performance
     * mode is on and the device runs API 31+. No-op otherwise.
     */
    fun applyAsyncQueueing(factory: DefaultRenderersFactory) {
        if (enabled && Build.VERSION.SDK_INT >= 31) {
            factory.forceEnableMediaCodecAsynchronousQueueing()
        }
    }

    // ─── Seek / Scrubbing ─────────────────────────────────────────────────────

    /**
     * Returns [ScrubbingModeParameters] that disable audio/metadata decoding and
     * boost codec operating rate for the fastest possible seek, or `null` when
     * performance mode is off.
     */
    fun buildScrubbingParams(): ScrubbingModeParameters? {
        if (!enabled) return null
        return ScrubbingModeParameters.Builder()
            .setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA))
            .setShouldIncreaseCodecOperatingRate(true)
            .setAllowSkippingMediaCodecFlush(true)
            .setShouldEnableDynamicScheduling(true)
            .build()
    }

    /**
     * Returns `true` when the seek target [positionMs] falls within the player's
     * already-buffered window (forward into [Player.getBufferedPosition] or
     * backward into the retained back-buffer).
     *
     * Only meaningful when performance mode is enabled; returns `false` otherwise.
     */
    fun isSeekInBuffer(player: ExoPlayer, positionMs: Long): Boolean {
        if (!enabled) return false
        val bufferedPos = player.bufferedPosition
        val currentPos = player.currentPosition
        val backBufferStart = (currentPos - SEEK_BACK_BUFFER_THRESHOLD_MS - SEEK_BACKWARD_TOLERANCE_MS)
            .coerceAtLeast(0L)
        return positionMs in backBufferStart..bufferedPos
    }

    // ─── Buffering UI ─────────────────────────────────────────────────────────

    /**
     * Determines whether transient buffering UI should be suppressed during a
     * seek operation. Returns `false` when performance mode is disabled so that
     * the stock buffering indicator always shows.
     *
     * @param suppressBufferingUiForSeek  Flag set when an in-buffer seek is active.
     * @param seekBufferingUiDeferred     Flag set during the 1 s grace window.
     * @param isBuffering                 Current [Player.STATE_BUFFERING] state.
     */
    fun shouldSuppressBufferingUi(
        suppressBufferingUiForSeek: Boolean,
        seekBufferingUiDeferred: Boolean,
        isBuffering: Boolean
    ): Boolean {
        if (!enabled) return false
        return (suppressBufferingUiForSeek && isBuffering) ||
            (seekBufferingUiDeferred && isBuffering)
    }

    // ─── Networking ───────────────────────────────────────────────────────────

    /**
     * Applies HTTP/2 and an 8-connection pool to the given [builder] when
     * performance mode is enabled. No-op otherwise.
     */
    fun applyNetworkOptimizations(builder: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder {
        if (!enabled) return builder
        return builder
            .connectionPool(okhttp3.ConnectionPool(8, 30, java.util.concurrent.TimeUnit.SECONDS))
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
    }

    // ─── Audio Renderer ───────────────────────────────────────────────────────

    /**
     * Returns `true` when the audio renderer should bypass the codec for a
     * non-PCM format that the sink supports directly. Only active when
     * performance mode is enabled.
     */
    fun shouldBypassForNonPcmFormat(): Boolean {
        return enabled
    }

    // ─── Memory Logging ───────────────────────────────────────────────────────

    /**
     * Returns `true` when off-heap allocator memory logging should be active.
     */
    fun shouldLogMemoryFootprint(): Boolean {
        return enabled
    }

    // ─── Track Rebuild Guard ──────────────────────────────────────────────────

    /**
     * Returns `true` when track selection rebuild should be skipped after seeks
     * (only allow on first ready). When disabled, always rebuilds (stock behaviour).
     */
    fun shouldGuardTrackRebuild(): Boolean {
        return enabled
    }

    // ─── Engine Config ───────────────────────────────────────────────────────

    /**
     * Applies [NuvioEngineConfig] based on the toggle state.
     * When enabled: native off-heap allocation + zero-copy ByteBuffer pipeline + 64 KB scratch.
     * When disabled: stock heap allocation + standard byte[] pipeline + 4 KB scratch.
     *
     * Must be called **before** building an ExoPlayer instance.
     */
    private fun applyEngineConfig(performanceModeEnabled: Boolean) {
        if (performanceModeEnabled) {
            NuvioEngineConfig.set(NuvioEngineConfig.nuvioMode())
        } else {
            NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
        }
    }
}
