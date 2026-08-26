package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPrewarmGateTest {

    @Test
    fun doubleWarmSameUrlIsAlreadyWarm() {
        val gate = PlaybackPrewarmGate()
        val first = gate.beginResolved(PlaybackPrewarmSession(url = "https://cdn.example/a.mkv"))
        assertTrue(first is PlaybackPrewarmBeginResult.Started)
        val second = gate.beginResolved(PlaybackPrewarmSession(url = "https://cdn.example/a.mkv"))
        assertEquals(PlaybackPrewarmBeginResult.AlreadyWarm, second)
    }

    @Test
    fun replacingUrlReturnsPreviousSession() {
        val gate = PlaybackPrewarmGate()
        gate.beginResolved(PlaybackPrewarmSession(url = "https://cdn.example/a.mkv"))
        val started = gate.beginResolved(PlaybackPrewarmSession(url = "https://cdn.example/b.mkv"))
                as PlaybackPrewarmBeginResult.Started
        assertEquals("https://cdn.example/a.mkv", started.previous?.url)
    }

    @Test
    fun claimHitClearsSessionAndBlocksAbort() {
        val gate = PlaybackPrewarmGate()
        gate.beginResolved(PlaybackPrewarmSession(url = "https://cdn.example/a.mkv"))
        val claim = gate.claim("https://cdn.example/a.mkv") as PlaybackPrewarmClaimResult.Hit
        assertEquals("https://cdn.example/a.mkv", claim.ticket.session.url)
        assertNull(gate.currentSession())
        assertNull(gate.abort())
    }

    @Test
    fun claimMismatchClearsSession() {
        val gate = PlaybackPrewarmGate()
        gate.beginResolved(PlaybackPrewarmSession(url = "https://cdn.example/a.mkv"))
        assertEquals(PlaybackPrewarmClaimResult.Mismatch, gate.claim("https://cdn.example/other.mkv"))
        assertNull(gate.currentSession())
    }

    @Test
    fun abortBeforeClaimReturnsSession() {
        val gate = PlaybackPrewarmGate()
        gate.beginResolved(PlaybackPrewarmSession(url = "https://cdn.example/a.mkv"))
        assertEquals("https://cdn.example/a.mkv", gate.abort()?.url)
        assertNull(gate.currentSession())
    }

    @Test
    fun claimMissWhenIdle() {
        val gate = PlaybackPrewarmGate()
        assertEquals(PlaybackPrewarmClaimResult.Miss, gate.claim("https://cdn.example/a.mkv"))
    }

    @Test
    fun inFlightClaimIsHit() {
        val gate = PlaybackPrewarmGate()
        gate.beginResolved(PlaybackPrewarmSession(url = "https://cdn.example/a.mkv", prepareStarted = true))
        gate.markPrepareStarted("https://cdn.example/a.mkv")
        val claim = gate.claim("https://cdn.example/a.mkv") as PlaybackPrewarmClaimResult.Hit
        assertTrue(claim.ticket.session.prepareStarted)
    }

    @Test
    fun claimDuringPreloadStillTransfersTheSameUrl() {
        val gate = PlaybackPrewarmGate()
        gate.beginResolved(PlaybackPrewarmSession(url = "https://cdn.example/a.mkv"))
        gate.markPrepareStarted("https://cdn.example/a.mkv")
        val claim = gate.claim("https://cdn.example/a.mkv") as PlaybackPrewarmClaimResult.Hit
        assertEquals("https://cdn.example/a.mkv", claim.ticket.session.url)
        assertTrue(claim.ticket.session.prepareStarted)
        assertNull(gate.currentSession())
    }
}
