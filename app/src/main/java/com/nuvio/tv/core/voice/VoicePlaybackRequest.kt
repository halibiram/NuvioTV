package com.nuvio.tv.core.voice

/**
 * A playback command spoken to the TV assistant.
 *
 * [title] is the catalog query in the language the assistant supplied.
 * [id] is set when that string is only an IMDB or TMDB id.
 * [resume] means the assistant sent an empty search, which plays the current item.
 */
data class VoicePlaybackRequest(
    val rawQuery: String,
    val title: String?,
    val id: String?,
    val type: String?,
    val season: Int?,
    val episode: Int?,
    val resume: Boolean
)

data class VoiceMatch(
    val id: String,
    val type: String,
    val name: String,
    val releaseInfo: String?,
    val poster: String?,
    val addonBaseUrl: String?
)

data class VoiceRankResult(
    val matches: List<VoiceMatch>,
    val winner: VoiceMatch?
)

enum class VoiceSuggestionKind {
    DETAIL,
    CHOOSE,
    RESUME
}

data class VoiceSuggestion(
    val title: String,
    val kind: VoiceSuggestionKind,
    val releaseInfo: String?,
    val intentData: String,
    val imageUrl: String?
)
