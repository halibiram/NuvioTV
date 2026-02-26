@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.State
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nuvio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ModernHomeContent(
    uiState: HomeUiState,
    focusState: HomeScreenFocusState,
    trailerPreviewUrls: Map<String, String>,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onRequestTrailerPreview: (String, String, String?, String) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    onItemFocus: (MetaPreview) -> Unit = {},
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit
) {
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    val showCatalogTypeSuffixInModern = uiState.catalogTypeSuffixEnabled
    val isLandscapeModern = useLandscapePosters
    val expandControlAvailable = !isLandscapeModern
    val trailerPlaybackTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget
    val effectiveAutoplayEnabled =
        uiState.focusedPosterBackdropTrailerEnabled &&
            (isLandscapeModern || uiState.focusedPosterBackdropExpandEnabled)
    val landscapeExpandedCardMode =
        isLandscapeModern &&
            effectiveAutoplayEnabled &&
            trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD
    val effectiveExpandEnabled =
        (uiState.focusedPosterBackdropExpandEnabled && expandControlAvailable) ||
            landscapeExpandedCardMode
    val shouldActivateFocusedPosterFlow =
        effectiveExpandEnabled ||
            (effectiveAutoplayEnabled &&
                trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
    val catalogRows = uiState.catalogRows
    val visibleCatalogRows = remember(catalogRows) {
        catalogRows.filter { it.items.isNotEmpty() }
    }
    val strContinueWatching = stringResource(R.string.continue_watching)
    val strAirsDate = stringResource(R.string.cw_airs_date)
    val rowBuildCache = remember { ModernCarouselRowBuildCache() }
    val carouselRows = remember(
        uiState.continueWatchingItems,
        visibleCatalogRows,
        useLandscapePosters,
        showCatalogTypeSuffixInModern
    ) {
        buildList {
            val activeCatalogKeys = LinkedHashSet<String>(visibleCatalogRows.size)
            if (uiState.continueWatchingItems.isNotEmpty()) {
                val reuseContinueWatchingRow =
                    rowBuildCache.continueWatchingRow != null &&
                        rowBuildCache.continueWatchingItems == uiState.continueWatchingItems &&
                        rowBuildCache.continueWatchingTitle == strContinueWatching &&
                        rowBuildCache.continueWatchingAirsDateTemplate == strAirsDate &&
                        rowBuildCache.continueWatchingUseLandscapePosters == useLandscapePosters
                val continueWatchingRow = if (reuseContinueWatchingRow) {
                    checkNotNull(rowBuildCache.continueWatchingRow)
                } else {
                    HeroCarouselRow(
                        key = "continue_watching",
                        title = strContinueWatching,
                        globalRowIndex = -1,
                        items = uiState.continueWatchingItems.map { item ->
                            buildContinueWatchingItem(
                                item = item,
                                useLandscapePosters = useLandscapePosters,
                                airsDateTemplate = strAirsDate
                            )
                        }
                    )
                }
                rowBuildCache.continueWatchingItems = uiState.continueWatchingItems
                rowBuildCache.continueWatchingTitle = strContinueWatching
                rowBuildCache.continueWatchingAirsDateTemplate = strAirsDate
                rowBuildCache.continueWatchingUseLandscapePosters = useLandscapePosters
                rowBuildCache.continueWatchingRow = continueWatchingRow
                add(continueWatchingRow)
            } else {
                rowBuildCache.continueWatchingItems = emptyList()
                rowBuildCache.continueWatchingRow = null
            }

            visibleCatalogRows.forEachIndexed { index, row ->
                val rowKey = catalogRowKey(row)
                activeCatalogKeys += rowKey
                val cached = rowBuildCache.catalogRows[rowKey]
                val canReuseMappedRow =
                    cached != null &&
                        cached.source == row &&
                        cached.useLandscapePosters == useLandscapePosters &&
                        cached.showCatalogTypeSuffix == showCatalogTypeSuffixInModern

                val mappedRow = if (canReuseMappedRow) {
                    val cachedMappedRow = checkNotNull(cached).mappedRow
                    if (cachedMappedRow.globalRowIndex == index) {
                        cachedMappedRow
                    } else {
                        cachedMappedRow.copy(globalRowIndex = index)
                    }
                } else {
                    val rowItemOccurrenceCounts = mutableMapOf<String, Int>()
                    HeroCarouselRow(
                        key = rowKey,
                        title = catalogRowTitle(
                            row = row,
                            showCatalogTypeSuffix = showCatalogTypeSuffixInModern
                        ),
                        globalRowIndex = index,
                        catalogId = row.catalogId,
                        addonId = row.addonId,
                        apiType = row.apiType,
                        supportsSkip = row.supportsSkip,
                        hasMore = row.hasMore,
                        isLoading = row.isLoading,
                        items = row.items.map { item ->
                            val occurrence = rowItemOccurrenceCounts.getOrDefault(item.id, 0)
                            rowItemOccurrenceCounts[item.id] = occurrence + 1
                            buildCatalogItem(
                                item = item,
                                row = row,
                                useLandscapePosters = useLandscapePosters,
                                occurrence = occurrence
                            )
                        }
                    )
                }

                rowBuildCache.catalogRows[rowKey] = ModernCatalogRowBuildCacheEntry(
                    source = row,
                    useLandscapePosters = useLandscapePosters,
                    showCatalogTypeSuffix = showCatalogTypeSuffixInModern,
                    mappedRow = mappedRow
                )
                add(mappedRow)
            }
            rowBuildCache.catalogRows.keys.retainAll(activeCatalogKeys)
        }
    }

    if (carouselRows.isEmpty()) return
    val carouselLookups = remember(carouselRows) {
        val rowIndexByKey = LinkedHashMap<String, Int>(carouselRows.size)
        val rowByKey = LinkedHashMap<String, HeroCarouselRow>(carouselRows.size)
        val activeRowKeys = LinkedHashSet<String>(carouselRows.size)
        val activeItemKeysByRow = LinkedHashMap<String, Set<String>>(carouselRows.size)
        val activeCatalogItemIds = LinkedHashSet<String>()

        carouselRows.forEachIndexed { index, row ->
            rowIndexByKey[row.key] = index
            rowByKey[row.key] = row
            activeRowKeys += row.key

            val itemKeys = LinkedHashSet<String>(row.items.size)
            row.items.forEach { item ->
                itemKeys += item.key
                val payload = item.payload
                if (payload is ModernPayload.Catalog) {
                    activeCatalogItemIds += payload.itemId
                }
            }
            activeItemKeysByRow[row.key] = itemKeys
        }

        CarouselRowLookups(
            rowIndexByKey = rowIndexByKey,
            rowByKey = rowByKey,
            activeRowKeys = activeRowKeys,
            activeItemKeysByRow = activeItemKeysByRow,
            activeCatalogItemIds = activeCatalogItemIds
        )
    }
    val rowIndexByKey = carouselLookups.rowIndexByKey
    val rowByKey = carouselLookups.rowByKey
    val activeRowKeys = carouselLookups.activeRowKeys
    val activeItemKeysByRow = carouselLookups.activeItemKeysByRow
    val activeCatalogItemIds = carouselLookups.activeCatalogItemIds
    val verticalRowListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset
    )
    val isVerticalRowsScrolling by remember(verticalRowListState) {
        derivedStateOf { verticalRowListState.isScrollInProgress }
    }

    val uiCaches = remember { ModernHomeUiCaches() }
    val focusedItemByRow = uiCaches.focusedItemByRow
    val itemFocusRequesters = uiCaches.itemFocusRequesters
    val rowListStates = uiCaches.rowListStates
    val loadMoreRequestedTotals = uiCaches.loadMoreRequestedTotals
    var activeRowKey by remember { mutableStateOf<String?>(null) }
    var activeItemIndex by remember { mutableIntStateOf(0) }
    var pendingRowFocusKey by remember { mutableStateOf<String?>(null) }
    var pendingRowFocusIndex by remember { mutableStateOf<Int?>(null) }
    var pendingRowFocusNonce by remember { mutableIntStateOf(0) }
    var heroItem by remember { mutableStateOf<HeroPreview?>(null) }
    var restoredFromSavedState by remember { mutableStateOf(false) }
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    var lastFocusedContinueWatchingIndex by remember { mutableStateOf(-1) }
    var focusedCatalogSelection by remember { mutableStateOf<FocusedCatalogSelection?>(null) }
    var expandedCatalogFocusKey by remember { mutableStateOf<String?>(null) }
    var expansionInteractionNonce by remember { mutableIntStateOf(0) }

    fun requesterFor(rowKey: String, itemKey: String): FocusRequester {
        val byIndex = itemFocusRequesters.getOrPut(rowKey) { mutableMapOf() }
        return byIndex.getOrPut(itemKey) { FocusRequester() }
    }

    // Stabilize the requesterFor function reference so ModernRowSection receives
    // the same lambda instance across recompositions (::requesterFor would create
    // a new reference each time).
    val stableRequesterFor: (String, String) -> FocusRequester = remember(itemFocusRequesters) {
        { rowKey: String, itemKey: String ->
            val byIndex = itemFocusRequesters.getOrPut(rowKey) { mutableMapOf() }
            byIndex.getOrPut(itemKey) { FocusRequester() }
        }
    }

    // Consolidated catalog-expansion + trailer-preview effects into a single
    // snapshotFlow.  Previously two separate LaunchedEffects both keyed on
    // focusedCatalogSelection?.focusKey + isVerticalRowsScrolling would restart
    // simultaneously on every focus change, doubling the coroutine churn.
    val latestOnRequestTrailerPreview by rememberUpdatedState(onRequestTrailerPreview)
    LaunchedEffect(Unit) {
        snapshotFlow {
            data class CatalogFocusSnapshot(
                val focusKey: String?,
                val scrolling: Boolean,
                val nonce: Int,
                val shouldExpand: Boolean,
                val autoplay: Boolean,
                val delaySeconds: Int,
                val selection: FocusedCatalogSelection?
            )
            CatalogFocusSnapshot(
                focusKey = focusedCatalogSelection?.focusKey,
                scrolling = isVerticalRowsScrolling,
                nonce = expansionInteractionNonce,
                shouldExpand = shouldActivateFocusedPosterFlow,
                autoplay = effectiveAutoplayEnabled,
                delaySeconds = uiState.focusedPosterBackdropExpandDelaySeconds,
                selection = focusedCatalogSelection
            )
        }
            .distinctUntilChanged()
            .collect { snapshot ->
                // Always reset expansion on any change
                expandedCatalogFocusKey = null

                // Debounce rapid focus transitions before firing trailer preview
                // requests — a short delay prevents unnecessary network requests when
                // the user is quickly scrolling through items.
                if (snapshot.autoplay && !snapshot.scrolling && snapshot.selection != null) {
                    delay(150)
                    // Re-verify the selection hasn't changed during the debounce window
                    if (focusedCatalogSelection?.focusKey != snapshot.focusKey) return@collect
                    latestOnRequestTrailerPreview(
                        snapshot.selection.payload.itemId,
                        snapshot.selection.payload.trailerTitle,
                        snapshot.selection.payload.trailerReleaseInfo,
                        snapshot.selection.payload.trailerApiType
                    )
                }

                // Delayed expansion
                if (!snapshot.shouldExpand || snapshot.scrolling || snapshot.selection == null) {
                    return@collect
                }
                delay(snapshot.delaySeconds.coerceAtLeast(1) * 1000L)
                // Re-verify conditions after delay via latest snapshot state
                if (shouldActivateFocusedPosterFlow &&
                    !isVerticalRowsScrolling &&
                    focusedCatalogSelection?.focusKey == snapshot.focusKey
                ) {
                    expandedCatalogFocusKey = snapshot.focusKey
                }
            }
    }

    LaunchedEffect(carouselRows, focusState.hasSavedFocus, focusState.focusedRowIndex, focusState.focusedItemIndex) {
        focusedItemByRow.keys.retainAll(activeRowKeys)
        itemFocusRequesters.keys.retainAll(activeRowKeys)
        rowListStates.keys.retainAll(activeRowKeys)
        loadMoreRequestedTotals.keys.retainAll(activeRowKeys)
        carouselRows.forEach { row ->
            val rowRequesters = itemFocusRequesters[row.key] ?: return@forEach
            val allowedKeys = activeItemKeysByRow[row.key] ?: emptySet()
            rowRequesters.keys.retainAll(allowedKeys)
        }
        if (focusedCatalogSelection?.payload?.itemId !in activeCatalogItemIds) {
            focusedCatalogSelection = null
            expandedCatalogFocusKey = null
        }

        carouselRows.forEach { row ->
            if (row.items.isNotEmpty() && row.key !in focusedItemByRow) {
                focusedItemByRow[row.key] = 0
            }
        }

        if (!restoredFromSavedState && focusState.hasSavedFocus) {
            val savedRowKey = when {
                focusState.focusedRowIndex == -1 && uiState.continueWatchingItems.isNotEmpty() -> "continue_watching"
                focusState.focusedRowIndex >= 0 -> visibleCatalogRows.getOrNull(focusState.focusedRowIndex)?.let { catalogRowKey(it) }
                else -> null
            }

            val resolvedRow = carouselRows.firstOrNull { it.key == savedRowKey } ?: carouselRows.first()
            val resolvedIndex = focusState.focusedItemIndex
                .coerceAtLeast(0)
                .coerceAtMost((resolvedRow.items.size - 1).coerceAtLeast(0))

            activeRowKey = resolvedRow.key
            activeItemIndex = resolvedIndex
            focusedItemByRow[resolvedRow.key] = resolvedIndex
            heroItem = resolvedRow.items.getOrNull(resolvedIndex)?.heroPreview
                ?: resolvedRow.items.firstOrNull()?.heroPreview
            pendingRowFocusKey = resolvedRow.key
            pendingRowFocusIndex = resolvedIndex
            pendingRowFocusNonce++
            restoredFromSavedState = true
            return@LaunchedEffect
        }

        val hadActiveRow = activeRowKey != null
        val existingActive = activeRowKey?.let { key -> carouselRows.firstOrNull { it.key == key } }
        val resolvedActive = existingActive ?: carouselRows.first()
        activeRowKey = resolvedActive.key
        val resolvedIndex = focusedItemByRow[resolvedActive.key]
            ?.coerceIn(0, (resolvedActive.items.size - 1).coerceAtLeast(0))
            ?: 0
        activeItemIndex = resolvedIndex
        focusedItemByRow[resolvedActive.key] = resolvedIndex
        heroItem = resolvedActive.items.getOrNull(resolvedIndex)?.heroPreview
            ?: resolvedActive.items.firstOrNull()?.heroPreview
        if (!focusState.hasSavedFocus && !hadActiveRow) {
            pendingRowFocusKey = resolvedActive.key
            pendingRowFocusIndex = resolvedIndex
            pendingRowFocusNonce++
        }
    }

    LaunchedEffect(focusState.verticalScrollIndex, focusState.verticalScrollOffset) {
        val targetIndex = focusState.verticalScrollIndex
        val targetOffset = focusState.verticalScrollOffset
        if (verticalRowListState.firstVisibleItemIndex == targetIndex &&
            verticalRowListState.firstVisibleItemScrollOffset == targetOffset
        ) {
            return@LaunchedEffect
        }
        if (targetIndex > 0 || targetOffset > 0) {
            verticalRowListState.scrollToItem(targetIndex, targetOffset)
        }
    }

    val activeRow by remember(carouselRows, rowByKey, activeRowKey) {
        derivedStateOf {
            val activeKey = activeRowKey
            if (activeKey == null) {
                null
            } else {
                rowByKey[activeKey] ?: carouselRows.firstOrNull()
            }
        }
    }
    val clampedActiveItemIndex by remember(activeRow, activeItemIndex) {
        derivedStateOf {
            activeRow?.let { row ->
                activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
            } ?: 0
        }
    }

    LaunchedEffect(activeRow?.key, activeRow?.items?.size) {
        val row = activeRow ?: return@LaunchedEffect
        val clampedIndex = activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
        if (activeItemIndex != clampedIndex) {
            activeItemIndex = clampedIndex
        }
        focusedItemByRow[row.key] = clampedIndex
    }

    // Use a single stable LaunchedEffect with snapshotFlow to observe hero item changes.
    // This replaces a LaunchedEffect keyed on (activeHeroItemKey, isVerticalRowsScrolling)
    // which restarted on every scroll-state toggle, wasting frames.
    val latestHeroRow by rememberUpdatedState(activeRow)
    val latestHeroIndex by rememberUpdatedState(clampedActiveItemIndex)
    LaunchedEffect(Unit) {
        snapshotFlow {
            val row = activeRow
            val idx = clampedActiveItemIndex
            val scrolling = isVerticalRowsScrolling
            Triple(
                row?.items?.getOrNull(idx)?.key ?: row?.items?.firstOrNull()?.key,
                scrolling,
                row to idx
            )
        }
            .distinctUntilChanged { old, new -> old.first == new.first && old.second == new.second }
            .collect { (heroItemKey, scrolling, rowAndIndex) ->
                if (scrolling || heroItemKey == null) return@collect
                delay(MODERN_HERO_FOCUS_DEBOUNCE_MS)
                // Re-read latest state after the debounce
                val row = latestHeroRow ?: return@collect
                val idx = latestHeroIndex
                val latestKey = row.items.getOrNull(idx)?.key ?: row.items.firstOrNull()?.key
                if (latestKey != heroItemKey) return@collect
                val latestHero =
                    row.items.getOrNull(idx)?.heroPreview ?: row.items.firstOrNull()?.heroPreview
                if (latestHero != null && heroItem != latestHero) {
                    heroItem = latestHero
                }
            }
    }
    val latestActiveRow by rememberUpdatedState(activeRow)
    val latestActiveItemIndex by rememberUpdatedState(clampedActiveItemIndex)
    val latestCarouselRows by rememberUpdatedState(carouselRows)
    val latestVerticalRowListState by rememberUpdatedState(verticalRowListState)
    DisposableEffect(Unit) {
        onDispose {
            val row = latestActiveRow
            val focusedRowIndex = row?.globalRowIndex ?: 0
            val catalogRowScrollStates = latestCarouselRows
                .filter { it.globalRowIndex >= 0 }
                .associate { rowState -> rowState.key to (focusedItemByRow[rowState.key] ?: 0) }

            onSaveFocusState(
                latestVerticalRowListState.firstVisibleItemIndex,
                latestVerticalRowListState.firstVisibleItemScrollOffset,
                focusedRowIndex,
                latestActiveItemIndex,
                catalogRowScrollStates
            )
        }
    }

    val portraitBaseWidth = uiState.posterCardWidthDp.dp
    val portraitBaseHeight = uiState.posterCardHeightDp.dp
    // Memoize card dimensions to avoid redundant floating-point math on every recomposition.
    val modernPosterScale = if (useLandscapePosters) 1.34f else 1.08f
    val modernCatalogCardWidth = remember(portraitBaseWidth, useLandscapePosters, modernPosterScale) {
        if (useLandscapePosters) {
            portraitBaseWidth * 1.24f * modernPosterScale
        } else {
            portraitBaseWidth * 0.84f * modernPosterScale
        }
    }
    val modernCatalogCardHeight = remember(modernCatalogCardWidth, portraitBaseHeight, useLandscapePosters, modernPosterScale) {
        if (useLandscapePosters) {
            modernCatalogCardWidth / 1.77f
        } else {
            portraitBaseHeight * 0.84f * modernPosterScale
        }
    }
    val continueWatchingScale = 1.34f
    val continueWatchingCardWidth = remember(portraitBaseWidth, continueWatchingScale) {
        portraitBaseWidth * 1.24f * continueWatchingScale
    }
    val continueWatchingCardHeight = remember(continueWatchingCardWidth) {
        continueWatchingCardWidth / 1.77f
    }

    // Replace BoxWithConstraints (SubcomposeLayout) with a regular Box to
    // eliminate subcomposition overhead.  SubcomposeLayout forces synchronous
    // composition during the layout phase and prevents the Compose compiler
    // from efficiently skipping unchanged children.
    //
    // We use LocalConfiguration as the initial size and refine via onSizeChanged
    // so that non-fullscreen contexts (e.g. dialogs, overlays) self-correct
    // after the first layout pass without a one-frame visual glitch.
    val configuration = LocalConfiguration.current
    val configWidthDp = configuration.screenWidthDp.dp
    val configHeightDp = configuration.screenHeightDp.dp
    val localDensityForSize = LocalDensity.current
    var measuredSize by remember { mutableStateOf<IntSize?>(null) }
    val maxWidth = measuredSize?.let { with(localDensityForSize) { it.width.toDp() } } ?: configWidthDp
    val maxHeight = measuredSize?.let { with(localDensityForSize) { it.height.toDp() } } ?: configHeightDp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (measuredSize != size) {
                    measuredSize = size
                }
            }
    ) {
        val rowHorizontalPadding = 52.dp

        val resolvedHero by remember(heroItem, activeRow, clampedActiveItemIndex) {
            derivedStateOf {
                heroItem
                    ?: activeRow?.items?.getOrNull(clampedActiveItemIndex)?.heroPreview
                    ?: activeRow?.items?.firstOrNull()?.heroPreview
            }
        }
        val activeRowFallbackBackdrop = remember(activeRow?.key, activeRow?.items) {
            activeRow?.items?.firstNotNullOfOrNull { item ->
                item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
            }
        }
        val heroBackdrop by remember(heroItem, resolvedHero, activeRowFallbackBackdrop) {
            derivedStateOf {
                firstNonBlank(
                    resolvedHero?.backdrop,
                    resolvedHero?.imageUrl,
                    resolvedHero?.poster,
                    if (heroItem == null) activeRowFallbackBackdrop else null
                )
            }
        }
        val expandedFocusedSelection by remember(focusedCatalogSelection, expandedCatalogFocusKey) {
            derivedStateOf {
                focusedCatalogSelection?.takeIf { it.focusKey == expandedCatalogFocusKey }
            }
        }
        val heroTrailerUrl by remember(expandedFocusedSelection, trailerPreviewUrls) {
            derivedStateOf {
                expandedFocusedSelection?.payload?.itemId?.let { trailerPreviewUrls[it] }
            }
        }
        val expandedCatalogTrailerUrl = heroTrailerUrl
        val shouldPlayHeroTrailer by remember(
            effectiveAutoplayEnabled,
            trailerPlaybackTarget,
            heroTrailerUrl,
            isVerticalRowsScrolling
        ) {
            derivedStateOf {
                effectiveAutoplayEnabled &&
                    !isVerticalRowsScrolling &&
                    trailerPlaybackTarget == FocusedPosterTrailerPlaybackTarget.HERO_MEDIA &&
                    !heroTrailerUrl.isNullOrBlank()
            }
        }
        var heroTrailerFirstFrameRendered by remember(heroTrailerUrl) { mutableStateOf(false) }
        LaunchedEffect(shouldPlayHeroTrailer) {
            if (!shouldPlayHeroTrailer) {
                heroTrailerFirstFrameRendered = false
            }
        }
        // Keep the State reference (no `by` delegation) so we can pass
        // lambda-based alpha providers to ModernHeroMediaLayer.  The lambdas
        // are read ONLY inside graphicsLayer draw blocks, so changes to the
        // animated progress never trigger recomposition of the hero layer or
        // any of its children — only a cheap draw-phase update occurs.
        // Over a 480 ms crossfade at 60 fps this eliminates ~29 full
        // recomposition passes of the hero media tree.
        val heroTransitionProgressState: State<Float> = animateFloatAsState(
            targetValue = if (shouldPlayHeroTrailer && heroTrailerFirstFrameRendered) 1f else 0f,
            animationSpec = tween(durationMillis = 480),
            label = "heroBackdropTrailerCrossfadeProgress"
        )
        val catalogBottomPadding = 0.dp
        val heroToCatalogGap = 16.dp
        val rowTitleBottom = 14.dp
        val rowsViewportHeightFraction = if (useLandscapePosters) 0.50f else 0.54f
        val rowsViewportHeight = maxHeight * rowsViewportHeightFraction
        val localDensity = LocalDensity.current
        val verticalRowBringIntoViewSpec = remember(localDensity, defaultBringIntoViewSpec) {
            val topInsetPx = with(localDensity) { MODERN_ROW_HEADER_FOCUS_INSET.toPx() }
            object : BringIntoViewSpec {
                @Suppress("DEPRECATION")
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float = offset - topInsetPx
            }
        }
        val bgColor = NuvioColors.Background
        val heroMediaWidthPx = remember(maxWidth, localDensity) {
            with(localDensity) { (maxWidth * 0.75f).roundToPx() }
        }
        val heroMediaHeightPx = remember(maxHeight, localDensity) {
            with(localDensity) { (maxHeight * MODERN_HERO_BACKDROP_HEIGHT_FRACTION).roundToPx() }
        }

        val heroMediaModifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = 56.dp)
            .fillMaxWidth(0.75f)
            .fillMaxHeight(MODERN_HERO_BACKDROP_HEIGHT_FRACTION)

        // Stabilize hero media callbacks to avoid recomposing ModernHeroMediaLayer
        // when unrelated parent state (e.g. row focus) changes.
        val stableHeroTrailerEnded = remember {
            { expandedCatalogFocusKey = null }
        }
        // heroTrailerFirstFrameRendered is keyed on heroTrailerUrl so its backing
        // MutableState changes — mirror that key here to capture the current setter.
        val stableHeroFirstFrameRendered = remember(heroTrailerUrl) {
            { heroTrailerFirstFrameRendered = true }
        }

        // Stable lambda references that read heroTransitionProgressState only
        // inside graphicsLayer (draw phase).  Using remember ensures the same
        // lambda instance is passed across recompositions.
        val stableBackdropAlphaProvider: () -> Float = remember {
            { 1f - heroTransitionProgressState.value }
        }
        val stableTrailerAlphaProvider: () -> Float = remember {
            { heroTransitionProgressState.value }
        }

        ModernHeroMediaLayer(
            heroBackdrop = heroBackdrop,
            heroBackdropAlphaProvider = stableBackdropAlphaProvider,
            shouldPlayHeroTrailer = shouldPlayHeroTrailer,
            heroTrailerUrl = heroTrailerUrl,
            heroTrailerAlphaProvider = stableTrailerAlphaProvider,
            muted = uiState.focusedPosterBackdropTrailerMuted,
            bgColor = bgColor,
            onTrailerEnded = stableHeroTrailerEnded,
            onFirstFrameRendered = stableHeroFirstFrameRendered,
            modifier = heroMediaModifier,
            requestWidthPx = heroMediaWidthPx,
            requestHeightPx = heroMediaHeightPx
        )
        // Combine dim, left gradient, and bottom gradient into a single draw pass
        // to reduce composable count (3 Boxes -> 1) and avoid per-frame layout overhead.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val dimBrush = bgColor.copy(alpha = 0.08f)
                    val leftGradient = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to bgColor.copy(alpha = 0.96f),
                            0.18f to bgColor.copy(alpha = 0.86f),
                            0.31f to bgColor.copy(alpha = 0.70f),
                            0.40f to bgColor.copy(alpha = 0.55f),
                            0.48f to bgColor.copy(alpha = 0.38f),
                            0.56f to bgColor.copy(alpha = 0.22f),
                            0.66f to Color.Transparent,
                            1.0f to Color.Transparent
                        )
                    )
                    val bottomGradient = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.44f to Color.Transparent,
                            0.62f to bgColor.copy(alpha = 0.38f),
                            0.78f to bgColor.copy(alpha = 0.74f),
                            0.92f to bgColor.copy(alpha = 0.94f),
                            1.0f to bgColor.copy(alpha = 1.0f)
                        )
                    )
                    onDrawBehind {
                        drawRect(color = dimBrush, size = size)
                        drawRect(brush = leftGradient, size = size)
                        drawRect(brush = bottomGradient, size = size)
                    }
                }
        )

        HeroTitleBlock(
            preview = resolvedHero,
            portraitMode = !useLandscapePosters,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = rowHorizontalPadding,
                    end = 48.dp,
                    bottom = catalogBottomPadding + rowsViewportHeight + heroToCatalogGap
                )
                .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
        )

        CompositionLocalProvider(LocalBringIntoViewSpec provides verticalRowBringIntoViewSpec) {
            LazyColumn(
                state = verticalRowListState,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(rowsViewportHeight)
                    .padding(bottom = catalogBottomPadding),
                contentPadding = PaddingValues(bottom = 0.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                beyondBoundsItemCount = 2
            ) {
                itemsIndexed(
                    items = carouselRows,
                    key = { _, row -> row.key },
                    contentType = { _, _ -> "modern_home_row" }
                ) { _, row ->
                    val stableOnPendingRowFocusCleared = remember {
                        {
                            pendingRowFocusKey = null
                            pendingRowFocusIndex = null
                        }
                    }
                    // Stabilize all callback lambdas passed to ModernRowSection to prevent
                    // unnecessary recomposition of child rows when the parent recomposes
                    // due to unrelated state changes (e.g. hero backdrop change).
                    val stableOnRowItemFocused = remember {
                        { rowKey: String, index: Int, isContinueWatchingRow: Boolean ->
                            val rowBecameActive = activeRowKey != rowKey
                            if (focusedItemByRow[rowKey] != index) {
                                focusedItemByRow[rowKey] = index
                            }
                            if (rowBecameActive) {
                                activeRowKey = rowKey
                            }
                            if (rowBecameActive || activeItemIndex != index) {
                                activeItemIndex = index
                            }
                            if (isContinueWatchingRow) {
                                if (lastFocusedContinueWatchingIndex != index) {
                                    lastFocusedContinueWatchingIndex = index
                                }
                                if (focusedCatalogSelection != null) {
                                    focusedCatalogSelection = null
                                }
                            }
                        }
                    }
                    val stableOnContinueWatchingOptions = remember {
                        { item: ContinueWatchingItem -> optionsItem = item }
                    }
                    val stableOnCatalogSelectionFocused = remember {
                        { selection: FocusedCatalogSelection ->
                            if (focusedCatalogSelection != selection) {
                                focusedCatalogSelection = selection
                            }
                        }
                    }
                    val stableOnBackdropInteraction = remember {
                        { expansionInteractionNonce++ }
                    }
                    val stableOnExpandedCatalogFocusKeyChange = remember {
                        { key: String? -> expandedCatalogFocusKey = key }
                    }
                    ModernRowSection(
                        row = row,
                        rowTitleBottom = rowTitleBottom,
                        defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                        focusStateCatalogRowScrollStates = focusState.catalogRowScrollStates,
                        rowListStates = rowListStates,
                        focusedItemByRow = focusedItemByRow,
                        itemFocusRequesters = itemFocusRequesters,
                        loadMoreRequestedTotals = loadMoreRequestedTotals,
                        requesterFor = stableRequesterFor,
                        pendingRowFocusKey = pendingRowFocusKey,
                        pendingRowFocusIndex = pendingRowFocusIndex,
                        pendingRowFocusNonce = pendingRowFocusNonce,
                        onPendingRowFocusCleared = stableOnPendingRowFocusCleared,
                        onRowItemFocused = stableOnRowItemFocused,
                        useLandscapePosters = useLandscapePosters,
                        showLabels = uiState.posterLabelsEnabled,
                        posterCardCornerRadius = uiState.posterCardCornerRadiusDp.dp,
                        focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                        effectiveExpandEnabled = effectiveExpandEnabled,
                        effectiveAutoplayEnabled = effectiveAutoplayEnabled,
                        trailerPlaybackTarget = trailerPlaybackTarget,
                        expandedCatalogFocusKey = expandedCatalogFocusKey,
                        expandedTrailerPreviewUrl = expandedCatalogTrailerUrl,
                        modernCatalogCardWidth = modernCatalogCardWidth,
                        modernCatalogCardHeight = modernCatalogCardHeight,
                        continueWatchingCardWidth = continueWatchingCardWidth,
                        continueWatchingCardHeight = continueWatchingCardHeight,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onContinueWatchingOptions = stableOnContinueWatchingOptions,
                        isCatalogItemWatched = isCatalogItemWatched,
                        onCatalogItemLongPress = onCatalogItemLongPress,
                        onCatalogSelectionFocused = stableOnCatalogSelectionFocused,
                        onNavigateToDetail = onNavigateToDetail,
                        onLoadMoreCatalog = onLoadMoreCatalog,
                        onBackdropInteraction = stableOnBackdropInteraction,
                        onExpandedCatalogFocusKeyChange = stableOnExpandedCatalogFocusKeyChange
                    )
                }
            }
        }
    }

    val selectedOptionsItem = optionsItem
    if (selectedOptionsItem != null) {
        ContinueWatchingOptionsDialog(
            item = selectedOptionsItem,
            onDismiss = { optionsItem = null },
            onRemove = {
                val targetIndex = if (uiState.continueWatchingItems.size <= 1) {
                    null
                } else {
                    minOf(lastFocusedContinueWatchingIndex, uiState.continueWatchingItems.size - 2)
                        .coerceAtLeast(0)
                }
                pendingRowFocusKey = if (targetIndex != null) "continue_watching" else null
                pendingRowFocusIndex = targetIndex
                pendingRowFocusNonce++
                onRemoveContinueWatching(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.season(),
                    selectedOptionsItem.episode(),
                    selectedOptionsItem is ContinueWatchingItem.NextUp
                )
                optionsItem = null
            },
            onDetails = {
                onNavigateToDetail(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.contentType(),
                    ""
                )
                optionsItem = null
            }
        )
    }
}
