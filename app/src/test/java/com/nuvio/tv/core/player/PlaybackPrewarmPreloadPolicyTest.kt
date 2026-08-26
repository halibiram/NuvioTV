package com.nuvio.tv.core.player

import androidx.media3.common.C
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPrewarmPreloadPolicyTest {

    @Test
    fun startPositionUsesUnsetWhenPlaybackBeginsAtZero() {
        assertEquals(C.TIME_UNSET, PlaybackPrewarmPreloadPolicy.startPositionUs(0L))
        assertEquals(C.TIME_UNSET, PlaybackPrewarmPreloadPolicy.startPositionUs(-1_000L))
    }

    @Test
    fun startPositionConvertsResumeMsToUs() {
        assertEquals(12_500_000L, PlaybackPrewarmPreloadPolicy.startPositionUs(12_500L))
        assertEquals(1_000L, PlaybackPrewarmPreloadPolicy.startPositionUs(1L))
    }

    @Test
    fun preloadStopsOnceThreeSecondsAreBuffered() {
        assertTrue(PlaybackPrewarmPreloadPolicy.shouldContinueLoading(0L))
        assertTrue(
            PlaybackPrewarmPreloadPolicy.shouldContinueLoading(
                PlaybackPrewarmPreloadPolicy.TARGET_BUFFER_US - 1L
            )
        )
        assertFalse(
            PlaybackPrewarmPreloadPolicy.shouldContinueLoading(
                PlaybackPrewarmPreloadPolicy.TARGET_BUFFER_US
            )
        )
        assertFalse(PlaybackPrewarmPreloadPolicy.shouldContinueLoading(10_000_000L))
    }

    @Test
    fun preloadDoesNotAttachOnMissingOrMainLooper() {
        assertFalse(PlaybackPrewarmPreloadPolicy.canAttachPreload(null))
        assertFalse(PlaybackPrewarmPreloadPolicy.canAttachPreload(android.os.Looper.getMainLooper()))
    }

    @Test
    fun engineWarmAndPreloadShareTheSameEligibilityGate() {
        assertTrue(
            PlaybackPrewarmPolicy.shouldWarmEngine(
                url = "https://cdn.example/a.mkv",
                isTorrent = false,
                playerPreference = PlayerPreference.INTERNAL,
                engine = InternalPlayerEngine.EXOPLAYER
            )
        )
        assertFalse(
            PlaybackPrewarmPolicy.shouldWarmEngine(
                url = "https://cdn.example/a.mkv",
                isTorrent = true,
                playerPreference = PlayerPreference.INTERNAL,
                engine = InternalPlayerEngine.EXOPLAYER
            )
        )
        assertFalse(
            PlaybackPrewarmPolicy.shouldWarmEngine(
                url = "https://cdn.example/a.mkv",
                isTorrent = false,
                playerPreference = PlayerPreference.INTERNAL,
                engine = InternalPlayerEngine.MVP_PLAYER
            )
        )
        assertFalse(
            PlaybackPrewarmPolicy.shouldWarmEngine(
                url = "magnet:?xt=urn:btih:abc",
                isTorrent = false,
                playerPreference = PlayerPreference.INTERNAL,
                engine = InternalPlayerEngine.EXOPLAYER
            )
        )
    }
}
