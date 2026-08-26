package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPrewarmPolicyTest {

    @Test
    fun claimMissWhenNoSession() {
        val result = PlaybackPrewarmPolicy.decideClaim(session = null, expectedUrl = "https://cdn.example/a.mkv")
        assertEquals(PlaybackPrewarmClaimResult.Miss, result)
    }

    @Test
    fun claimHitWhenUrlsMatch() {
        val session = PlaybackPrewarmSession(url = "https://cdn.example/a.mkv")
        val result = PlaybackPrewarmPolicy.decideClaim(session, "https://cdn.example/a.mkv")
        val hit = result as PlaybackPrewarmClaimResult.Hit
        assertEquals(session.url, hit.ticket.session.url)
    }

    @Test
    fun claimMismatchWhenUrlsDiffer() {
        val session = PlaybackPrewarmSession(url = "https://cdn.example/a.mkv")
        val result = PlaybackPrewarmPolicy.decideClaim(session, "https://cdn.example/b.mkv")
        assertEquals(PlaybackPrewarmClaimResult.Mismatch, result)
    }

    @Test
    fun shouldReplaceWarmWhenUrlChanges() {
        assertTrue(
            PlaybackPrewarmPolicy.shouldReplaceWarm(
                existingUrl = "https://cdn.example/a.mkv",
                newUrl = "https://cdn.example/b.mkv"
            )
        )
    }

    @Test
    fun shouldNotReplaceWarmWhenUrlMatches() {
        assertFalse(
            PlaybackPrewarmPolicy.shouldReplaceWarm(
                existingUrl = "https://cdn.example/a.mkv",
                newUrl = "https://cdn.example/a.mkv"
            )
        )
    }

    @Test
    fun shouldWarmEngineForInternalExoHttp() {
        assertTrue(
            PlaybackPrewarmPolicy.shouldWarmEngine(
                url = "https://cdn.example/a.mkv",
                isTorrent = false,
                playerPreference = PlayerPreference.INTERNAL,
                engine = InternalPlayerEngine.EXOPLAYER
            )
        )
    }

    @Test
    fun shouldNotWarmEngineForMpvOrTorrentOrExternal() {
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
                playerPreference = PlayerPreference.EXTERNAL,
                engine = InternalPlayerEngine.EXOPLAYER
            )
        )
        assertFalse(
            PlaybackPrewarmPolicy.shouldWarmEngine(
                url = "https://cdn.example/a.mkv",
                isTorrent = false,
                playerPreference = PlayerPreference.INTERNAL,
                engine = InternalPlayerEngine.AUTO
            )
        )
        assertFalse(
            PlaybackPrewarmPolicy.shouldWarmEngine(
                url = "https://cdn.example/a.mkv",
                isTorrent = false,
                playerPreference = PlayerPreference.INTERNAL,
                engine = InternalPlayerEngine.EXOPLAYER,
                contentPlaybackActive = true
            )
        )
    }

    @Test
    fun abortIsNoOpAfterOwnershipTransfer() {
        assertFalse(PlaybackPrewarmPolicy.canAbortAndRelease(hasTransferredOwnership = true))
        assertTrue(PlaybackPrewarmPolicy.canAbortAndRelease(hasTransferredOwnership = false))
    }
}
