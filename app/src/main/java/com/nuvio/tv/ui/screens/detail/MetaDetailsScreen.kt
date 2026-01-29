package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.foundation.lazy.list.TvLazyColumn
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.items
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.List
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MetaDetailsScreen(
    viewModel: MetaDetailsViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onPlayClick: (videoId: String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }
            uiState.error != null -> {
                ErrorState(
                    message = uiState.error ?: "An error occurred",
                    onRetry = { viewModel.onEvent(MetaDetailsEvent.OnRetry) }
                )
            }
            uiState.meta != null -> {
                MetaDetailsContent(
                    meta = uiState.meta!!,
                    seasons = uiState.seasons,
                    selectedSeason = uiState.selectedSeason,
                    episodesForSeason = uiState.episodesForSeason,
                    onSeasonSelected = { viewModel.onEvent(MetaDetailsEvent.OnSeasonSelected(it)) },
                    onEpisodeClick = { viewModel.onEvent(MetaDetailsEvent.OnEpisodeClick(it)) },
                    onPlayClick = { onPlayClick(it) }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaDetailsContent(
    meta: Meta,
    seasons: List<Int>,
    selectedSeason: Int,
    episodesForSeason: List<Video>,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeClick: (Video) -> Unit,
    onPlayClick: (String) -> Unit
) {
    val isSeries = meta.type == ContentType.SERIES || meta.videos.isNotEmpty()
    val nextEpisode = episodesForSeason.firstOrNull()

    Box(modifier = Modifier.fillMaxSize()) {
        // Sticky background image - stays fixed in place while content scrolls
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = meta.background ?: meta.poster,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Light global dim so text remains readable; the main fade happens in the section below the hero.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NuvioColors.Background.copy(alpha = 0.08f))
            )

            // Left side gradient for better text readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                NuvioColors.Background.copy(alpha = 0.85f),
                                NuvioColors.Background.copy(alpha = 0.5f),
                                Color.Transparent
                            ),
                            startX = 0f,
                            endX = 600f
                        )
                    )
            )
        }

        // Scrollable content on top of the fixed background
        TvLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            // Hero content section (fills screen height, content at bottom)
            item {
                HeroContentSection(
                    meta = meta,
                    nextEpisode = nextEpisode,
                    onPlayClick = {
                        val videoId = if (isSeries && nextEpisode != null) {
                            nextEpisode.id
                        } else {
                            meta.id
                        }
                        onPlayClick(videoId)
                    }
                )
            }

            // Season tabs and episodes for series - fully transparent over backdrop
            if (isSeries && seasons.isNotEmpty()) {
                item {
                    // This section owns the fade-to-dark background (smooth at top, darker as you scroll down).
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        NuvioColors.Background.copy(alpha = 0.55f),
                                        NuvioColors.Background.copy(alpha = 0.85f),
                                        NuvioColors.Background
                                    )
                                )
                            )
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            SeasonTabs(
                                seasons = seasons,
                                selectedSeason = selectedSeason,
                                onSeasonSelected = onSeasonSelected
                            )
                            EpisodesRow(
                                episodes = episodesForSeason,
                                onEpisodeClick = onEpisodeClick
                            )
                            Spacer(modifier = Modifier.height(140.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroContentSection(
    meta: Meta,
    nextEpisode: Video?,
    onPlayClick: () -> Unit
) {
    // Full height hero with content at bottom
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(540.dp), // Full screen height for TV
        contentAlignment = Alignment.BottomStart
    ) {
        // Content at the bottom
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Logo or Title
            if (meta.logo != null) {
                AsyncImage(
                    model = meta.logo,
                    contentDescription = meta.name,
                    modifier = Modifier
                        .height(120.dp)
                        .padding(bottom = 16.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.displayMedium,
                    color = NuvioColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Addon source info (like "AIOMeta | ElfHosted")
            Text(
                text = "Via Stremio Addons",
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.extendedColors.textSecondary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Action buttons row
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play button
                PlayButton(
                    text = if (nextEpisode != null) {
                        "Play S${nextEpisode.season}, E${nextEpisode.episode}"
                    } else {
                        "Play"
                    },
                    onClick = onPlayClick
                )

                // List/Shuffle button
                ActionIconButton(
                    icon = Icons.AutoMirrored.Filled.List,
                    contentDescription = "Shuffle",
                    onClick = { }
                )

                // Add to list button
                ActionIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "Add to list",
                    onClick = { }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Episode info and description
            if (nextEpisode != null) {
                Text(
                    text = "S${nextEpisode.season}, E${nextEpisode.episode} • ${nextEpisode.title}: ${nextEpisode.overview ?: ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .padding(bottom = 12.dp)
                )
            } else if (meta.description != null) {
                Text(
                    text = meta.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .padding(bottom = 12.dp)
                )
            }

            // Meta info row (Genre, Year, Ratings)
            MetaInfoRow(meta = meta)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayButton(
    text: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Button(
        onClick = onClick,
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused },
        colors = ButtonDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.Primary,
            contentColor = NuvioColors.TextPrimary,
            focusedContentColor = NuvioColors.OnPrimary
        ),
        shape = ButtonDefaults.shape(
            shape = RoundedCornerShape(4.dp)
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .onFocusChanged { isFocused = it.isFocused },
        colors = IconButtonDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.Primary,
            contentColor = NuvioColors.TextPrimary,
            focusedContentColor = NuvioColors.OnPrimary
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaInfoRow(meta: Meta) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Genres
        if (meta.genres.isNotEmpty()) {
            Text(
                text = meta.genres.firstOrNull() ?: "",
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.extendedColors.textSecondary
            )
            MetaInfoDivider()
        }

        // Year
        meta.releaseInfo?.let { releaseInfo ->
            Text(
                text = releaseInfo,
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.extendedColors.textSecondary
            )
            MetaInfoDivider()
        }

        // IMDB Rating with icon
        meta.imdbRating?.let { rating ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "⬥",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF5799EF)
                )
                Text(
                    text = rating.toInt().toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
            MetaInfoDivider()

            // Star rating
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "★",
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.extendedColors.rating
                )
                Text(
                    text = String.format("%.1f", rating),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
            MetaInfoDivider()

            // Tomato-like rating (using rating * 10 as percentage)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "●",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE74C3C)
                )
                Text(
                    text = "${(rating * 10).toInt()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaInfoDivider() {
    Text(
        text = "•",
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.extendedColors.textTertiary
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeasonTabs(
    seasons: List<Int>,
    selectedSeason: Int,
    onSeasonSelected: (Int) -> Unit
) {
    val selectedIndex = seasons.indexOf(selectedSeason).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        NuvioColors.Background.copy(alpha = 0.2f),
                        NuvioColors.Background.copy(alpha = 0.4f)
                    )
                )
            )
            .padding(horizontal = 48.dp, vertical = 24.dp)
    ) {
        TabRow(
            selectedTabIndex = selectedIndex,
            modifier = Modifier.fillMaxWidth(),
            separator = { Spacer(modifier = Modifier.width(24.dp)) },
            containerColor = Color.Transparent
        ) {
            seasons.forEachIndexed { index, season ->
                Tab(
                    selected = index == selectedIndex,
                    onFocus = { onSeasonSelected(season) },
                    onClick = { onSeasonSelected(season) }
                ) {
                    Text(
                        text = "Season $season",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (index == selectedIndex) {
                            NuvioColors.TextPrimary
                        } else {
                            NuvioTheme.extendedColors.textSecondary
                        },
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodesRow(
    episodes: List<Video>,
    onEpisodeClick: (Video) -> Unit
) {
    TvLazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        NuvioColors.Background.copy(alpha = 0.3f),
                        NuvioColors.Background.copy(alpha = 0.5f)
                    )
                )
            ),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(episodes, key = { it.id }) { episode ->
            EpisodeCard(
                episode = episode,
                onClick = { onEpisodeClick(episode) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(
    episode: Video,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    val formattedDate = remember(episode.released) {
        episode.released?.let { formatReleaseDate(it) } ?: ""
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .width(280.dp)
            .onFocusChanged { isFocused = it.isFocused },
        shape = CardDefaults.shape(
            shape = RoundedCornerShape(8.dp)
        ),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        scale = CardDefaults.scale(
            focusedScale = 1.05f
        )
    ) {
        Column {
            // Episode thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(158.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            ) {
                AsyncImage(
                    model = episode.thumbnail,
                    contentDescription = episode.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // Play indicator overlay on focus
                if (isFocused) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(NuvioColors.Background.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(NuvioColors.Primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = NuvioColors.OnPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                // Episode indicator badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NuvioColors.Background.copy(alpha = 0.8f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "◉",
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioColors.Primary
                    )
                }
            }

            // Episode info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                // Episode number and date
                Text(
                    text = "S${episode.season?.toString()?.padStart(2, '0')}E${episode.episode?.toString()?.padStart(2, '0')} - $formattedDate",
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.extendedColors.textSecondary
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Episode title
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Episode overview
                episode.overview?.let { overview ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.extendedColors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun formatReleaseDate(isoDate: String): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val outputFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)
        val date = inputFormat.parse(isoDate)
        date?.let { outputFormat.format(it) } ?: ""
    } catch (e: Exception) {
        try {
            // Try simpler format
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outputFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)
            val date = inputFormat.parse(isoDate)
            date?.let { outputFormat.format(it) } ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
