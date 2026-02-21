package com.nuvio.tv.ui.screens.search

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (catalogId: String, addonId: String, type: String) -> Unit = { _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val voiceFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val discoverFirstItemFocusRequester = remember { FocusRequester() }
    var isSearchFieldAttached by remember { mutableStateOf(false) }
    var focusResults by remember { mutableStateOf(false) }
    var pendingFocusMoveToResultsQuery by remember { mutableStateOf<String?>(null) }
    var pendingFocusMoveSawSearching by remember { mutableStateOf(false) }
    var pendingFocusMoveHadExistingSearchRows by remember { mutableStateOf(false) }
    var pendingVoiceSearchResume by remember { mutableStateOf(false) }
    var discoverFocusedItemIndex by rememberSaveable { mutableStateOf(0) }
    var restoreDiscoverFocus by rememberSaveable { mutableStateOf(false) }
    var pendingDiscoverRestoreOnResume by rememberSaveable { mutableStateOf(false) }
    var showAdvancedDiscover by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    androidx.activity.compose.BackHandler(enabled = showAdvancedDiscover) {
        showAdvancedDiscover = false
        focusResults = false
        viewModel.onEvent(SearchEvent.SelectDiscoverGenre(null))
    }
    val coroutineScope = rememberCoroutineScope()

    // --- Shared callbacks (eliminates duplication between discover / search modes) ---
    fun resetPendingFocusState() {
        pendingFocusMoveToResultsQuery = null
        pendingFocusMoveSawSearching = false
        pendingFocusMoveHadExistingSearchRows = false
    }

    val sharedOnQueryChanged: (String) -> Unit = { query ->
        focusResults = false
        resetPendingFocusState()
        viewModel.onEvent(SearchEvent.QueryChanged(query))
    }

    val sharedOnSubmit: () -> Unit = {
        val submittedQuery = uiState.query.trim()
        viewModel.onEvent(SearchEvent.SubmitSearch)
        focusResults = false
        if (submittedQuery.length >= 2) {
            pendingFocusMoveToResultsQuery = submittedQuery
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows =
                uiState.submittedQuery.trim().length >= 2 && uiState.catalogRows.any { row -> row.items.isNotEmpty() }
        } else {
            resetPendingFocusState()
        }
    }

    val sharedOnMoveToResults: () -> Unit = { focusResults = true }
    val onVoiceQueryResultState = rememberUpdatedState<(String) -> Unit> { recognized ->
        if (recognized.isNotBlank()) {
            viewModel.onEvent(SearchEvent.QueryChanged(recognized))
            viewModel.onEvent(SearchEvent.SubmitSearch)
            focusResults = false
            pendingFocusMoveToResultsQuery = recognized
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows =
                uiState.submittedQuery.trim().length >= 2 && uiState.catalogRows.any { it.items.isNotEmpty() }
        } else {
            Toast.makeText(context, "No speech detected. Try again.", Toast.LENGTH_SHORT).show()
        }
    }
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        pendingVoiceSearchResume = false
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val recognized = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
            .trim()
        onVoiceQueryResultState.value(recognized)
    }
    val voiceIntentAction = remember(context) {
        listOf(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH,
            RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE
        ).firstOrNull { action ->
            Intent(action).resolveActivity(context.packageManager) != null
        }
    }
    val isVoiceSearchAvailable = voiceIntentAction != null
    val topInputFocusRequester = remember(isVoiceSearchAvailable) {
        if (isVoiceSearchAvailable) voiceFocusRequester else searchFocusRequester
    }
    val launchVoiceSearch: () -> Unit = {
        val action = voiceIntentAction
        if (action == null) {
            Toast.makeText(context, "Voice search is unavailable on this device.", Toast.LENGTH_SHORT).show()
        } else {
            val intent = Intent(action).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search")
            }
            pendingVoiceSearchResume = true
            runCatching { voiceLauncher.launch(intent) }.onFailure {
                pendingVoiceSearchResume = false
                Toast.makeText(context, "Voice search is unavailable on this device.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val posterCardStyle = remember(uiState.posterCardWidthDp, uiState.posterCardCornerRadiusDp) {
        val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = computedHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    val trimmedQuery = remember(uiState.query) { uiState.query.trim() }
    val trimmedSubmittedQuery = remember(uiState.submittedQuery) { uiState.submittedQuery.trim() }
    val isDiscoverMode = remember(uiState.discoverEnabled, trimmedSubmittedQuery) {
        uiState.discoverEnabled && trimmedSubmittedQuery.isEmpty()
    }
    val hasPendingUnsubmittedQuery = remember(isDiscoverMode, trimmedQuery, trimmedSubmittedQuery) {
        !isDiscoverMode && trimmedQuery.length >= 2 && trimmedQuery != trimmedSubmittedQuery
    }
    val canMoveToResults = remember(
        isDiscoverMode,
        uiState.discoverResults,
        trimmedSubmittedQuery,
        uiState.catalogRows
    ) {
        if (isDiscoverMode) false else trimmedSubmittedQuery.length >= 2 && uiState.catalogRows.any { it.items.isNotEmpty() }
    }

    LaunchedEffect(focusResults, isDiscoverMode, uiState.discoverResults.size) {
        if (focusResults && isDiscoverMode && uiState.discoverResults.isNotEmpty()) {
            delay(100)
            runCatching { discoverFirstItemFocusRequester.requestFocus() }
            focusResults = false
            resetPendingFocusState()
        }
    }

    LaunchedEffect(
        pendingFocusMoveToResultsQuery,
        pendingFocusMoveSawSearching,
        pendingFocusMoveHadExistingSearchRows,
        uiState.isSearching,
        uiState.submittedQuery,
        canMoveToResults,
        isDiscoverMode
    ) {
        val pendingQuery = pendingFocusMoveToResultsQuery ?: return@LaunchedEffect
        val currentSubmittedQuery = uiState.submittedQuery.trim()
        if (currentSubmittedQuery != pendingQuery) return@LaunchedEffect

        if (uiState.isSearching) {
            pendingFocusMoveSawSearching = true
            return@LaunchedEffect
        }

        val shouldRequireSeenSearching = pendingFocusMoveHadExistingSearchRows
        if ((shouldRequireSeenSearching && !pendingFocusMoveSawSearching) || !canMoveToResults) {
            return@LaunchedEffect
        }

        if (isDiscoverMode) {
            focusResults = true
        } else {
            // Use explicit first-item focus for deterministic landing on row 1 / column 1.
            delay(80)
            focusResults = true
        }
        resetPendingFocusState()
    }

    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { topInputFocusRequester.requestFocus() }
    }

    val latestPendingDiscoverRestore by rememberUpdatedState(pendingDiscoverRestoreOnResume)
    val latestShouldKeepSearchFocus by rememberUpdatedState(
        focusResults || uiState.isSearching || pendingVoiceSearchResume
    )
    val latestVoiceSearchAvailable by rememberUpdatedState(isVoiceSearchAvailable)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (latestPendingDiscoverRestore) {
                    restoreDiscoverFocus = true
                    pendingDiscoverRestoreOnResume = false
                } else if (!latestShouldKeepSearchFocus) {
                    coroutineScope.launch {
                        repeat(2) { withFrameNanos { } }
                        runCatching {
                            if (latestVoiceSearchAvailable) {
                                voiceFocusRequester.requestFocus()
                            } else {
                                searchFocusRequester.requestFocus()
                            }
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background),
        contentAlignment = Alignment.TopCenter
    ) {
        if (isDiscoverMode) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp)
            ) {
                SearchInputField(
                    query = uiState.query,
                    canMoveToResults = canMoveToResults,
                    voiceFocusRequester = if (isVoiceSearchAvailable) voiceFocusRequester else null,
                    searchFocusRequester = searchFocusRequester,
                    onAttached = { isSearchFieldAttached = true },
                    onQueryChanged = sharedOnQueryChanged,
                    onSubmit = sharedOnSubmit,
                    showVoiceSearch = isVoiceSearchAvailable,
                    onVoiceSearch = launchVoiceSearch,
                    onMoveToResults = sharedOnMoveToResults,
                    keyboardController = keyboardController
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (showAdvancedDiscover || uiState.selectedDiscoverGenre != null) {
                    DiscoverSection(
                        uiState = uiState,
                        posterCardStyle = posterCardStyle,
                        focusResults = focusResults,
                        firstItemFocusRequester = discoverFirstItemFocusRequester,
                        focusedItemIndex = discoverFocusedItemIndex,
                        shouldRestoreFocusedItem = restoreDiscoverFocus,
                        onRestoreFocusedItemHandled = { restoreDiscoverFocus = false },
                        onNavigateToDetail = { item, addonBaseUrl ->
                            pendingDiscoverRestoreOnResume = true
                            viewModel.onEvent(SearchEvent.AddRecentlyViewed(
                                com.nuvio.tv.domain.model.SearchHistoryItem(
                                    id = item.id,
                                    type = item.apiType,
                                    title = item.name,
                                    posterUrl = item.poster
                                )
                            ))
                            onNavigateToDetail(item.id, item.apiType, addonBaseUrl)
                        },
                        onDiscoverItemFocused = { index ->
                            discoverFocusedItemIndex = index
                        },
                        onSelectType = { viewModel.onEvent(SearchEvent.SelectDiscoverType(it)) },
                        onSelectCatalog = { viewModel.onEvent(SearchEvent.SelectDiscoverCatalog(it)) },
                        onSelectGenre = { viewModel.onEvent(SearchEvent.SelectDiscoverGenre(it)) },
                        onLoadMore = { viewModel.onEvent(SearchEvent.LoadNextDiscoverResults) },
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                } else {
                    ProgressiveSearchContent(
                        uiState = uiState,
                        posterCardStyle = posterCardStyle,
                        onSearchQuery = sharedOnQueryChanged,
                        onGenreSelected = { genre ->
                            viewModel.onEvent(SearchEvent.SelectDiscoverGenre(genre))
                            showAdvancedDiscover = true
                        },
                        onClearRecentSearches = { viewModel.onEvent(SearchEvent.ClearRecentSearches) },
                        onClearRecentlyViewed = { viewModel.onEvent(SearchEvent.ClearRecentlyViewed) },
                        onNavigateToDetail = { item, addonBaseUrl ->
                            viewModel.onEvent(SearchEvent.AddRecentlyViewed(
                                com.nuvio.tv.domain.model.SearchHistoryItem(
                                    id = item.id,
                                    type = item.apiType,
                                    title = item.name,
                                    posterUrl = item.poster
                                )
                            ))
                            onNavigateToDetail(item.id, item.apiType, addonBaseUrl)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SearchInputField(
                        query = uiState.query,
                        canMoveToResults = canMoveToResults,
                        voiceFocusRequester = if (isVoiceSearchAvailable) voiceFocusRequester else null,
                        searchFocusRequester = searchFocusRequester,
                        onAttached = { isSearchFieldAttached = true },
                        onQueryChanged = sharedOnQueryChanged,
                        onSubmit = sharedOnSubmit,
                        showVoiceSearch = isVoiceSearchAvailable,
                        onVoiceSearch = launchVoiceSearch,
                        onMoveToResults = sharedOnMoveToResults,
                        keyboardController = keyboardController
                    )
                }

                if (trimmedSubmittedQuery.length < 2 || hasPendingUnsubmittedQuery) {
                    item {
                        Text(
                            text = "Press Done on the keyboard to search",
                            style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 52.dp)
                        )
                    }
                }

                when {
                    trimmedSubmittedQuery.length < 2 && !hasPendingUnsubmittedQuery -> {
                        item {
                            EmptyScreenState(
                                title = "Start Searching",
                                subtitle = if (uiState.discoverEnabled) {
                                    "Enter at least 2 characters"
                                } else {
                                    "Discover is disabled. Enter at least 2 characters"
                                },
                                icon = Icons.Default.Search
                            )
                        }
                    }

                    uiState.isSearching && uiState.catalogRows.isEmpty() -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 80.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LoadingIndicator()
                            }
                        }
                    }

                    uiState.error != null && uiState.catalogRows.isEmpty() -> {
                        item {
                            ErrorState(
                                message = uiState.error ?: "Search failed",
                                onRetry = { viewModel.onEvent(SearchEvent.Retry) }
                            )
                        }
                    }

                    uiState.catalogRows.isEmpty() || uiState.catalogRows.none { it.items.isNotEmpty() } -> {
                        item {
                            EmptyScreenState(
                                title = "No Results",
                                subtitle = "Try searching with different keywords",
                                icon = Icons.Default.Search
                            )
                        }
                    }

                    else -> {
                        val visibleCatalogRows = uiState.catalogRows.filter { it.items.isNotEmpty() }

                        itemsIndexed(
                            items = visibleCatalogRows,
                            key = { index, item ->
                                "${item.addonId}_${item.type}_${item.catalogId}_${trimmedSubmittedQuery}_$index"
                            }
                        ) { index, catalogRow ->
                            CatalogRowSection(
                                catalogRow = catalogRow,
                                showPosterLabels = uiState.posterLabelsEnabled,
                                showAddonName = uiState.catalogAddonNameEnabled,
                                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                                enableRowFocusRestorer = false,
                                focusedItemIndex = if (focusResults && index == 0) 0 else -1,
                                onItemFocused = {
                                    if (focusResults) {
                                        focusResults = false
                                    }
                                },
                                upFocusRequester = if (index == 0 && isSearchFieldAttached) topInputFocusRequester else null,
                                onItemClick = { id, type, addonBaseUrl ->
                                    val item = catalogRow.items.find { it.id == id && it.apiType == type }
                                    if (item != null) {
                                        viewModel.onEvent(SearchEvent.AddRecentlyViewed(
                                            com.nuvio.tv.domain.model.SearchHistoryItem(
                                                id = id,
                                                type = type,
                                                title = item.name,
                                                posterUrl = item.poster
                                            )
                                        ))
                                    }
                                    onNavigateToDetail(id, type, addonBaseUrl)
                                },
                                onSeeAll = {
                                    onNavigateToSeeAll(
                                        catalogRow.catalogId,
                                        catalogRow.addonId,
                                        catalogRow.apiType
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchInputField(
    query: String,
    canMoveToResults: Boolean,
    voiceFocusRequester: FocusRequester?,
    searchFocusRequester: FocusRequester,
    onAttached: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    showVoiceSearch: Boolean,
    onVoiceSearch: () -> Unit,
    onMoveToResults: () -> Unit,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp)
            .onGloballyPositioned { onAttached() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showVoiceSearch) {
            androidx.tv.material3.IconButton(
                onClick = onVoiceSearch,
                modifier = Modifier
                    .then(
                        if (voiceFocusRequester != null) {
                            Modifier.focusRequester(voiceFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                    .size(56.dp),
                colors = androidx.tv.material3.IconButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContainerColor = NuvioColors.FocusBackground,
                    focusedContentColor = NuvioColors.TextPrimary
                ),
                scale = androidx.tv.material3.IconButtonDefaults.scale(focusedScale = 1.1f),
                border = androidx.tv.material3.IconButtonDefaults.border(
                    border = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(1.dp, NuvioColors.Border),
                        shape = RoundedCornerShape(12.dp)
                    ),
                    focusedBorder = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(12.dp)
                    )
                ),
                shape = androidx.tv.material3.IconButtonDefaults.shape(shape = RoundedCornerShape(12.dp))
            ) {
                androidx.tv.material3.Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Voice search"
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .weight(1f)
                .focusRequester(searchFocusRequester)
                .onPreviewKeyEvent { keyEvent ->
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                onSubmit()
                            }
                            return@onPreviewKeyEvent true
                        }

                        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (canMoveToResults) {
                                if (keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                    onMoveToResults()
                                }
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                    false
                },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default.copy(
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    onSubmit()
                    keyboardController?.hide()
                }
            ),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = {
                androidx.tv.material3.Text(
                    text = "Search movies & series",
                    color = NuvioColors.TextTertiary
                )
            },
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = NuvioColors.BackgroundCard,
                unfocusedContainerColor = NuvioColors.BackgroundCard,
                focusedIndicatorColor = NuvioColors.FocusRing,
                unfocusedIndicatorColor = NuvioColors.Border,
                focusedTextColor = NuvioColors.TextPrimary,
                unfocusedTextColor = NuvioColors.TextPrimary,
                cursorColor = NuvioColors.FocusRing
            )
        )
    }
}

@Composable
private fun ProgressiveSearchContent(
    uiState: SearchUiState,
    posterCardStyle: PosterCardStyle,
    onSearchQuery: (String) -> Unit,
    onGenreSelected: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    onClearRecentlyViewed: () -> Unit,
    onNavigateToDetail: (MetaPreview, String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        if (uiState.recentSearches.isNotEmpty()) {
            item(key = "recent_searches") {
                RecentSearchChips(
                    recentSearches = uiState.recentSearches,
                    onSearchQuery = onSearchQuery,
                    onClearHistory = onClearRecentSearches
                )
            }
        }

        if (uiState.recentlyViewed.isNotEmpty()) {
            item(key = "recently_viewed") {
                RecentlyViewedRow(
                    recentlyViewed = uiState.recentlyViewed,
                    posterCardStyle = posterCardStyle,
                    onItemClick = onNavigateToDetail,
                    onClearHistory = onClearRecentlyViewed
                )
            }
        }

        if (uiState.availableGenres.isNotEmpty()) {
            item(key = "quick_search_genres") {
                GenreChipsGrid(
                    genres = uiState.availableGenres,
                    onGenreSelected = onGenreSelected
                )
            }
        }
    }
}

@Composable
private fun RecentSearchChips(
    recentSearches: List<String>,
    onSearchQuery: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.tv.material3.Text(
                text = "Recent Searches",
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary
            )
            androidx.tv.material3.Button(
                onClick = onClearHistory,
                colors = androidx.tv.material3.ButtonDefaults.colors(
                    containerColor = NuvioColors.SurfaceVariant,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContainerColor = NuvioColors.FocusBackground,
                    focusedContentColor = NuvioColors.TextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                shape = androidx.tv.material3.ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                border = androidx.tv.material3.ButtonDefaults.border(
                    border = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Transparent),
                        shape = RoundedCornerShape(50)
                    ),
                    focusedBorder = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(50)
                    )
                ),
                scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1.08f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    androidx.tv.material3.Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Clear",
                        modifier = Modifier.size(16.dp)
                    )
                    androidx.tv.material3.Text(
                        "Clear",
                        style = androidx.tv.material3.MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(recentSearches, key = { it }) { query ->
                var isChipFocused by androidx.compose.runtime.remember { mutableStateOf(false) }
                androidx.tv.material3.Surface(
                    onClick = { onSearchQuery(query) },
                    modifier = Modifier.onFocusChanged { state -> isChipFocused = state.isFocused },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = NuvioColors.SurfaceVariant,
                        contentColor = NuvioColors.TextSecondary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.TextPrimary
                    ),
                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                    border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                        border = androidx.tv.material3.Border(
                            border = androidx.compose.foundation.BorderStroke(1.dp, NuvioColors.Border),
                            shape = RoundedCornerShape(50)
                        ),
                        focusedBorder = androidx.tv.material3.Border(
                            border = androidx.compose.foundation.BorderStroke(2.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(50)
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.tv.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        androidx.tv.material3.Text(
                            text = query,
                            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentlyViewedRow(
    recentlyViewed: List<com.nuvio.tv.domain.model.SearchHistoryItem>,
    posterCardStyle: PosterCardStyle,
    onItemClick: (MetaPreview, String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.tv.material3.Text(
                text = "Recently Viewed",
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                color = NuvioColors.TextPrimary
            )
            androidx.tv.material3.Button(
                onClick = onClearHistory,
                colors = androidx.tv.material3.ButtonDefaults.colors(
                    containerColor = NuvioColors.SurfaceVariant,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContainerColor = NuvioColors.FocusBackground,
                    focusedContentColor = NuvioColors.TextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                shape = androidx.tv.material3.ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                border = androidx.tv.material3.ButtonDefaults.border(
                    border = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Transparent),
                        shape = RoundedCornerShape(50)
                    ),
                    focusedBorder = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(50)
                    )
                ),
                scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1.08f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    androidx.tv.material3.Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Clear",
                        modifier = Modifier.size(16.dp)
                    )
                    androidx.tv.material3.Text(
                        "Clear",
                        style = androidx.tv.material3.MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(recentlyViewed, key = { "${it.id}_${it.type}" }) { historyItem ->
                val preview = remember(historyItem) {
                    MetaPreview(
                        id = historyItem.id,
                        type = ContentType.fromString(historyItem.type),
                        rawType = historyItem.type,
                        name = historyItem.title,
                        poster = historyItem.posterUrl,
                        posterShape = PosterShape.POSTER,
                        description = null,
                        logo = null,
                        background = null,
                        imdbRating = null,
                        releaseInfo = null,
                        genres = emptyList()
                    )
                }
                ContentCard(
                    item = preview,
                    posterCardStyle = posterCardStyle,
                    showLabels = true,
                    onClick = { onItemClick(preview, "") },
                    focusedPosterBackdropExpandEnabled = false,
                    focusedPosterBackdropTrailerEnabled = false,
                    trailerPreviewUrl = null
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GenreChipsGrid(
    genres: List<String>,
    onGenreSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAll by remember { mutableStateOf(false) }
    val visibleGenres = remember(genres, showAll) {
        if (showAll) genres
        else genres.filter { genre ->
            val isHistory = genre.equals("history", ignoreCase = true)
            val isYear = genre.matches(Regex("""^(19|20)\d{2}$"""))
            !isHistory && !isYear
        }
    }
    val hasHidden = genres.size > visibleGenres.size
    
    val expandedFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var itemToFocus by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(itemToFocus) {
        if (itemToFocus != null) {
            kotlinx.coroutines.delay(100)
            try { expandedFocusRequester.requestFocus() } catch (e: Exception) {}
            itemToFocus = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp)
    ) {
        androidx.tv.material3.Text(
            text = "Quick Search",
            style = androidx.tv.material3.MaterialTheme.typography.titleLarge,
            color = NuvioColors.TextPrimary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            visibleGenres.forEach { genre ->
                val emoji = getGenreEmoji(genre)
                var isFocused by remember { mutableStateOf(false) }
                
                androidx.tv.material3.Surface(
                    onClick = { onGenreSelected(genre) },
                    modifier = Modifier
                        .onFocusChanged { state -> isFocused = state.isFocused }
                        .then(if (itemToFocus != null && genre == itemToFocus) Modifier.focusRequester(expandedFocusRequester) else Modifier),
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = NuvioColors.SurfaceVariant,
                        contentColor = NuvioColors.TextPrimary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.TextPrimary
                    ),
                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(
                        focusedScale = 1.08f
                    ),
                    border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                        border = androidx.tv.material3.Border(
                            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Transparent),
                            shape = RoundedCornerShape(50)
                        ),
                        focusedBorder = androidx.tv.material3.Border(
                            border = androidx.compose.foundation.BorderStroke(3.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(50)
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f), androidx.compose.foundation.shape.CircleShape)
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.tv.material3.Text(
                                text = emoji,
                                style = androidx.tv.material3.MaterialTheme.typography.titleMedium
                            )
                        }
                        androidx.tv.material3.Text(
                            text = genre,
                            style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
            }
            
            if (hasHidden && !showAll) {
                var isFocused by remember { mutableStateOf(false) }
                
                androidx.tv.material3.Surface(
                    onClick = { 
                        itemToFocus = visibleGenres.lastOrNull()
                        showAll = true 
                    },
                    modifier = Modifier.onFocusChanged { state -> isFocused = state.isFocused },
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = NuvioColors.SurfaceVariant,
                        contentColor = NuvioColors.TextPrimary,
                        focusedContainerColor = NuvioColors.FocusBackground,
                        focusedContentColor = NuvioColors.TextPrimary
                    ),
                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(
                        focusedScale = 1.08f
                    ),
                    border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                        border = androidx.tv.material3.Border(
                            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Transparent),
                            shape = RoundedCornerShape(50)
                        ),
                        focusedBorder = androidx.tv.material3.Border(
                            border = androidx.compose.foundation.BorderStroke(3.dp, NuvioColors.FocusRing),
                            shape = RoundedCornerShape(50)
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f), androidx.compose.foundation.shape.CircleShape)
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.tv.material3.Text(
                                text = "➕",
                                style = androidx.tv.material3.MaterialTheme.typography.titleMedium
                            )
                        }
                        androidx.tv.material3.Text(
                            text = "More",
                            style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun getGenreEmoji(genre: String): String {
    return when (genre.lowercase()) {
        "action" -> "🎬"
        "adventure" -> "🌋"
        "animation" -> "🎨"
        "anime" -> "🥷"
        "comedy" -> "😂"
        "crime" -> "🕵️"
        "documentary" -> "🎥"
        "drama" -> "🎭"
        "family" -> "👨‍👩‍👧‍👦"
        "fantasy" -> "🧝"
        "history" -> "🏛️"
        "horror" -> "🧟"
        "music", "musical" -> "🎵"
        "mystery" -> "🔍"
        "romance" -> "❤️"
        "science fiction", "sci-fi" -> "🚀"
        "thriller" -> "😱"
        "war" -> "⚔️"
        "western" -> "🤠"
        "sport", "sports" -> "⚽"
        "kids" -> "👶"
        "news" -> "📰"
        "reality" -> "📺"
        "talk" -> "🗣️"
        "politics", "political" -> "⚖️"
        "action & adventure" -> "⚔️"
        "sci-fi & fantasy" -> "🛸"
        "biography" -> "📖"
        "indie" -> "🎸"
        "short" -> "⏳"
        "superhero" -> "🦸"
        "food" -> "🍔"
        "nature" -> "🌿"
        else -> "🍿"
    }
}

private fun getGenreGradient(genre: String): androidx.compose.ui.graphics.Brush {
    val colors = when (genre.lowercase()) {
        "action", "action & adventure" -> listOf(androidx.compose.ui.graphics.Color(0xFFE50914), androidx.compose.ui.graphics.Color(0xFF8E0E00)) // Netflix red
        "comedy" -> listOf(androidx.compose.ui.graphics.Color(0xFFF2C94C), androidx.compose.ui.graphics.Color(0xFFF2994A)) // Joyful gold
        "sci-fi", "science fiction", "sci-fi & fantasy" -> listOf(androidx.compose.ui.graphics.Color(0xFF00C9FF), androidx.compose.ui.graphics.Color(0xFF92FE9D)) // Neon cyan
        "romance" -> listOf(androidx.compose.ui.graphics.Color(0xFFff0844), androidx.compose.ui.graphics.Color(0xFFffb199)) // Pink
        "horror", "thriller", "mystery" -> listOf(androidx.compose.ui.graphics.Color(0xFF434343), androidx.compose.ui.graphics.Color(0xFF000000)) // Creepy dark
        "drama" -> listOf(androidx.compose.ui.graphics.Color(0xFF8E2DE2), androidx.compose.ui.graphics.Color(0xFF4A00E0)) // Deep purple
        "documentary", "history", "biography" -> listOf(androidx.compose.ui.graphics.Color(0xFF1D976C), androidx.compose.ui.graphics.Color(0xFF93F9B9)) // Earthy green
        "animation", "anime", "kids" -> listOf(androidx.compose.ui.graphics.Color(0xFFFF416C), androidx.compose.ui.graphics.Color(0xFFFF4B2B)) // Vibrant red/pink
        "fantasy", "adventure" -> listOf(androidx.compose.ui.graphics.Color(0xFF3A1C71), androidx.compose.ui.graphics.Color(0xFFD76D77), androidx.compose.ui.graphics.Color(0xFFFFAF7B)) // Sunset
        else -> listOf(androidx.compose.ui.graphics.Color(0xFF2C3E50), androidx.compose.ui.graphics.Color(0xFF3498DB)) // Default blue/gray
    }
    return androidx.compose.ui.graphics.Brush.horizontalGradient(colors)
}
