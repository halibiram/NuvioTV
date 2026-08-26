package com.nuvio.tv.ui.screens.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.extractor.ExtractorsFactory
import io.github.peerless2012.ass.media.type.AssRenderType

@OptIn(UnstableApi::class)
internal data class ContentExoPlayerBuildSpec(
    val headers: Map<String, String>,
    val bandwidthMeter: BandwidthMeter,
    val trackSelector: DefaultTrackSelector,
    val loadControl: LoadControl,
    val renderersFactory: RenderersFactory,
    val extractorsFactory: ExtractorsFactory,
    val mediaSourceFactory: PlayerMediaSourceFactory,
    val useLibass: Boolean,
    val libassRenderType: AssRenderType = AssRenderType.CUES
)

@OptIn(UnstableApi::class)
internal object ContentExoPlayerFactory {
    const val PLAYER_RELEASE_TIMEOUT_MS = 3000L

    fun build(context: Context, spec: ContentExoPlayerBuildSpec): ExoPlayer {
        val playerDataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(context, spec.headers)
        spec.mediaSourceFactory.configureSubtitleParsing(
            extractorsFactory = spec.extractorsFactory,
            subtitleParserFactory = null
        )
        val builder = ExoPlayer.Builder(context)
            .setBandwidthMeter(spec.bandwidthMeter)
            .setTrackSelector(spec.trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playerDataSourceFactory, spec.extractorsFactory))
            .setLoadControl(spec.loadControl)
            .setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        return if (spec.useLibass) {
            builder.buildWithAssSupportCompat(
                context = context,
                renderType = spec.libassRenderType,
                playerMediaSourceFactory = spec.mediaSourceFactory,
                dataSourceFactory = playerDataSourceFactory,
                extractorsFactory = spec.extractorsFactory,
                renderersFactory = spec.renderersFactory
            )
        } else {
            builder
                .setRenderersFactory(spec.renderersFactory)
                .build()
        }
    }
}
