@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioColors
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernContinueWatchingRowItem(
    payload: ModernPayload.ContinueWatching,
    requester: FocusRequester,
    cardWidth: Dp,
    imageHeight: Dp,
    onFocused: () -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onShowOptions: (ContinueWatchingItem) -> Unit
) {
    // Stabilize per-item lambdas so ContinueWatchingCard does not recompose
    // when only the parent row recomposes with the same payload.
    val stableOnClick = remember(payload.item) {
        { onContinueWatchingClick(payload.item) }
    }
    val stableOnLongPress = remember(payload.item) {
        { onShowOptions(payload.item) }
    }
    ContinueWatchingCard(
        item = payload.item,
        onClick = stableOnClick,
        onLongPress = stableOnLongPress,
        cardWidth = cardWidth,
        imageHeight = imageHeight,
        modifier = Modifier
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused) {
                    onFocused()
                }
            }
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ModernCatalogRowItem(
    item: ModernCarouselItem,
    payload: ModernPayload.Catalog,
    requester: FocusRequester,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    modernCatalogCardWidth: Dp,
    modernCatalogCardHeight: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    expandedCatalogFocusKey: String?,
    expandedTrailerPreviewUrl: String?,
    isWatched: Boolean,
    onFocused: () -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onLongPress: () -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit
) {
    val focusKey = payload.focusKey
    val suppressCardExpansionForHeroTrailer =
        effectiveAutoplayEnabled &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
    val isBackdropExpanded =
        effectiveExpandEnabled &&
            expandedCatalogFocusKey == focusKey &&
            !suppressCardExpansionForHeroTrailer
    val playTrailerInExpandedCard =
        effectiveAutoplayEnabled &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD &&
            isBackdropExpanded
    val trailerPreviewUrl = if (playTrailerInExpandedCard) {
        expandedTrailerPreviewUrl
    } else {
        null
    }

    // Stabilize per-item lambdas to prevent recomposition of the card when only
    // the parent row recomposes.  The remember keys cover every captured value that
    // can meaningfully change between recompositions.
    val stableOnCardFocused = remember(onFocused, focusKey, payload) {
        {
            onFocused()
            onCatalogSelectionFocused(
                FocusedCatalogSelection(
                    focusKey = focusKey,
                    payload = payload
                )
            )
        }
    }
    val stableOnClick = remember(payload.itemId, payload.itemType, payload.addonBaseUrl) {
        {
            onNavigateToDetail(
                payload.itemId,
                payload.itemType,
                payload.addonBaseUrl
            )
        }
    }
    val stableOnTrailerEnded = remember(onExpandedCatalogFocusKeyChange) {
        { onExpandedCatalogFocusKeyChange(null) }
    }

    ModernCarouselCard(
        item = item,
        useLandscapePosters = useLandscapePosters,
        showLabels = showLabels,
        cardCornerRadius = posterCardCornerRadius,
        cardWidth = modernCatalogCardWidth,
        cardHeight = modernCatalogCardHeight,
        focusedPosterBackdropExpandEnabled = effectiveExpandEnabled,
        isBackdropExpanded = isBackdropExpanded,
        playTrailerInExpandedCard = playTrailerInExpandedCard,
        focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
        trailerPreviewUrl = trailerPreviewUrl,
        isWatched = isWatched,
        focusRequester = requester,
        onFocused = stableOnCardFocused,
        onClick = stableOnClick,
        onLongPress = onLongPress,
        onBackdropInteraction = onBackdropInteraction,
        onTrailerEnded = stableOnTrailerEnded
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModernRowSection(
    row: HeroCarouselRow,
    rowTitleBottom: Dp,
    defaultBringIntoViewSpec: BringIntoViewSpec,
    focusStateCatalogRowScrollStates: Map<String, Int>,
    rowListStates: MutableMap<String, LazyListState>,
    focusedItemByRow: MutableMap<String, Int>,
    itemFocusRequesters: MutableMap<String, MutableMap<String, FocusRequester>>,
    loadMoreRequestedTotals: MutableMap<String, Int>,
    requesterFor: (String, String) -> FocusRequester,
    pendingRowFocusKey: String?,
    pendingRowFocusIndex: Int?,
    pendingRowFocusNonce: Int,
    onPendingRowFocusCleared: () -> Unit,
    onRowItemFocused: (String, Int, Boolean) -> Unit,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    posterCardCornerRadius: Dp,
    focusedPosterBackdropTrailerMuted: Boolean,
    effectiveExpandEnabled: Boolean,
    effectiveAutoplayEnabled: Boolean,
    trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget,
    expandedCatalogFocusKey: String?,
    expandedTrailerPreviewUrl: String?,
    modernCatalogCardWidth: Dp,
    modernCatalogCardHeight: Dp,
    continueWatchingCardWidth: Dp,
    continueWatchingCardHeight: Dp,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingOptions: (ContinueWatchingItem) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onCatalogSelectionFocused: (FocusedCatalogSelection) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onBackdropInteraction: () -> Unit,
    onExpandedCatalogFocusKeyChange: (String?) -> Unit
) {
    Column {
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = NuvioColors.TextPrimary,
            modifier = Modifier.padding(start = 52.dp, bottom = rowTitleBottom)
        )

        val rowListState = rowListStates.getOrPut(row.key) {
            LazyListState(
                firstVisibleItemIndex = focusStateCatalogRowScrollStates[row.key] ?: 0
            )
        }
        val isRowScrolling by remember(rowListState) {
            derivedStateOf { rowListState.isScrollInProgress }
        }
        val currentRowState = rememberUpdatedState(row)
        val loadMoreCatalogId = row.catalogId
        val loadMoreAddonId = row.addonId
        val loadMoreApiType = row.apiType
        val canObserveLoadMore = row.supportsSkip &&
            row.hasMore &&
            !loadMoreCatalogId.isNullOrBlank() &&
            !loadMoreAddonId.isNullOrBlank() &&
            !loadMoreApiType.isNullOrBlank()

        LaunchedEffect(row.key, pendingRowFocusNonce) {
            if (pendingRowFocusKey != row.key) return@LaunchedEffect
            val targetIndex = (pendingRowFocusIndex ?: 0)
                .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            val targetItemKey = row.items.getOrNull(targetIndex)?.key ?: return@LaunchedEffect
            val requester = requesterFor(row.key, targetItemKey)
            var didFocus = false
            var didScrollToTarget = false
            // Use a time-based budget (350ms) instead of a fixed retry count so that
            // slow devices with longer frame times still get enough attempts while fast
            // devices don't waste frames.  This replaces the previous fixed-12 retry
            // which could be insufficient on low-end hardware.
            val deadlineNanos = System.nanoTime() + 350_000_000L // 350ms
            while (System.nanoTime() < deadlineNanos) {
                didFocus = runCatching {
                    requester.requestFocus()
                    true
                }.getOrDefault(false)
                if (didFocus) break
                if (!didScrollToTarget) {
                    runCatching { rowListState.scrollToItem(targetIndex) }
                    didScrollToTarget = true
                }
                withFrameNanos { }
            }
            if (!didFocus) {
                val fallbackIndex = rowListState.firstVisibleItemIndex
                    .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
                val fallbackItemKey = row.items.getOrNull(fallbackIndex)?.key
                didFocus = runCatching {
                    if (fallbackItemKey != null) {
                        requesterFor(row.key, fallbackItemKey).requestFocus()
                    }
                    true
                }.getOrDefault(false)
            }
            if (didFocus) {
                onPendingRowFocusCleared()
            }
        }

        if (canObserveLoadMore) {
            LaunchedEffect(
                row.key,
                rowListState,
                canObserveLoadMore
            ) {
                snapshotFlow {
                    val layoutInfo = rowListState.layoutInfo
                    val total = layoutInfo.totalItemsCount
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible to total
                }
                    .distinctUntilChanged()
                    .collect { (lastVisible, total) ->
                        if (total <= 0) return@collect
                        val rowState = currentRowState.value
                        val isNearEnd = lastVisible >= total - 4
                        if (!isNearEnd) {
                            loadMoreRequestedTotals.remove(rowState.key)
                            return@collect
                        }
                        val lastRequestedTotal = loadMoreRequestedTotals[rowState.key]
                        if (rowState.hasMore &&
                            !rowState.isLoading &&
                            lastRequestedTotal != total
                        ) {
                            loadMoreRequestedTotals[rowState.key] = total
                            onLoadMoreCatalog(
                                loadMoreCatalogId,
                                loadMoreAddonId,
                                loadMoreApiType
                            )
                        }
                    }
            }
        }

        val density = LocalDensity.current
        val rowStartPadding = 52.dp
        val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec) {
            val parentStartOffsetPx = with(density) { 28.dp.roundToPx() }
            object : BringIntoViewSpec {
                @Suppress("DEPRECATION")
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float {
                    val childSize = abs(size)
                    val childSmallerThanParent = childSize <= containerSize
                    val initialTarget = parentStartOffsetPx.toFloat()
                    val spaceAvailable = containerSize - initialTarget

                    val targetForLeadingEdge =
                        if (childSmallerThanParent && spaceAvailable < childSize) {
                            containerSize - childSize
                        } else {
                            initialTarget
                        }

                    return offset - targetForLeadingEdge
                }
            }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
            LazyRow(
                state = rowListState,
                modifier = Modifier.focusRestorer {
                    val rememberedIndex = (focusedItemByRow[row.key] ?: 0)
                        .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
                    val fallbackIndex = rowListState.firstVisibleItemIndex
                        .coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
                    val restoreIndex = if (rememberedIndex in row.items.indices) {
                        rememberedIndex
                    } else {
                        fallbackIndex
                    }
                    val itemKey = row.items.getOrNull(restoreIndex)?.key ?: row.items.first().key
                    itemFocusRequesters[row.key]?.get(itemKey) ?: FocusRequester.Default
                },
                contentPadding = PaddingValues(horizontal = rowStartPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                beyondBoundsItemCount = 3
            ) {
                itemsIndexed(
                    items = row.items,
                    key = { _, item -> item.key },
                    contentType = { _, item ->
                        when (item.payload) {
                            is ModernPayload.ContinueWatching -> "modern_cw_card"
                            is ModernPayload.Catalog -> "modern_catalog_card"
                        }
                    }
                ) { index, item ->
                    val requester = requesterFor(row.key, item.key)
                    val isContinueWatchingRow = row.key == "continue_watching"
                    val rowKey = row.key
                    val stableOnFocused = remember(rowKey, index, isContinueWatchingRow) {
                        { onRowItemFocused(rowKey, index, isContinueWatchingRow) }
                    }

                    when (val payload = item.payload) {
                        is ModernPayload.ContinueWatching -> {
                            ModernContinueWatchingRowItem(
                                payload = payload,
                                requester = requester,
                                cardWidth = continueWatchingCardWidth,
                                imageHeight = continueWatchingCardHeight,
                                onFocused = stableOnFocused,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onShowOptions = onContinueWatchingOptions
                            )
                        }

                        is ModernPayload.Catalog -> {
                            val stableLongPress = remember(item.metaPreview, payload.addonBaseUrl) {
                                {
                                    item.metaPreview?.let { preview ->
                                        onCatalogItemLongPress(preview, payload.addonBaseUrl)
                                    }
                                    Unit
                                }
                            }
                            ModernCatalogRowItem(
                                item = item,
                                payload = payload,
                                requester = requester,
                                useLandscapePosters = useLandscapePosters,
                                showLabels = showLabels,
                                posterCardCornerRadius = posterCardCornerRadius,
                                modernCatalogCardWidth = modernCatalogCardWidth,
                                modernCatalogCardHeight = modernCatalogCardHeight,
                                focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
                                effectiveExpandEnabled = effectiveExpandEnabled && !isRowScrolling,
                                effectiveAutoplayEnabled = effectiveAutoplayEnabled && !isRowScrolling,
                                trailerPlaybackTarget = trailerPlaybackTarget,
                                expandedCatalogFocusKey = expandedCatalogFocusKey,
                                expandedTrailerPreviewUrl = expandedTrailerPreviewUrl,
                                isWatched = item.metaPreview?.let(isCatalogItemWatched) == true,
                                onFocused = stableOnFocused,
                                onCatalogSelectionFocused = onCatalogSelectionFocused,
                                onNavigateToDetail = onNavigateToDetail,
                                onLongPress = stableLongPress,
                                onBackdropInteraction = onBackdropInteraction,
                                onExpandedCatalogFocusKeyChange = onExpandedCatalogFocusKeyChange
                            )
                        }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ModernCarouselCard(
    item: ModernCarouselItem,
    useLandscapePosters: Boolean,
    showLabels: Boolean,
    cardCornerRadius: Dp,
    cardWidth: Dp,
    cardHeight: Dp,
    focusedPosterBackdropExpandEnabled: Boolean,
    isBackdropExpanded: Boolean,
    playTrailerInExpandedCard: Boolean,
    focusedPosterBackdropTrailerMuted: Boolean,
    trailerPreviewUrl: String?,
    isWatched: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onBackdropInteraction: () -> Unit,
    onTrailerEnded: () -> Unit
) {
    val cardShape = remember(cardCornerRadius) { RoundedCornerShape(cardCornerRadius) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val expandedCardWidth = remember(cardHeight) { cardHeight * (16f / 9f) }
    val targetCardWidth = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        expandedCardWidth
    } else {
        cardWidth
    }
    // Keep the State reference (no `by` delegation) so we can read the value
    // in the layout phase instead of composition.  This avoids a full recomposition
    // on every animation frame during card expansion/collapse — only relayout occurs.
    val animatedCardWidthState: State<Dp> = if (focusedPosterBackdropExpandEnabled) {
        animateDpAsState(
            targetValue = targetCardWidth,
            animationSpec = tween(durationMillis = 250),
            label = "modernCardWidth"
        )
    } else {
        rememberUpdatedState(cardWidth)
    }
    val imageUrl = if (focusedPosterBackdropExpandEnabled && isBackdropExpanded) {
        item.heroPreview.backdrop ?: item.imageUrl ?: item.heroPreview.poster
    } else {
        item.imageUrl ?: item.heroPreview.poster ?: item.heroPreview.backdrop
    }
    // Keep decode target stable across expand/collapse to avoid recreating image requests/painters
    // purely due to animated width changes.
    val maxRequestCardWidth = if (focusedPosterBackdropExpandEnabled) {
        maxOf(cardWidth, expandedCardWidth)
    } else {
        cardWidth
    }
    val requestWidthPx = remember(maxRequestCardWidth, density) {
        with(density) { maxRequestCardWidth.roundToPx() }
    }
    val requestHeightPx = remember(cardHeight, density) {
        with(density) { cardHeight.roundToPx() }
    }
    val imageModel = remember(context, imageUrl, requestWidthPx, requestHeightPx) {
        imageUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .size(width = requestWidthPx, height = requestHeightPx)
                .memoryCacheKey("card_${it}_${requestWidthPx}x${requestHeightPx}")
                .diskCacheKey(it)
                .build()
        }
    }
    val logoHeight = cardHeight * 0.34f
    val logoHeightPx = remember(logoHeight, density) {
        with(density) { logoHeight.roundToPx() }
    }
    val maxLogoWidthPx = remember(maxRequestCardWidth, density) {
        with(density) { (maxRequestCardWidth * 0.62f).roundToPx() }
    }
    val logoModel = remember(context, item.heroPreview.logo, maxLogoWidthPx, logoHeightPx) {
        item.heroPreview.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .size(width = maxLogoWidthPx, height = logoHeightPx)
                .memoryCacheKey("logo_${it}_${maxLogoWidthPx}x${logoHeightPx}")
                .diskCacheKey(it)
                .build()
        }
    }
    val shouldPlayTrailerInCard = playTrailerInExpandedCard && !trailerPreviewUrl.isNullOrBlank()
    val hasImage = !imageUrl.isNullOrBlank()
    val hasLandscapeLogo = useLandscapePosters && !item.heroPreview.logo.isNullOrBlank()
    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    // Only animate watched icon padding when the item is actually watched to avoid
    // unnecessary animation state overhead for the majority of cards.
    val watchedIconEndPadding = if (isWatched) {
        val animated by animateDpAsState(
            targetValue = if (isFocused) 16.dp else 8.dp,
            animationSpec = tween(durationMillis = 180),
            label = "modernCardWatchedIconEndPadding"
        )
        animated
    } else {
        8.dp
    }
    val backgroundCardColor = NuvioColors.BackgroundCard
    val focusRingColor = NuvioColors.FocusRing
    val titleMedium = MaterialTheme.typography.titleMedium
    val focusedBorder = remember(cardShape, focusRingColor) {
        Border(
            border = BorderStroke(2.dp, focusRingColor),
            shape = cardShape
        )
    }
    val titleStyle = remember(titleMedium) {
        titleMedium.copy(fontWeight = FontWeight.Medium)
    }
    val cardColors = remember(backgroundCardColor) {
        CardDefaults.colors(
            containerColor = backgroundCardColor,
            focusedContainerColor = backgroundCardColor
        )
    }
    val cardBorder = remember(focusedBorder) {
        CardDefaults.border(focusedBorder = focusedBorder)
    }
    val cardScale = remember { CardDefaults.scale(focusedScale = 1f) }
    val cardShapeDefaults = remember(cardShape) { CardDefaults.shape(shape = cardShape) }

    Column(
        // Defer the animated width read to the layout phase so that each
        // animation frame only triggers relayout (cheap) instead of full
        // recomposition of the entire card tree.
        modifier = Modifier.layout { measurable, constraints ->
            val widthPx = animatedCardWidthState.value.roundToPx()
            val placeable = measurable.measure(
                constraints.copy(minWidth = widthPx, maxWidth = widthPx)
            )
            layout(widthPx, placeable.height) {
                placeable.placeRelative(0, 0)
            }
        },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            onClick = {
                if (longPressTriggered) {
                    longPressTriggered = false
                } else {
                    onClick()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) {
                        onFocused()
                    }
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                        if (focusedPosterBackdropExpandEnabled && shouldResetBackdropTimer(event.key)) {
                            onBackdropInteraction()
                        }
                        if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                            longPressTriggered = true
                            onLongPress()
                            return@onPreviewKeyEvent true
                        }
                        val isLongPress = native.isLongPress || native.repeatCount > 0
                        if (isLongPress && isSelectKey(native.keyCode)) {
                            longPressTriggered = true
                            onLongPress()
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (native.action == AndroidKeyEvent.ACTION_UP &&
                        longPressTriggered &&
                        isSelectKey(native.keyCode)
                    ) {
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            shape = cardShapeDefaults,
            colors = cardColors,
            border = cardBorder,
            scale = cardScale
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (hasImage) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    MonochromePosterPlaceholder()
                }

                if (shouldPlayTrailerInCard) {
                    TrailerPlayer(
                        trailerUrl = trailerPreviewUrl,
                        isPlaying = true,
                        onEnded = onTrailerEnded,
                        muted = focusedPosterBackdropTrailerMuted,
                        cropToFill = true,
                        overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (hasLandscapeLogo) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MODERN_LANDSCAPE_LOGO_GRADIENT)
                    )
                    AsyncImage(
                        model = logoModel,
                        contentDescription = item.title,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.62f)
                            .height(cardHeight * 0.34f)
                            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                }

                if (isWatched) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = watchedIconEndPadding, top = 8.dp)
                            .zIndex(2f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.episodes_cd_watched),
                            tint = Color.White,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }

        if (showLabels && !isBackdropExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                Text(
                    text = item.title,
                    style = titleStyle,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}


private fun shouldResetBackdropTimer(key: Key): Boolean {
    return when (key) {
        Key.DirectionUp,
        Key.DirectionDown,
        Key.DirectionLeft,
        Key.DirectionRight,
        Key.DirectionCenter,
        Key.Enter,
        Key.NumPadEnter,
        Key.Back -> true
        else -> false
    }
}

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}
