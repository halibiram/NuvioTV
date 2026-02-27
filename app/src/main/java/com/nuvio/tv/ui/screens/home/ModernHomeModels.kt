package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nuvio.tv.domain.model.MetaPreview

internal val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
internal const val MODERN_HERO_TEXT_WIDTH_FRACTION = 0.42f
internal const val MODERN_HERO_BACKDROP_HEIGHT_FRACTION = 0.62f
internal const val MODERN_TRAILER_OVERSCAN_ZOOM = 1.35f
internal const val MODERN_HERO_FOCUS_DEBOUNCE_MS = 90L
internal val MODERN_ROW_HEADER_FOCUS_INSET = 56.dp
internal val MODERN_LANDSCAPE_LOGO_GRADIENT = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color.Transparent,
        0.58f to Color.Transparent,
        1.0f to Color.Black.copy(alpha = 0.75f)
    )
)

@Immutable
internal data class HeroPreview(
    val title: String,
    val logo: String?,
    val description: String?,
    val contentTypeText: String?,
    val yearText: String?,
    val imdbText: String?,
    val genres: List<String>,
    val poster: String?,
    val backdrop: String?,
    val imageUrl: String?
)

@Immutable
internal sealed class ModernPayload {
    data class ContinueWatching(val item: ContinueWatchingItem) : ModernPayload()
    data class Catalog(
        val focusKey: String,
        val itemId: String,
        val itemType: String,
        val addonBaseUrl: String,
        val trailerTitle: String,
        val trailerReleaseInfo: String?,
        val trailerApiType: String
    ) : ModernPayload()
}

@Immutable
internal data class FocusedCatalogSelection(
    val focusKey: String,
    val payload: ModernPayload.Catalog
)

@Immutable
internal data class ModernCarouselItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val heroPreview: HeroPreview,
    val payload: ModernPayload,
    val metaPreview: MetaPreview? = null
)

@Immutable
internal data class HeroCarouselRow(
    val key: String,
    val title: String,
    val globalRowIndex: Int,
    val items: List<ModernCarouselItem>,
    val catalogId: String? = null,
    val addonId: String? = null,
    val apiType: String? = null,
    val supportsSkip: Boolean = false,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false
)

@Immutable
internal data class CarouselRowLookups(
    val rowIndexByKey: Map<String, Int>,
    val rowByKey: Map<String, HeroCarouselRow>,
    val activeRowKeys: Set<String>,
    val activeItemKeysByRow: Map<String, Set<String>>,
    val activeCatalogItemIds: Set<String>
)

internal data class ModernCatalogRowBuildCacheEntry(
    val source: CatalogRow,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val mappedRow: HeroCarouselRow
)

/**
 * A non-snapshot mutable container. Writing to [value] does NOT trigger recomposition,
 * making it ideal for values that are only read inside callbacks or LaunchedEffects
 * (e.g., key-repeat timestamps, last-focused indices used only in side-effects).
 */
@Stable
internal class Ref<T>(var value: T)

@Stable
internal class ModernHomeUiCaches {
    val focusedItemByRow = mutableMapOf<String, Int>()
    val itemFocusRequesters = mutableMapOf<String, MutableMap<String, FocusRequester>>()
    val rowListStates = mutableMapOf<String, LazyListState>()
    val loadMoreRequestedTotals = mutableMapOf<String, Int>()

    fun requesterFor(rowKey: String, itemKey: String): FocusRequester {
        val byIndex = itemFocusRequesters.getOrPut(rowKey) { mutableMapOf() }
        return byIndex.getOrPut(itemKey) { FocusRequester() }
    }
}

/**
 * Bundles infrequently-changing configuration that every row and card needs.
 * Because all fields are vals and the class is @Stable, the Compose compiler
 * treats it as a single stable parameter – preventing cascading recompositions
 * when unrelated state (focus key, hero item, etc.) changes in the parent.
 */
@Immutable
internal data class ModernRowSharedConfig(
    val useLandscapePosters: Boolean,
    val showLabels: Boolean,
    val posterCardCornerRadius: Dp,
    val modernCatalogCardWidth: Dp,
    val modernCatalogCardHeight: Dp,
    val continueWatchingCardWidth: Dp,
    val continueWatchingCardHeight: Dp,
    val focusedPosterBackdropTrailerMuted: Boolean,
    val effectiveExpandEnabled: Boolean,
    val effectiveAutoplayEnabled: Boolean,
    val trailerPlaybackTarget: FocusedPosterTrailerPlaybackTarget
)

/**
 * Observable holder for the currently-expanded catalog focus key.
 * Using a @Stable class with mutableStateOf allows individual row items
 * to observe only the expansion state change they care about, instead of
 * receiving the key as a parameter (which forces ALL items to recompose
 * whenever the expanded key changes).
 */
@Stable
internal class ExpandedCatalogState {
    var focusKey: String? by mutableStateOf(null)
}

/**
 * Observable holder for trailer preview URLs keyed by item ID.
 * Rows read from this holder instead of receiving the full map as a parameter,
 * which avoids recomposing every row when a single trailer URL is resolved.
 */
@Stable
internal class TrailerPreviewState {
    var urls: Map<String, String> by mutableStateOf(emptyMap())
}

/**
 * Observable holder for pending row focus restoration.
 *
 * Previously, pendingRowFocusKey / pendingRowFocusIndex / pendingRowFocusNonce
 * were passed as direct parameters to **every** ModernRowSection, causing ALL
 * visible rows to recompose whenever focus moved. Now each row reads from this
 * @Stable holder; only the row whose key matches the pending key actually
 * recomposes, and the rest skip entirely.
 */
@Stable
internal class PendingRowFocusHolder {
    var key: String? by mutableStateOf(null)
    var index: Int? by mutableStateOf(null)
    var nonce: Int by mutableIntStateOf(0)

    fun request(rowKey: String?, itemIndex: Int?) {
        key = rowKey
        index = itemIndex
        nonce++
    }

    fun clear() {
        key = null
        index = null
    }
}

/**
 * Observable holder for the focused catalog selection.
 *
 * Previously stored as a raw `mutableStateOf` in the parent composable, every
 * D-pad move changed this value and triggered recomposition of the entire
 * ModernHomeContent (including ALL rows). Now rows and the hero section read
 * from this @Stable holder, so only composables that actually READ the value
 * are invalidated when it changes.
 */
@Stable
internal class FocusedCatalogSelectionHolder {
    var selection: FocusedCatalogSelection? by mutableStateOf(null)

    fun update(newSelection: FocusedCatalogSelection) {
        if (selection != newSelection) {
            selection = newSelection
        }
    }

    fun clearIfNotIn(activeCatalogItemIds: Set<String>) {
        val current = selection ?: return
        if (current.payload.itemId !in activeCatalogItemIds) {
            selection = null
        }
    }
}

/**
 * Observable holder for the active row key and item index.
 *
 * Prevents full parent recomposition on every D-pad row/item change.
 * Row sections read activeRowKey from here to determine if they are the
 * active row, and the hero section reads activeItemIndex for hero preview
 * resolution — all without forcing sibling rows to recompose.
 */
@Stable
internal class ActiveRowState {
    var rowKey: String? by mutableStateOf(null)
    var itemIndex: Int by mutableIntStateOf(0)
}

/**
 * Observable holder for the hero preview displayed in the backdrop.
 *
 * Previously a raw `mutableStateOf` in the parent, hero changes cascaded
 * recomposition to rows even though rows don't read the hero. Now the hero
 * media layer reads from this holder independently.
 */
@Stable
internal class HeroPreviewHolder {
    var preview: HeroPreview? by mutableStateOf(null)
}

internal class ModernCarouselRowBuildCache {
    var continueWatchingItems: List<ContinueWatchingItem> = emptyList()
    var continueWatchingTitle: String = ""
    var continueWatchingAirsDateTemplate: String = ""
    var continueWatchingUpcomingLabel: String = ""
    var continueWatchingUseLandscapePosters: Boolean = false
    var continueWatchingRow: HeroCarouselRow? = null
    val catalogRows = mutableMapOf<String, ModernCatalogRowBuildCacheEntry>()
}


internal fun buildContinueWatchingItem(
    item: ContinueWatchingItem,
    useLandscapePosters: Boolean,
    airsDateTemplate: String,
    upcomingLabel: String
): ModernCarouselItem {
    val heroPreview = when (item) {
        is ContinueWatchingItem.InProgress -> HeroPreview(
            title = item.progress.name,
            logo = item.progress.logo,
            description = item.episodeDescription ?: item.progress.episodeTitle,
            contentTypeText = item.progress.contentType.replaceFirstChar { ch -> ch.uppercase() },
            yearText = null,
            imdbText = null,
            genres = emptyList(),
            poster = item.progress.poster,
            backdrop = item.progress.backdrop,
            imageUrl = if (useLandscapePosters) {
                item.progress.backdrop ?: item.progress.poster
            } else {
                item.progress.poster ?: item.progress.backdrop
            }
        )
        is ContinueWatchingItem.NextUp -> HeroPreview(
            title = item.info.name,
            logo = item.info.logo,
            description = item.info.episodeDescription
                ?: item.info.episodeTitle
                ?: item.info.airDateLabel?.let { airsDateTemplate.format(it) },
            contentTypeText = item.info.contentType.replaceFirstChar { ch -> ch.uppercase() },
            yearText = null,
            imdbText = null,
            genres = emptyList(),
            poster = item.info.poster,
            backdrop = item.info.backdrop,
            imageUrl = if (useLandscapePosters) {
                firstNonBlank(item.info.backdrop, item.info.poster, item.info.thumbnail)
            } else {
                firstNonBlank(item.info.poster, item.info.backdrop, item.info.thumbnail)
            }
        )
    }

    val imageUrl = when (item) {
        is ContinueWatchingItem.InProgress -> if (useLandscapePosters) {
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(item.episodeThumbnail, item.progress.poster, item.progress.backdrop)
            } else {
                firstNonBlank(item.progress.backdrop, item.progress.poster)
            }
        } else {
            if (isSeriesType(item.progress.contentType)) {
                firstNonBlank(heroPreview.poster, item.progress.poster, item.progress.backdrop)
            } else {
                firstNonBlank(item.progress.poster, item.progress.backdrop)
            }
        }
        is ContinueWatchingItem.NextUp -> if (useLandscapePosters) {
            if (item.info.hasAired) {
                firstNonBlank(item.info.thumbnail, item.info.poster, item.info.backdrop)
            } else {
                firstNonBlank(item.info.backdrop, item.info.poster, item.info.thumbnail)
            }
        } else {
            firstNonBlank(item.info.poster, item.info.backdrop, item.info.thumbnail)
        }
    }

    return ModernCarouselItem(
        key = continueWatchingItemKey(item),
        title = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.name
            is ContinueWatchingItem.NextUp -> item.info.name
        },
        subtitle = when (item) {
            is ContinueWatchingItem.InProgress -> item.progress.episodeDisplayString ?: item.progress.episodeTitle
            is ContinueWatchingItem.NextUp -> {
                val code = "S${item.info.season}E${item.info.episode}"
                if (item.info.hasAired) {
                    code
                } else {
                    item.info.airDateLabel?.let { "$code • ${airsDateTemplate.format(it)}" } ?: "$code • $upcomingLabel"
                }
            }
        },
        imageUrl = imageUrl,
        heroPreview = heroPreview.copy(imageUrl = imageUrl ?: heroPreview.imageUrl),
        payload = ModernPayload.ContinueWatching(item)
    )
}

internal fun buildCatalogItem(
    item: MetaPreview,
    row: CatalogRow,
    useLandscapePosters: Boolean,
    occurrence: Int
): ModernCarouselItem {
    val heroPreview = HeroPreview(
        title = item.name,
        logo = item.logo,
        description = item.description,
        contentTypeText = item.apiType.replaceFirstChar { ch -> ch.uppercase() },
        yearText = extractYear(item.releaseInfo),
        imdbText = item.imdbRating?.let { String.format("%.1f", it) },
        genres = item.genres.take(3),
        poster = item.poster,
        backdrop = item.background,
        imageUrl = if (useLandscapePosters) {
            item.background ?: item.poster
        } else {
            item.poster ?: item.background
        }
    )

    return ModernCarouselItem(
        key = "catalog_${row.key()}_${item.id}_${occurrence}",
        title = item.name,
        subtitle = item.releaseInfo,
        imageUrl = if (useLandscapePosters) {
            item.background ?: item.poster
        } else {
            item.poster ?: item.background
        },
        heroPreview = heroPreview,
        payload = ModernPayload.Catalog(
            focusKey = "${row.key()}::${item.id}",
            itemId = item.id,
            itemType = item.apiType,
            addonBaseUrl = row.addonBaseUrl,
            trailerTitle = item.name,
            trailerReleaseInfo = item.releaseInfo,
            trailerApiType = item.apiType
        ),
        metaPreview = item
    )
}

internal fun continueWatchingItemKey(item: ContinueWatchingItem): String {
    return when (item) {
        is ContinueWatchingItem.InProgress ->
            "cw_inprogress_${item.progress.contentId}_${item.progress.videoId}_${item.progress.season ?: -1}_${item.progress.episode ?: -1}"
        is ContinueWatchingItem.NextUp ->
            "cw_nextup_${item.info.contentId}_${item.info.videoId}_${item.info.season}_${item.info.episode}"
    }
}

internal fun catalogRowKey(row: CatalogRow): String {
    return "${row.addonId}_${row.apiType}_${row.catalogId}"
}

internal fun catalogRowTitle(
    row: CatalogRow,
    showCatalogTypeSuffix: Boolean,
    strTypeMovie: String = "",
    strTypeSeries: String = ""
): String {
    val catalogName = row.catalogName.replaceFirstChar { it.uppercase() }
    if (!showCatalogTypeSuffix) return catalogName
    val typeLabel = when (row.apiType.lowercase()) {
        "movie" -> strTypeMovie.ifBlank { row.apiType.replaceFirstChar { it.uppercase() } }
        "series" -> strTypeSeries.ifBlank { row.apiType.replaceFirstChar { it.uppercase() } }
        else -> row.apiType.replaceFirstChar { it.uppercase() }
    }
    return "$catalogName - $typeLabel"
}

internal fun CatalogRow.key(): String {
    return "${addonId}_${apiType}_${catalogId}"
}

internal fun isSeriesType(type: String?): Boolean {
    return type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
}

internal fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}

internal fun extractYear(releaseInfo: String?): String? {
    if (releaseInfo.isNullOrBlank()) return null
    return YEAR_REGEX.find(releaseInfo)?.value
}

internal fun ContinueWatchingItem.contentId(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentId
        is ContinueWatchingItem.NextUp -> info.contentId
    }
}

internal fun ContinueWatchingItem.contentType(): String {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentType
        is ContinueWatchingItem.NextUp -> info.contentType
    }
}

internal fun ContinueWatchingItem.season(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.season
        is ContinueWatchingItem.NextUp -> info.season
    }
}

internal fun ContinueWatchingItem.episode(): Int? {
    return when (this) {
        is ContinueWatchingItem.InProgress -> progress.episode
        is ContinueWatchingItem.NextUp -> info.episode
    }
}
