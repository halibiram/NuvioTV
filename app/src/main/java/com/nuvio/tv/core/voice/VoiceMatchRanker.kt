package com.nuvio.tv.core.voice

import java.text.Normalizer
import java.util.Locale

/**
 * Picks a single catalog hit only when one title is clearly the utterance.
 * Close scores stay in [VoiceRankResult.matches] so the TV can show a short list
 * instead of starting the wrong title.
 */
object VoiceMatchRanker {
    private const val EXACT = 1000
    private const val STARTS_WITH = 700
    private const val CONTAINS = 500
    private const val MINIMUM = 400
    private const val WINNER_GAP = 150
    private const val TYPE_PENALTY = 200
    private const val MAX_CHOICES = 8

    fun rank(request: VoicePlaybackRequest, candidates: List<VoiceMatch>): VoiceRankResult {
        val query = request.title?.trim().orEmpty().ifBlank { request.id?.trim().orEmpty() }
        if (query.isBlank()) return VoiceRankResult(emptyList(), winner = null)

        val scored = candidates
            .asSequence()
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .map { match -> match to adjustedScore(query, request.type, match) }
            .filter { it.second >= MINIMUM }
            .groupBy { it.first.id to it.first.type }
            .map { (_, group) -> group.maxBy { it.second } }
            .sortedByDescending { it.second }
            .take(MAX_CHOICES)
            .toList()

        if (scored.isEmpty()) return VoiceRankResult(emptyList(), winner = null)

        val topScore = scored.first().second
        val strong = scored.filter { it.second >= STARTS_WITH }
        val winner = when {
            strong.size == 1 && strong.first().second == topScore -> strong.first().first
            topScore >= EXACT && scored.drop(1).all { topScore - it.second >= WINNER_GAP } -> scored.first().first
            else -> null
        }
        return VoiceRankResult(
            matches = scored.map { it.first },
            winner = winner
        )
    }

    internal fun adjustedScore(query: String, requestedType: String?, match: VoiceMatch): Int {
        val base = score(query, match.name)
        if (base == 0 || requestedType.isNullOrBlank() || match.type == requestedType) return base
        return (base - TYPE_PENALTY).coerceAtLeast(0)
    }

    internal fun score(query: String, name: String): Int {
        val normalizedQuery = normalizeTitle(query)
        val normalizedName = normalizeTitle(name)
        if (normalizedQuery.isBlank() || normalizedName.isBlank()) return 0
        if (normalizedQuery == normalizedName) return EXACT
        if (normalizedName.startsWith(normalizedQuery)) return STARTS_WITH
        if (normalizedName.contains(normalizedQuery)) return CONTAINS
        val queryTokens = normalizedQuery.split(' ')
        val nameTokens = normalizedName.split(' ').toSet()
        val overlap = queryTokens.count { it in nameTokens }
        if (overlap == 0) return 0
        return (300 * overlap) / queryTokens.size
    }

    internal fun normalizeTitle(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return decomposed.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }
}
