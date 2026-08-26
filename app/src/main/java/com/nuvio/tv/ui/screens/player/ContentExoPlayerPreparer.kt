package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import com.nuvio.tv.core.player.DolbyVisionExtractorsFactory
import com.nuvio.tv.core.player.PlaybackPrewarmEngineSnapshot
import com.nuvio.tv.core.player.PlaybackPrewarmMediaRequest
import com.nuvio.tv.core.player.PrewarmedPlayerFactory
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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
            try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                player.setAudioAttributes(audioAttributes, true)
                val mediaSource = mediaSourceFactory.createMediaSource(
                    context = context,
                    url = url,
                    headers = request.headers,
                    filename = request.filename,
                    mimeTypeOverride = mimeType
                )
                val resume = request.resumePositionMs.coerceAtLeast(0L)
                if (resume > 0L) {
                    player.setMediaSource(mediaSource, resume)
                } else {
                    player.setMediaSource(mediaSource)
                }
                player.playWhenReady = false
                Log.i(TAG, "Built prewarm ExoPlayer host=${safeHost(url)} resumeMs=$resume mime=$mimeType")
                PlaybackPrewarmEngineSnapshot(
                    player = player,
                    mimeType = mimeType,
                    trackSelector = trackSelector,
                    useLibass = settings.useLibass,
                    resumePositionMs = resume,
                    prepareStartedAtElapsedMs = SystemClock.elapsedRealtime()
                )
            } catch (error: Throwable) {
                runCatching { player.release() }
                throw error
            }
        }
    }

    override fun release(snapshot: PlaybackPrewarmEngineSnapshot) {
        val player = snapshot.player
        val release = {
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

    private fun safeHost(url: String): String {
        return runCatching { android.net.Uri.parse(url).host ?: "unknown" }.getOrDefault("unknown")
    }

    private companion object {
        const val TAG = "ContentExoPreparer"
        const val PREWARM_INITIAL_BITRATE_ESTIMATE_BPS = 25_000_000L
    }
}
