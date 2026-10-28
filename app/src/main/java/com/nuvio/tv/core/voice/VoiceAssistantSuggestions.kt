package com.nuvio.tv.core.voice

import com.nuvio.tv.domain.model.WatchProgress

sealed interface VoiceResolution {
    data class Play(
        val match: VoiceMatch,
        val season: Int?,
        val episode: Int?
    ) : VoiceResolution

    data class Choose(val matches: List<VoiceMatch>) : VoiceResolution

    data class Resume(val progress: WatchProgress) : VoiceResolution

    data object NotFound : VoiceResolution
}

/**
 * Rows the TV search provider returns to the assistant.
 * An ambiguous title is a single "choose" link so the assistant cannot autoplay the wrong hit.
 */
object VoiceAssistantSuggestions {
    fun from(request: VoicePlaybackRequest, resolution: VoiceResolution): List<VoiceSuggestion> {
        if (request.resume || resolution is VoiceResolution.Resume) {
            return listOf(resumeSuggestion(request))
        }
        return when (resolution) {
            is VoiceResolution.Play -> listOf(detailSuggestion(resolution.match, resolution.season, resolution.episode))
            is VoiceResolution.Choose,
            VoiceResolution.NotFound -> listOf(
                VoiceSuggestion(
                    title = request.title ?: request.id ?: request.rawQuery,
                    kind = VoiceSuggestionKind.CHOOSE,
                    releaseInfo = null,
                    intentData = VoiceDeepLinks.choose(request),
                    imageUrl = null
                )
            )
            is VoiceResolution.Resume -> listOf(resumeSuggestion(request))
        }
    }

    private fun resumeSuggestion(request: VoicePlaybackRequest): VoiceSuggestion {
        return VoiceSuggestion(
            title = request.rawQuery,
            kind = VoiceSuggestionKind.RESUME,
            releaseInfo = null,
            intentData = VoiceDeepLinks.resume(),
            imageUrl = null
        )
    }

    private fun detailSuggestion(match: VoiceMatch, season: Int?, episode: Int?): VoiceSuggestion {
        return VoiceSuggestion(
            title = match.name,
            kind = VoiceSuggestionKind.DETAIL,
            releaseInfo = match.releaseInfo,
            intentData = VoiceDeepLinks.detail(match.type, match.id, season, episode),
            imageUrl = match.poster?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        )
    }
}
