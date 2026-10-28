package com.nuvio.tv.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceQueryParserTest {

    @Test
    fun `empty query resumes the current item`() {
        assertResume(VoiceQueryParser.parse(""))
        assertResume(VoiceQueryParser.parse("   "))
        assertResume(VoiceQueryParser.parse(VoiceSearchFields(query = null, title = "  ")))
    }

    @Test
    fun `titles stay intact in every language`() {
        listOf(
            "Dune",
            "Kara Sevda",
            "oynat Kara Sevda",
            "play Dune movie",
            "Дюна",
            "千と千尋の神隠し",
            "Amélie",
            "لعبة العروش",
            "3. sezon 5. bölüm",
            "season 3 episode 5"
        ).forEach { spoken ->
            val parsed = VoiceQueryParser.parse(spoken)
            assertFalse(spoken, parsed.resume)
            assertEquals(spoken, parsed.title)
            assertNull(parsed.id)
            assertNull(parsed.season)
            assertNull(parsed.episode)
            assertNull(parsed.type)
        }
    }

    @Test
    fun `structured title wins over the raw query`() {
        val parsed = VoiceQueryParser.parse(
            VoiceSearchFields(
                query = "play something else on the tv",
                title = "Kara Sevda"
            )
        )

        assertEquals("Kara Sevda", parsed.title)
        assertEquals("play something else on the tv", parsed.rawQuery)
        assertFalse(parsed.resume)
    }

    @Test
    fun `numeric episode codes are read without season words`() {
        val seasonEpisode = VoiceQueryParser.parse("Breaking Bad S03E05")
        assertEquals("Breaking Bad", seasonEpisode.title)
        assertEquals(3, seasonEpisode.season)
        assertEquals(5, seasonEpisode.episode)
        assertEquals("series", seasonEpisode.type)

        val cross = VoiceQueryParser.parse("Kara Sevda 2x10")
        assertEquals("Kara Sevda", cross.title)
        assertEquals(2, cross.season)
        assertEquals(10, cross.episode)

        val leading = VoiceQueryParser.parse("s01e02 Dark")
        assertEquals("Dark", leading.title)
        assertEquals(1, leading.season)
        assertEquals(2, leading.episode)
    }

    @Test
    fun `episode code in the query is kept when the title extra has none`() {
        val parsed = VoiceQueryParser.parse(
            VoiceSearchFields(query = "Dark S01E02", title = "Dark")
        )

        assertEquals("Dark", parsed.title)
        assertEquals(1, parsed.season)
        assertEquals(2, parsed.episode)
        assertEquals("series", parsed.type)
    }

    @Test
    fun `episode codes inside words and ids are left alone`() {
        val stuck = VoiceQueryParser.parse("AS3E5B")
        assertEquals("AS3E5B", stuck.title)
        assertNull(stuck.season)

        val imdb = VoiceQueryParser.parse("tt0111161")
        assertEquals("tt0111161", imdb.id)
        assertNull(imdb.title)
        assertNull(imdb.season)

        val tmdb = VoiceQueryParser.parse("TMDB:1399")
        assertEquals("tmdb:1399", tmdb.id)
        assertNull(tmdb.title)

        val spaced = VoiceQueryParser.parse("tmdb 1399")
        assertEquals("tmdb:1399", spaced.id)
    }

    @Test
    fun `a title that merely contains an id is still a title`() {
        val parsed = VoiceQueryParser.parse("Inception tt1375666")
        assertEquals("Inception tt1375666", parsed.title)
        assertNull(parsed.id)
    }

    private fun assertResume(request: VoicePlaybackRequest) {
        assertEquals(true, request.resume)
        assertNull(request.title)
        assertNull(request.id)
        assertNull(request.season)
    }
}
