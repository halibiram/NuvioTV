package com.nuvio.tv.ui.screens.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.voice.VoiceMatch
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VoiceChoiceScreen(
    onPlay: (VoiceMatch, Int?, Int?, Boolean) -> Unit,
    onResume: (WatchProgress) -> Unit,
    onBack: () -> Unit,
    viewModel: VoiceChoiceViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var handledNavigation by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (handledNavigation) return@LaunchedEffect
        when (val current = state) {
            is VoiceChoiceUiState.Play -> {
                handledNavigation = true
                onPlay(current.match, current.season, current.episode, current.startPlayback)
            }
            is VoiceChoiceUiState.Resume -> {
                handledNavigation = true
                onResume(current.progress)
            }
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.voice_choice_title),
            color = NuvioTheme.colors.TextPrimary,
            style = NuvioTheme.textStyles.headline
        )
        when (val current = state) {
            VoiceChoiceUiState.Loading -> {
                Text(
                    text = stringResource(R.string.voice_choice_searching),
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            is VoiceChoiceUiState.NotFound -> {
                Text(
                    text = stringResource(
                        if (current.resume) R.string.voice_resume_empty else R.string.voice_choice_not_found
                    ),
                    color = NuvioTheme.colors.TextSecondary
                )
                Button(onClick = onBack) {
                    Text(stringResource(R.string.voice_choice_back))
                }
            }
            is VoiceChoiceUiState.Choices -> {
                val firstFocus = remember { FocusRequester() }
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(current.matches, key = { _, match -> "${match.type}:${match.id}" }) { index, match ->
                        Button(
                            onClick = {
                                onPlay(match, current.season, current.episode, current.startPlayback)
                            },
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                        ) {
                            Text(voiceChoiceLabel(match))
                        }
                    }
                }
                LaunchedEffect(current.matches) {
                    if (current.matches.isNotEmpty()) {
                        runCatching { firstFocus.requestFocus() }
                    }
                }
            }
            is VoiceChoiceUiState.Play,
            is VoiceChoiceUiState.Resume -> Unit
        }
    }
}

private fun voiceChoiceLabel(match: VoiceMatch): String {
    val year = match.releaseInfo?.takeIf { it.isNotBlank() }
    return if (year == null) match.name else "${match.name} ($year)"
}
