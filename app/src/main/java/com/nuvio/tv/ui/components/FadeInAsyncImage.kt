package com.nuvio.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.size.Precision

/**
 * AsyncImage wrapper with optional fade-in.
 *
 * By default, fades in only when the image is not already in Coil's memory cache.
 */
@Composable
fun FadeInAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    fadeDurationMs: Int = 400,
    enableFadeIn: Boolean = true,
    requestedWidthDp: Dp? = null,
    requestedHeightDp: Dp? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val request = remember(model, requestedWidthDp, requestedHeightDp) {
        val builder = ImageRequest.Builder(context)
            .data(model)
            .crossfade(false)
            .allowHardware(true)
            .precision(Precision.INEXACT)

        if (requestedWidthDp != null && requestedHeightDp != null) {
            val widthPx = with(density) { requestedWidthDp.roundToPx() }
            val heightPx = with(density) { requestedHeightDp.roundToPx() }
            builder.size(widthPx, heightPx)
        }

        builder.build()
    }

    var shouldAnimate by remember(model, enableFadeIn) { mutableStateOf(enableFadeIn) }
    var loaded by remember(model, enableFadeIn) { mutableStateOf(!enableFadeIn) }
    val alpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = tween(durationMillis = if (shouldAnimate) fadeDurationMs else 0),
        label = "imageFadeIn"
    )

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = if (enableFadeIn) {
            modifier.graphicsLayer { this.alpha = alpha }
        } else {
            modifier
        },
        contentScale = contentScale,
        alignment = alignment,
        onState = { state ->
            if (enableFadeIn && state is AsyncImagePainter.State.Success) {
                // Avoid re-animating cached images while scrolling.
                shouldAnimate = state.result.dataSource != DataSource.MEMORY_CACHE
                loaded = true
            }
        }
    )
}
