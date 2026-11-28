package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBackPressTest {

    @Test
    fun `binge group reuse skip next then back does not open next episode streams`() {
        val autoPlay = playerBackTreatsSkipAsAutoPlay(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            preferBingeGroupForNextEpisode = true
        )

        assertTrue(autoPlay)
        assertFalse(
            playerBackOpensCurrentEpisodeStreams(
                episodeChangedInPlace = true,
                autoPlayEnabled = autoPlay
            )
        )
    }

    @Test
    fun `strict manual skip next still opens current episode streams`() {
        val autoPlay = playerBackTreatsSkipAsAutoPlay(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            preferBingeGroupForNextEpisode = false
        )

        assertFalse(autoPlay)
        assertTrue(
            playerBackOpensCurrentEpisodeStreams(
                episodeChangedInPlace = true,
                autoPlayEnabled = autoPlay
            )
        )
    }

    @Test
    fun `first stream skip next does not open the stream screen`() {
        val autoPlay = playerBackTreatsSkipAsAutoPlay(
            streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
            preferBingeGroupForNextEpisode = false
        )

        assertTrue(autoPlay)
        assertFalse(
            playerBackOpensCurrentEpisodeStreams(
                episodeChangedInPlace = true,
                autoPlayEnabled = autoPlay
            )
        )
    }

    @Test
    fun `same episode back does not open a different episode stream screen`() {
        val autoPlay = playerBackTreatsSkipAsAutoPlay(
            streamAutoPlayMode = StreamAutoPlayMode.MANUAL,
            preferBingeGroupForNextEpisode = false
        )

        assertFalse(
            playerBackOpensCurrentEpisodeStreams(
                episodeChangedInPlace = false,
                autoPlayEnabled = autoPlay
            )
        )
    }

    @Test
    fun `dismissing next episode popup while controls are visible restores focus to play pause`() {
        assertEquals(
            NextEpisodeDismissFocusTarget.PLAY_PAUSE,
            resolveNextEpisodeDismissFocusTarget(
                controlsVisible = true,
                skipButtonVisible = false,
                skipIntroCanFocus = true
            )
        )
    }

    @Test
    fun `dismissing next episode popup while controls are visible and skip button is active focuses skip button`() {
        assertEquals(
            NextEpisodeDismissFocusTarget.SKIP_INTRO,
            resolveNextEpisodeDismissFocusTarget(
                controlsVisible = true,
                skipButtonVisible = true,
                skipIntroCanFocus = true
            )
        )
    }

    @Test
    fun `dismissing next episode popup while controls are visible and skip button is unfocusable restores play pause`() {
        assertEquals(
            NextEpisodeDismissFocusTarget.PLAY_PAUSE,
            resolveNextEpisodeDismissFocusTarget(
                controlsVisible = true,
                skipButtonVisible = true,
                skipIntroCanFocus = false
            )
        )
    }

    @Test
    fun `dismissing next episode popup with controls hidden and no skip button restores container focus`() {
        assertEquals(
            NextEpisodeDismissFocusTarget.CONTAINER,
            resolveNextEpisodeDismissFocusTarget(
                controlsVisible = false,
                skipButtonVisible = false,
                skipIntroCanFocus = true
            )
        )
    }

    @Test
    fun `dismissing next episode popup with controls hidden and active skip button focuses skip button`() {
        assertEquals(
            NextEpisodeDismissFocusTarget.SKIP_INTRO,
            resolveNextEpisodeDismissFocusTarget(
                controlsVisible = false,
                skipButtonVisible = true,
                skipIntroCanFocus = true
            )
        )
    }

    @Test
    fun `dismissing next episode popup with unfocusable skip button falls back to container focus`() {
        assertEquals(
            NextEpisodeDismissFocusTarget.CONTAINER,
            resolveNextEpisodeDismissFocusTarget(
                controlsVisible = false,
                skipButtonVisible = true,
                skipIntroCanFocus = false
            )
        )
    }
}
