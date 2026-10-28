package com.nuvio.tv.core.voice

/**
 * Search fields the TV assistant already resolved.
 *
 * [Google Assistant and media apps](https://developer.android.com/media/implement/assistant)
 * and [Common intents](https://developer.android.com/guide/components/intents-common)
 * supply a title in [title] (`MediaStore.EXTRA_MEDIA_TITLE`) or an unstructured
 * [query] (`SearchManager.QUERY`). An empty query means play the current item.
 * The spoken sentence is not parsed:
 * season and episode come only from the numeric codes `S03E05` and `3x5`.
 */
data class VoiceSearchFields(
    val query: String? = null,
    val title: String? = null
)

/**
 * Turns an assistant search into a catalog request. Titles stay in whatever
 * language they arrived in.
 */
object VoiceQueryParser {
    private val whitespace = Regex("\\s+")
    private val imdbId = Regex("(?i)^tt\\d{5,}$")
    private val tmdbId = Regex("(?i)^tmdb[:\\s]+(\\d+)$")
    private val seasonEpisodeCode = Regex("(?i)(?<![\\p{L}\\p{N}])S(\\d{1,3})E(\\d{1,4})(?![\\p{L}\\p{N}])")
    private val crossCode = Regex("(?i)(?<![\\p{L}\\p{N}])(\\d{1,3})x(\\d{1,4})(?![\\p{L}\\p{N}])")

    fun parse(raw: String): VoicePlaybackRequest = parse(VoiceSearchFields(query = raw))

    fun parse(fields: VoiceSearchFields): VoicePlaybackRequest {
        val query = fields.query?.trim().orEmpty()
        val explicitTitle = fields.title?.trim()?.takeIf { it.isNotEmpty() }
        if (explicitTitle == null && query.isEmpty()) {
            return resume(query)
        }

        val primary = (explicitTitle ?: query).replace(whitespace, " ")
        val inPrimary = extractEpisodeCode(primary)
        val inQuery = if (inPrimary == null && explicitTitle != null) extractEpisodeCode(query) else null
        val coded = inPrimary ?: inQuery
        val text = (if (inPrimary != null) inPrimary.remainder else primary).trim()
        val id = standaloneId(text)
        return VoicePlaybackRequest(
            rawQuery = query.ifBlank { explicitTitle.orEmpty() },
            title = text.takeIf { it.isNotEmpty() && id == null },
            id = id,
            type = if (coded != null) "series" else null,
            season = coded?.season,
            episode = coded?.episode,
            resume = false
        )
    }

    private fun resume(raw: String) = VoicePlaybackRequest(
        rawQuery = raw,
        title = null,
        id = null,
        type = null,
        season = null,
        episode = null,
        resume = true
    )

    private fun extractEpisodeCode(text: String): EpisodeCode? {
        val match = seasonEpisodeCode.find(text) ?: crossCode.find(text) ?: return null
        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues[2].toIntOrNull() ?: return null
        if (season !in 0..300 || episode !in 1..9999) return null
        val remainder = text.removeRange(match.range).replace(whitespace, " ").trim()
        return EpisodeCode(season, episode, remainder)
    }

    private fun standaloneId(text: String): String? {
        imdbId.matchEntire(text)?.let { return it.value.lowercase() }
        val tmdb = tmdbId.matchEntire(text) ?: return null
        return "tmdb:${tmdb.groupValues[1]}"
    }

    private data class EpisodeCode(
        val season: Int,
        val episode: Int,
        val remainder: String
    )
}
