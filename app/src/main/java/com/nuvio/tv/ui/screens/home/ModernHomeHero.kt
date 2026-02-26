package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioColors

/**
 * Hero media layer – optimized to eliminate SubcomposeLayout and reduce overdraw.
 *
 * Key changes vs. the previous implementation:
 * 1. **No more Crossfade**: Crossfade uses SubcomposeLayout internally, which forces a
 *    second measurement pass every frame during the transition. We now stack two AsyncImage
 *    instances and drive alpha via graphicsLayer (GPU-only, no recomposition/relayout).
 * 2. **Pre-computed gradient overlay**: The gradient modifier is remembered based only on
 *    bgColor, so it is not recreated on every recomposition.
 * 3. **CompositingStrategy.ModulateAlpha**: Alpha changes are GPU-only operations with no
 *    recomposition or relayout, making backdrop/trailer crossfades essentially free.
 */
@Composable
internal fun ModernHeroMediaLayer(
    heroBackdrop: String?,
    heroBackdropAlpha: Float,
    shouldPlayHeroTrailer: Boolean,
    heroTrailerUrl: String?,
    heroTrailerAlpha: Float,
    muted: Boolean,
    bgColor: Color,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int
) {
    val localContext = LocalContext.current

    // Track the previous backdrop URL so we can cross-fade between old and new images
    // without SubcomposeLayout (Crossfade).
    var previousBackdrop by remember { mutableStateOf<String?>(null) }
    var currentBackdrop by remember { mutableStateOf<String?>(null) }

    // When heroBackdrop changes, shift the current image to "previous" slot for fade-out.
    if (heroBackdrop != currentBackdrop) {
        previousBackdrop = currentBackdrop
        currentBackdrop = heroBackdrop
    }

    // Animate the crossfade progress: 0 = showing previous, 1 = showing current.
    // The key on currentBackdrop resets the animation whenever the target changes.
    val crossfadeTarget by animateFloatAsState(
        targetValue = if (currentBackdrop == heroBackdrop) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "heroBackdropCrossfade",
        finishedListener = {
            // Once the transition completes, discard the previous image to free memory.
            previousBackdrop = null
        }
    )

    val previousImageModel = remember(localContext, previousBackdrop, requestWidthPx, requestHeightPx) {
        previousBackdrop?.let {
            ImageRequest.Builder(localContext)
                .data(it)
                .crossfade(false)
                .memoryCacheKey("hero_bg_${it}_${requestWidthPx}x${requestHeightPx}")
                .size(width = requestWidthPx, height = requestHeightPx)
                .build()
        }
    }

    val currentImageModel = remember(localContext, currentBackdrop, requestWidthPx, requestHeightPx) {
        currentBackdrop?.let {
            ImageRequest.Builder(localContext)
                .data(it)
                .crossfade(false)
                .memoryCacheKey("hero_bg_${it}_${requestWidthPx}x${requestHeightPx}")
                .size(width = requestWidthPx, height = requestHeightPx)
                .build()
        }
    }

    // Pre-compute the gradient overlay modifier once (only changes when bgColor changes).
    val gradientOverlayModifier = remember(bgColor) {
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val horizontalGradient = Brush.horizontalGradient(
                    0.0f to bgColor.copy(alpha = 0.96f),
                    0.10f to bgColor.copy(alpha = 0.72f),
                    0.30f to Color.Transparent
                )
                val radialGradient = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to bgColor.copy(alpha = 0.78f),
                        0.55f to bgColor.copy(alpha = 0.52f),
                        0.80f to bgColor.copy(alpha = 0.16f),
                        1.0f to Color.Transparent
                    ),
                    center = Offset(0f, size.height / 2f),
                    radius = size.height
                )
                val verticalGradient = Brush.verticalGradient(
                    0.78f to Color.Transparent,
                    0.90f to bgColor.copy(alpha = 0.72f),
                    0.96f to bgColor.copy(alpha = 0.98f),
                    1.0f to bgColor
                )
                onDrawBehind {
                    drawRect(brush = horizontalGradient, size = size)
                    drawRect(brush = radialGradient, size = size, blendMode = BlendMode.SrcOver)
                    drawRect(brush = verticalGradient, size = size, blendMode = BlendMode.SrcOver)
                }
            }
    }

    Box(modifier = modifier) {
        // --- Backdrop images: manual dual-image crossfade (no SubcomposeLayout) ---
        // Previous image fading out
        if (previousBackdrop != null && previousImageModel != null) {
            AsyncImage(
                model = previousImageModel,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = heroBackdropAlpha * (1f - crossfadeTarget)
                        // Use ModulateAlpha so alpha changes are GPU-only (no recomposition).
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }

        // Current image fading in
        if (currentImageModel != null) {
            AsyncImage(
                model = currentImageModel,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = heroBackdropAlpha * crossfadeTarget
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }

        // --- Trailer player ---
        if (shouldPlayHeroTrailer) {
            TrailerPlayer(
                trailerUrl = heroTrailerUrl,
                isPlaying = true,
                onEnded = onTrailerEnded,
                onFirstFrameRendered = onFirstFrameRendered,
                muted = muted,
                cropToFill = true,
                overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = heroTrailerAlpha
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
            )
        }

        // --- Gradient scrim overlay (pre-computed, cached on bgColor) ---
        Box(modifier = gradientOverlayModifier)
    }
}

@Composable
internal fun HeroTitleBlock(
    preview: HeroPreview?,
    portraitMode: Boolean,
    modifier: Modifier = Modifier
) {
    if (preview == null) return

    val descriptionMaxLines = if (portraitMode) 4 else 5
    val descriptionScale = if (portraitMode) 0.90f else 1f
    val titleScale = if (portraitMode) 0.92f else 1f
    val metaScale = 1f
    val titleSpacing = 8.dp * titleScale
    val metaSpacing = 8.dp * metaScale
    val imdbMetaSpacing = 4.dp * metaScale
    val context = LocalContext.current
    val density = LocalDensity.current
    val headlineLarge = MaterialTheme.typography.headlineLarge
    val labelMedium = MaterialTheme.typography.labelMedium
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val logoMaxWidthPx = remember(density) { with(density) { 220.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { 100.dp.roundToPx() } }
    val logoModel = remember(context, preview.logo, logoMaxWidthPx, logoHeightPx) {
        preview.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .memoryCacheKey("hero_title_logo_${it}_${logoMaxWidthPx}x${logoHeightPx}")
                .size(width = logoMaxWidthPx, height = logoHeightPx)
                .build()
        }
    }
    val imdbLogoModel = remember(context) {
        ImageRequest.Builder(context)
            .data(com.nuvio.tv.R.raw.imdb_logo_2016)
            .memoryCacheKey("imdb_logo_svg")
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    val scaledTitleStyle = remember(headlineLarge, titleScale) {
        headlineLarge.copy(
            fontSize = headlineLarge.fontSize * titleScale,
            lineHeight = headlineLarge.lineHeight * titleScale
        )
    }
    val scaledDescriptionStyle = remember(bodyMedium, descriptionScale) {
        bodyMedium.copy(
            fontSize = bodyMedium.fontSize * descriptionScale,
            lineHeight = bodyMedium.lineHeight * descriptionScale
        )
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(titleSpacing)
    ) {
        if (!preview.logo.isNullOrBlank()) {
            AsyncImage(
                model = logoModel,
                contentDescription = preview.title,
                modifier = Modifier
                    .height(100.dp)
                    .widthIn(min = 100.dp, max = 220.dp)
                    .fillMaxWidth(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart
            )
        } else {
            Text(
                text = preview.title,
                style = scaledTitleStyle,
                color = NuvioColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metaSpacing)
        ) {
            var hasLeadingMeta = false

            preview.contentTypeText?.takeIf { it.isNotBlank() }?.let { contentType ->
                Text(
                    text = contentType,
                    style = labelMedium,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                hasLeadingMeta = true
            }

            preview.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { genre ->
                if (hasLeadingMeta) {
                    HeroMetaDivider(metaScale)
                }
                Text(
                    text = genre,
                    style = labelMedium,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                hasLeadingMeta = true
            }

            val yearText = preview.yearText
            val imdbText = preview.imdbText
            val hasYearOrImdb = !yearText.isNullOrBlank() || !imdbText.isNullOrBlank()
            if (hasYearOrImdb) {
                if (hasLeadingMeta) {
                    HeroMetaDivider(metaScale)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metaSpacing)
                ) {
                    if (!yearText.isNullOrBlank()) {
                        Text(
                            text = yearText,
                            style = labelMedium,
                            color = NuvioColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (!imdbText.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(imdbMetaSpacing)
                        ) {
                            AsyncImage(
                                model = imdbLogoModel,
                                contentDescription = "IMDb",
                                modifier = Modifier.size(30.dp * metaScale),
                                contentScale = ContentScale.Fit
                            )
                            Text(
                                text = imdbText,
                                style = labelMedium,
                                color = NuvioColors.TextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }
                hasLeadingMeta = true
            }
        }

        preview.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = scaledDescriptionStyle,
                color = NuvioColors.TextPrimary,
                maxLines = descriptionMaxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HeroMetaDivider(scale: Float) {
    Box(
        modifier = Modifier
            .size((4.dp * scale).coerceAtLeast(2.dp))
            .clip(RoundedCornerShape(percent = 50))
            .background(NuvioColors.TextTertiary.copy(alpha = 0.78f))
    )
}
