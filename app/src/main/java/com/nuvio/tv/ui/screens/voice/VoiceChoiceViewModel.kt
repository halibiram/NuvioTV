package com.nuvio.tv.ui.screens.voice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.voice.VoiceMatch
import com.nuvio.tv.core.voice.VoicePlaybackRequest
import com.nuvio.tv.core.voice.VoicePlaybackResolver
import com.nuvio.tv.core.voice.VoiceResolution
import com.nuvio.tv.domain.model.WatchProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface VoiceChoiceUiState {
    data object Loading : VoiceChoiceUiState

    data class Choices(
        val matches: List<VoiceMatch>,
        val season: Int?,
        val episode: Int?,
        val startPlayback: Boolean
    ) : VoiceChoiceUiState

    data class Play(
        val match: VoiceMatch,
        val season: Int?,
        val episode: Int?,
        val startPlayback: Boolean
    ) : VoiceChoiceUiState

    data class Resume(val progress: WatchProgress) : VoiceChoiceUiState

    data class NotFound(val resume: Boolean) : VoiceChoiceUiState
}

@HiltViewModel
class VoiceChoiceViewModel @Inject constructor(
    private val resolver: VoicePlaybackResolver,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val request = savedStateHandle.toVoiceRequest()
    private val startPlayback = savedStateHandle.get<String>("play") != "false"

    private val _uiState = MutableStateFlow<VoiceChoiceUiState>(VoiceChoiceUiState.Loading)
    val uiState: StateFlow<VoiceChoiceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = when (val resolution = resolver.resolve(request)) {
                is VoiceResolution.Play -> VoiceChoiceUiState.Play(
                    match = resolution.match,
                    season = resolution.season,
                    episode = resolution.episode,
                    startPlayback = startPlayback
                )
                is VoiceResolution.Choose -> VoiceChoiceUiState.Choices(
                    matches = resolution.matches,
                    season = request.season,
                    episode = request.episode,
                    startPlayback = startPlayback
                )
                is VoiceResolution.Resume -> VoiceChoiceUiState.Resume(resolution.progress)
                VoiceResolution.NotFound -> VoiceChoiceUiState.NotFound(resume = request.resume)
            }
        }
    }
}

private fun SavedStateHandle.toVoiceRequest(): VoicePlaybackRequest {
    if (get<String>("resume") == "true") {
        return VoicePlaybackRequest(
            rawQuery = "",
            title = null,
            id = null,
            type = null,
            season = null,
            episode = null,
            resume = true
        )
    }
    val query = get<String>("q").orEmpty()
    val id = get<String>("id")?.takeIf { it.isNotBlank() }
    return VoicePlaybackRequest(
        rawQuery = query,
        title = query.takeIf { it.isNotBlank() },
        id = id,
        type = get<String>("type")?.takeIf { it.isNotBlank() },
        season = get<String>("season")?.toIntOrNull(),
        episode = get<String>("episode")?.toIntOrNull(),
        resume = query.isBlank() && id == null
    )
}
