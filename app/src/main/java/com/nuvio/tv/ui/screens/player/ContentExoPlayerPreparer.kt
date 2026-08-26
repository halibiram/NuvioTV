package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRendererCapabilitiesList
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.PreloadMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import com.nuvio.tv.core.player.DolbyVisionExtractorsFactory
import com.nuvio.tv.core.player.DurationLimitedPreloadControl
import com.nuvio.tv.core.player.PlaybackPrewarmEngineSnapshot
import com.nuvio.tv.core.player.PlaybackPrewarmMediaRequest
import com.nuvio.tv.core.player.PlaybackPrewarmPreloadPolicy
import com.nuvio.tv.core.player.PrewarmedPlayerFactory
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Constructs a claimable ExoPlayer and prefetches the start of the stream into
 * PreloadMediaSource sample queues. ExoPlayer.prepare() is deferred until
 * PlayerScreen has a surface so Fire TV does not hold a second HW decoder.
 */
@OptIn(UnstableApi::class)
@Singleton
class ContentExoPlayerPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerSettingsDataStore: PlayerSettingsDataStore
) : PrewarmedPlayerFactory {

    override suspend fun prepare(request: PlaybackPrewarmMediaRequest): PlaybackPrewarmEngineSnapshot? {
        val settings = playerSettingsDataStore.playerSettings.first()
        if (settings.internalPlayerEngine != InternalPlayerEngine.EXOPLAYER) return null
        val url = request.url
        if (url.isBlank()) return null
        val mimeType = PlayerMediaSourceFactory.probeMimeType(
            url = url,
            headers = request.headers,
            filename = request.filename
        )
        return withContext(Dispatchers.Main) {
            NuvioExoPlayerPerformanceHelper.updateSettings(settings, context)
            NuvioExoPlayerPerformanceHelper.enabled = settings.nuvioPerformanceModeEnabled
            val bandwidthMeter = if (NuvioExoPlayerPerformanceHelper.enabled) {
                NuvioExoPlayerPerformanceHelper.buildBandwidthMeter(context)
            } else {
                DefaultBandwidthMeter.Builder(context)
                    .setInitialBitrateEstimate(PREWARM_INITIAL_BITRATE_ESTIMATE_BPS)
                    .build()
            }
            val loadControl = if (settings.nuvioPerformanceModeEnabled) {
                NuvioExoPlayerPerformanceHelper.buildLoadControl(context)
            } else {
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBackBuffer(1_500, true)
                    .build()
            }
            val trackSelector = DefaultTrackSelector(context).apply {
                if (settings.effectiveTunnelingEnabled) {
                    setParameters(buildUponParameters().setTunnelingEnabled(true))
                }
            }
            val extractorsFactory = DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
            val effectiveExtractorsFactory = DolbyVisionExtractorsFactory(
                delegate = extractorsFactory,
                config = DolbyVisionConversionConfig(active = false),
                stripDvRpu = false,
                stripHdr10PlusSei = settings.stripHdr10PlusSei
            )
            val renderersFactory = DefaultRenderersFactory(context)
                .setExtensionRendererMode(settings.decoderPriority)
                .setEnableDecoderFallback(true)
            val mediaSourceFactory = PlayerMediaSourceFactory(context.applicationContext)
            if (settings.bufferEngineEnabled) {
                mediaSourceFactory.vodCacheEnabled = settings.vodCacheEnabled
            } else {
                mediaSourceFactory.vodCacheEnabled = false
            }
            if (settings.parallelNetworkEnabled) {
                mediaSourceFactory.useParallelConnections = settings.useParallelConnections
                mediaSourceFactory.parallelConnectionCount = settings.parallelConnectionCount
                mediaSourceFactory.parallelChunkSizeKb = settings.parallelChunkSizeKb
                mediaSourceFactory.nuvioPerformanceModeEnabled = settings.nuvioPerformanceModeEnabled
            } else {
                mediaSourceFactory.useParallelConnections = false
                mediaSourceFactory.nuvioPerformanceModeEnabled = false
            }
            val player = ContentExoPlayerFactory.build(
                context = context,
                spec = ContentExoPlayerBuildSpec(
                    headers = request.headers,
                    bandwidthMeter = bandwidthMeter,
                    trackSelector = trackSelector,
                    loadControl = loadControl,
                    renderersFactory = renderersFactory,
                    extractorsFactory = effectiveExtractorsFactory,
                    mediaSourceFactory = mediaSourceFactory,
                    useLibass = settings.useLibass,
                    libassRenderType = settings.libassRenderType.toAssRenderType()
                )
            )
            var preloadMediaSource: PreloadMediaSource? = null
            try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                player.setAudioAttributes(audioAttributes, true)
                val innerSource = mediaSourceFactory.createMediaSource(
                    context = context,
                    url = url,
                    headers = request.headers,
                    filename = request.filename,
                    mimeTypeOverride = mimeType
                )
                preloadMediaSource = wrapPreloadMediaSource(
                    innerSource = innerSource,
                    trackSelector = trackSelector,
                    bandwidthMeter = bandwidthMeter,
                    renderersFactory = renderersFactory,
                    loadControl = loadControl,
                    playbackLooper = player.playbackLooper
                )
                val mediaSource = preloadMediaSource ?: innerSource
                val resume = request.resumePositionMs.coerceAtLeast(0L)
                if (resume > 0L) {
                    player.setMediaSource(mediaSource, resume)
                } else {
                    player.setMediaSource(mediaSource)
                }
                player.playWhenReady = false
                preloadMediaSource?.preload(PlaybackPrewarmPreloadPolicy.startPositionUs(resume))
                PlaybackPrewarmEngineSnapshot(
                    player = player,
                    mimeType = mimeType,
                    trackSelector = trackSelector,
                    useLibass = settings.useLibass,
                    resumePositionMs = resume,
                    prepareStartedAtElapsedMs = SystemClock.elapsedRealtime(),
                    preloadMediaSource = preloadMediaSource
                )
            } catch (error: Throwable) {
                preloadMediaSource?.releasePreloadMediaSource()
                runCatching { player.release() }
                throw error
            }
        }
    }

    override fun release(snapshot: PlaybackPrewarmEngineSnapshot) {
        val player = snapshot.player
        val preloadMediaSource = snapshot.preloadMediaSource
        val release = {
            runCatching { preloadMediaSource?.releasePreloadMediaSource() }
            runCatching { player.playWhenReady = false }
            runCatching { player.stop() }
            runCatching { player.clearMediaItems() }
            runCatching { player.clearVideoSurface() }
            runCatching { player.release() }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            release()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post { release() }
        }
    }

    private fun wrapPreloadMediaSource(
        innerSource: MediaSource,
        trackSelector: TrackSelector,
        bandwidthMeter: BandwidthMeter,
        renderersFactory: RenderersFactory,
        loadControl: LoadControl,
        playbackLooper: android.os.Looper
    ): PreloadMediaSource? {
        if (!PlaybackPrewarmPreloadPolicy.canAttachPreload(playbackLooper)) return null
        val capabilitiesList = DefaultRendererCapabilitiesList.Factory(renderersFactory)
            .createRendererCapabilitiesList()
        return try {
            val factory = PreloadMediaSource.Factory(
                DefaultMediaSourceFactory(context),
                DurationLimitedPreloadControl(),
                trackSelector,
                bandwidthMeter,
                capabilitiesList.rendererCapabilities,
                loadControl.allocator,
                playbackLooper
            )
            factory.createMediaSource(innerSource)
        } catch (error: RuntimeException) {
            Log.w(TAG, "PreloadMediaSource wrap failed", error)
            null
        } finally {
            capabilitiesList.release()
        }
    }

    private companion object {
        const val TAG = "ContentExoPreparer"
        const val PREWARM_INITIAL_BITRATE_ESTIMATE_BPS = 25_000_000L
    }
}
