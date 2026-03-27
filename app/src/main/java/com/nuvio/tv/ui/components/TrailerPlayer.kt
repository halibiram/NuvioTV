package com.nuvio.tv.ui.components

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.tv.R
import com.nuvio.tv.data.trailer.YoutubeChunkedDataSourceFactory
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

private val TRAILER_YOUTUBE_VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
private const val TRAILER_YOUTUBE_STARTUP_UNMUTE_DELAY_MS = 200L

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrailerPlayer(
    trailerUrl: String?,
    trailerAudioUrl: String? = null,
    isPlaying: Boolean,
    onEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit = {},
    muted: Boolean = false,
    seekRequestToken: Int = 0,
    seekDeltaMs: Long = 0L,
    onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onRemoteKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean = { _, _, _ -> false },
    cropToFill: Boolean = false,
    overscanZoom: Float = 1f,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(animationSpec = tween(800)),
    exit: ExitTransition = fadeOut(animationSpec = tween(500))
) {
    val youtubeVideoId = remember(trailerUrl, trailerAudioUrl) {
        if (trailerAudioUrl.isNullOrBlank()) trailerUrl?.let(::extractYouTubeVideoIdForFallback) else null
    }

    if (youtubeVideoId != null) {
        YouTubeFallbackTrailerPlayer(
            youtubeVideoId = youtubeVideoId,
            isPlaying = isPlaying,
            onEnded = onEnded,
            onFirstFrameRendered = onFirstFrameRendered,
            muted = muted,
            seekRequestToken = seekRequestToken,
            seekDeltaMs = seekDeltaMs,
            onProgressChanged = onProgressChanged,
            onRemoteKey = onRemoteKey,
            cropToFill = cropToFill,
            overscanZoom = overscanZoom,
            modifier = modifier,
            enter = enter,
            exit = exit
        )
    } else {
        ExoTrailerPlayer(
            trailerUrl = trailerUrl,
            trailerAudioUrl = trailerAudioUrl,
            isPlaying = isPlaying,
            onEnded = onEnded,
            onFirstFrameRendered = onFirstFrameRendered,
            muted = muted,
            seekRequestToken = seekRequestToken,
            seekDeltaMs = seekDeltaMs,
            onProgressChanged = onProgressChanged,
            onRemoteKey = onRemoteKey,
            cropToFill = cropToFill,
            overscanZoom = overscanZoom,
            modifier = modifier,
            enter = enter,
            exit = exit
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ExoTrailerPlayer(
    trailerUrl: String?,
    trailerAudioUrl: String? = null,
    isPlaying: Boolean,
    onEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit = {},
    muted: Boolean = false,
    seekRequestToken: Int = 0,
    seekDeltaMs: Long = 0L,
    onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onRemoteKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean = { _, _, _ -> false },
    cropToFill: Boolean = false,
    overscanZoom: Float = 1f,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(animationSpec = tween(800)),
    exit: ExitTransition = fadeOut(animationSpec = tween(500))
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activityLifecycleOwner = remember(context) { context as? androidx.lifecycle.LifecycleOwner ?: lifecycleOwner }
    val currentIsPlaying by rememberUpdatedState(isPlaying)
    val currentTrailerUrl by rememberUpdatedState(trailerUrl)
    val currentTrailerAudioUrl by rememberUpdatedState(trailerAudioUrl)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnFirstFrameRendered by rememberUpdatedState(onFirstFrameRendered)
    val currentOnProgressChanged by rememberUpdatedState(onProgressChanged)
    val currentOnRemoteKey by rememberUpdatedState(onRemoteKey)
    val zoomScale = if (cropToFill) overscanZoom.coerceAtLeast(1f) else 1f
    var hasRenderedFirstFrame by remember(trailerUrl) { mutableStateOf(false) }
    val playerAlphaState = animateFloatAsState(
        targetValue = if (isPlaying && hasRenderedFirstFrame) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "trailerFirstFrameAlpha"
    )

    val trailerPlayer = remember(trailerUrl, trailerAudioUrl) {
        if (trailerUrl != null) {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 30_000,
                    /* maxBufferMs = */ 120_000,
                    /* bufferForPlaybackMs = */ 5_000,
                    /* bufferForPlaybackAfterRebufferMs = */ 10_000
                )
                .build()
            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                .build()
                .apply {
                    repeatMode = Player.REPEAT_MODE_OFF
                    volume = if (muted) 0f else 1f
                    videoScalingMode = if (cropToFill) {
                        C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    } else {
                        C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                    }
                }
        } else {
            null
        }
    }
    val releaseCalled = remember(trailerPlayer) { AtomicBoolean(false) }

    LaunchedEffect(isPlaying, trailerUrl, trailerAudioUrl, muted) {
        val player = trailerPlayer ?: return@LaunchedEffect
        player.volume = if (muted) 0f else 1f
        if (isPlaying && trailerUrl != null) {
            hasRenderedFirstFrame = false
            if (!trailerAudioUrl.isNullOrBlank()) {
                val mediaSourceFactory = DefaultMediaSourceFactory(YoutubeChunkedDataSourceFactory())
                val videoSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(trailerUrl))
                val audioSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(trailerAudioUrl))
                player.setMediaSource(MergingMediaSource(videoSource, audioSource))
            } else {
                player.setMediaItem(MediaItem.fromUri(trailerUrl))
            }
            player.prepare()
            player.playWhenReady = true
        } else {
            hasRenderedFirstFrame = false
            player.stop()
            player.clearMediaItems()
        }
    }

    LaunchedEffect(trailerPlayer, cropToFill) {
        val player = trailerPlayer ?: return@LaunchedEffect
        player.videoScalingMode = if (cropToFill) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }

    LaunchedEffect(seekRequestToken, seekDeltaMs, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (seekRequestToken <= 0) return@LaunchedEffect
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val current = player.currentPosition
        val target = (current + seekDeltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        player.seekTo(target)
    }

    LaunchedEffect(trailerPlayer, isPlaying) {
        val player = trailerPlayer ?: return@LaunchedEffect
        while (isPlaying) {
            val position = player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            currentOnProgressChanged(position, duration)
            delay(250)
        }
        currentOnProgressChanged(0L, 0L)
    }

    DisposableEffect(activityLifecycleOwner, trailerPlayer) {
        val player = trailerPlayer ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    currentOnEnded()
                }
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
                currentOnFirstFrameRendered()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (currentIsPlaying && !currentTrailerUrl.isNullOrBlank()) {
                        if (player.currentMediaItem == null) {
                            if (!currentTrailerAudioUrl.isNullOrBlank()) {
                                val mediaSourceFactory = DefaultMediaSourceFactory(YoutubeChunkedDataSourceFactory())
                                val videoSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(currentTrailerUrl!!))
                                val audioSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(currentTrailerAudioUrl!!))
                                player.setMediaSource(MergingMediaSource(videoSource, audioSource))
                            } else {
                                player.setMediaItem(MediaItem.fromUri(currentTrailerUrl!!))
                            }
                            player.prepare()
                        }
                        player.playWhenReady = true
                    }
                }

                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    player.playWhenReady = false
                    player.pause()
                    player.stop()
                    player.clearMediaItems()
                }

                Lifecycle.Event.ON_DESTROY -> {
                    if (releaseCalled.compareAndSet(false, true)) {
                        runCatching { player.stop() }
                        runCatching { player.clearMediaItems() }
                        runCatching { player.release() }
                    }
                }

                else -> Unit
            }
        }
        player.addListener(listener)
        activityLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            runCatching { activityLifecycleOwner.lifecycle.removeObserver(observer) }
            runCatching { player.removeListener(listener) }
            if (releaseCalled.compareAndSet(false, true)) {
                runCatching { player.stop() }
                runCatching { player.clearMediaItems() }
                runCatching { player.release() }
            }
        }
    }

    if (trailerPlayer != null) {
        AnimatedVisibility(
            visible = isPlaying,
            enter = enter,
            exit = exit
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.trailer_player_view, null) as PlayerView).apply {
                        player = trailerPlayer
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setOnKeyListener { _, keyCode, event ->
                            currentOnRemoteKey(keyCode, event.action, event.repeatCount)
                        }
                        keepScreenOn = true
                        resizeMode = if (cropToFill) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        } else {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }
                },
                update = { view ->
                    view.resizeMode = if (cropToFill) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = modifier
                    .clipToBounds()
                    .graphicsLayer {
                        alpha = playerAlphaState.value
                        scaleX = zoomScale
                        scaleY = zoomScale
                    }
            )
        }
    }
}

@Composable
private fun YouTubeFallbackTrailerPlayer(
    youtubeVideoId: String,
    isPlaying: Boolean,
    onEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit = {},
    muted: Boolean = false,
    seekRequestToken: Int = 0,
    seekDeltaMs: Long = 0L,
    onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onRemoteKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean = { _, _, _ -> false },
    cropToFill: Boolean = false,
    overscanZoom: Float = 1f,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(animationSpec = tween(800)),
    exit: ExitTransition = fadeOut(animationSpec = tween(500))
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activityLifecycleOwner = remember(context) { context as? androidx.lifecycle.LifecycleOwner ?: lifecycleOwner }
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnFirstFrameRendered by rememberUpdatedState(onFirstFrameRendered)
    val currentOnProgressChanged by rememberUpdatedState(onProgressChanged)
    val currentOnRemoteKey by rememberUpdatedState(onRemoteKey)
    val zoomScale = if (cropToFill) overscanZoom.coerceAtLeast(1f) else 1f
    var hasRenderedFirstFrame by remember(youtubeVideoId) { mutableStateOf(false) }
    var youtubePlayer by remember(youtubeVideoId) { mutableStateOf<YouTubePlayer?>(null) }
    var currentSecond by remember(youtubeVideoId) { mutableFloatStateOf(0f) }
    var durationSeconds by remember(youtubeVideoId) { mutableFloatStateOf(0f) }
    val playerAlphaState = animateFloatAsState(
        targetValue = if (isPlaying && hasRenderedFirstFrame) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "trailerYouTubeFirstFrameAlpha"
    )

    val youTubePlayerView = remember(youtubeVideoId) {
        val options = IFramePlayerOptions.Builder(context)
            .controls(0)
            .fullscreen(0)
            .rel(0)
            .ivLoadPolicy(3)
            .ccLoadPolicy(1)
            .build()

        YouTubePlayerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            enableAutomaticInitialization = false
            isFocusable = true
            isFocusableInTouchMode = true
            keepScreenOn = true
            setOnKeyListener { _, keyCode, event ->
                currentOnRemoteKey(keyCode, event.action, event.repeatCount)
            }
            initialize(
                object : AbstractYouTubePlayerListener() {
                    override fun onReady(player: YouTubePlayer) {
                        youtubePlayer = player
                        runCatching { player.mute() }
                    }

                    override fun onStateChange(player: YouTubePlayer, state: PlayerConstants.PlayerState) {
                        when (state) {
                            PlayerConstants.PlayerState.ENDED -> currentOnEnded()
                            else -> Unit
                        }
                    }

                    override fun onCurrentSecond(player: YouTubePlayer, second: Float) {
                        currentSecond = second
                        if (!hasRenderedFirstFrame) {
                            hasRenderedFirstFrame = true
                            currentOnFirstFrameRendered()
                        }
                        currentOnProgressChanged(
                            (second * 1_000f).roundToLong(),
                            (durationSeconds * 1_000f).roundToLong()
                        )
                    }

                    override fun onVideoDuration(player: YouTubePlayer, duration: Float) {
                        durationSeconds = duration
                        currentOnProgressChanged(
                            (currentSecond * 1_000f).roundToLong(),
                            (duration * 1_000f).roundToLong()
                        )
                    }
                },
                true,
                options
            )
        }
    }

    DisposableEffect(activityLifecycleOwner, youTubePlayerView) {
        activityLifecycleOwner.lifecycle.addObserver(youTubePlayerView)
        onDispose {
            runCatching { activityLifecycleOwner.lifecycle.removeObserver(youTubePlayerView) }
            runCatching { youTubePlayerView.release() }
        }
    }

    LaunchedEffect(youtubePlayer, youtubeVideoId, isPlaying) {
        val player = youtubePlayer ?: return@LaunchedEffect
        if (isPlaying) {
            hasRenderedFirstFrame = false
            currentSecond = 0f
            durationSeconds = 0f
            currentOnProgressChanged(0L, 0L)
            runCatching { player.mute() }
            player.loadVideo(youtubeVideoId, 0f)
        } else {
            hasRenderedFirstFrame = false
            currentSecond = 0f
            durationSeconds = 0f
            currentOnProgressChanged(0L, 0L)
            runCatching { player.pause() }
            runCatching { player.seekTo(0f) }
        }
    }

    LaunchedEffect(youtubePlayer, isPlaying, muted, hasRenderedFirstFrame) {
        val player = youtubePlayer ?: return@LaunchedEffect
        if (!isPlaying || muted) {
            runCatching { player.mute() }
            return@LaunchedEffect
        }

        runCatching { player.mute() }
        if (!hasRenderedFirstFrame) return@LaunchedEffect

        delay(TRAILER_YOUTUBE_STARTUP_UNMUTE_DELAY_MS)
        runCatching { player.unMute() }
    }

    LaunchedEffect(seekRequestToken, seekDeltaMs, youtubePlayer, currentSecond, durationSeconds) {
        val player = youtubePlayer ?: return@LaunchedEffect
        if (seekRequestToken <= 0) return@LaunchedEffect
        val durationMs = (durationSeconds * 1_000f).roundToLong().coerceAtLeast(0L)
        val currentMs = (currentSecond * 1_000f).roundToLong().coerceAtLeast(0L)
        val targetMs = (currentMs + seekDeltaMs).coerceIn(0L, durationMs)
        player.seekTo(targetMs / 1_000f)
    }

    AnimatedVisibility(
        visible = isPlaying,
        enter = enter,
        exit = exit
    ) {
        AndroidView(
            factory = { youTubePlayerView },
            update = { view ->
                view.keepScreenOn = isPlaying
                view.setOnKeyListener { _, keyCode, event ->
                    currentOnRemoteKey(keyCode, event.action, event.repeatCount)
                }
            },
            modifier = modifier
                .clipToBounds()
                .graphicsLayer {
                    alpha = playerAlphaState.value
                    scaleX = zoomScale
                    scaleY = zoomScale
                }
        )
    }
}

private fun extractYouTubeVideoIdForFallback(input: String): String? {
    val trimmed = input.trim()
    if (TRAILER_YOUTUBE_VIDEO_ID_REGEX.matches(trimmed)) return trimmed

    return runCatching {
        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val uri = Uri.parse(normalized)
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return@runCatching null

        when {
            host == "youtu.be" -> {
                uri.pathSegments.firstOrNull()?.takeIf { TRAILER_YOUTUBE_VIDEO_ID_REGEX.matches(it) }
            }

            host == "youtube.com" || host.endsWith(".youtube.com") -> {
                val queryId = uri.getQueryParameter("v")
                if (!queryId.isNullOrBlank() && TRAILER_YOUTUBE_VIDEO_ID_REGEX.matches(queryId)) {
                    queryId
                } else {
                    val segments = uri.pathSegments
                    val candidate = when (segments.firstOrNull()?.lowercase()) {
                        "embed", "shorts", "live" -> segments.getOrNull(1)
                        else -> null
                    }
                    candidate?.takeIf { TRAILER_YOUTUBE_VIDEO_ID_REGEX.matches(it) }
                }
            }

            else -> null
        }
    }.getOrNull()
}
