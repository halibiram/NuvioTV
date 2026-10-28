package com.nuvio.tv.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceMatchRankerTest {

    @Test
    fun `exact titles match after unicode case folding`() {
        assertEquals(1000, VoiceMatchRanker.score("Kara Sevda", "KARA SEVDA"))
        assertEquals(1000, VoiceMatchRanker.score("Amelie", "Amélie"))
        assertEquals(1000, VoiceMatchRanker.score("Дюна", "дюна"))
        assertEquals(1000, VoiceMatchRanker.score("千と千尋の神隠し", "千と千尋の神隠し"))
        assertEquals("istanbul", VoiceMatchRanker.normalizeTitle("İstanbul"))
    }

    @Test
    fun `english article is not stripped from other titles`() {
        assertEquals(500, VoiceMatchRanker.score("Matrix", "The Matrix"))
        assertEquals(1000, VoiceMatchRanker.score("The Matrix", "The Matrix"))
    }

    @Test
    fun `one clear hit plays and equal hits stay a choice`() {
        val partTwo = match("tt2", "movie", "Dune: Part Two")
        val clear = VoiceMatchRanker.rank(
            request("Dune: Part Two"),
            listOf(match("tt1", "movie", "Dune"), partTwo)
        )
        assertEquals(partTwo, clear.winner)

        val ambiguous = VoiceMatchRanker.rank(
            request("Dune"),
            listOf(match("tt1", "movie", "Dune"), match("tt2", "movie", "Dune"))
        )
        assertNull(ambiguous.winner)
        assertEquals(listOf("tt1", "tt2"), ambiguous.matches.map { it.id })
    }

    @Test
    fun `a different type cannot outrank the requested type`() {
        val movie = match("movie-1", "movie", "Dark")
        val series = match("series-1", "series", "Dark")
        val ranked = VoiceMatchRanker.rank(
            request("Dark").copy(type = "series"),
            listOf(movie, series)
        )
        assertEquals(series, ranked.winner)
    }

    private fun request(title: String) = VoicePlaybackRequest(
        rawQuery = title,
        title = title,
        id = null,
        type = null,
        season = null,
        episode = null,
        resume = false
    )

    private fun match(id: String, type: String, name: String) = VoiceMatch(
        id = id,
        type = type,
        name = name,
        releaseInfo = null,
        poster = null,
        addonBaseUrl = null
    )
}
