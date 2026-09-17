package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPlaybackAnalyticsDiagnosticsTest {

    @Test
    fun `vod buffer below duration reports integer percent`() {
        assertEquals(0, safeBufferedPercentage(bufferedPositionMs = 0L, durationMs = 10_000L))
        assertEquals(25, safeBufferedPercentage(bufferedPositionMs = 2_500L, durationMs = 10_000L))
        assertEquals(99, safeBufferedPercentage(bufferedPositionMs = 9_999L, durationMs = 10_000L))
    }

    @Test
    fun `fully buffered or empty duration reports 100`() {
        assertEquals(100, safeBufferedPercentage(bufferedPositionMs = 10_000L, durationMs = 10_000L))
        assertEquals(100, safeBufferedPercentage(bufferedPositionMs = 12_000L, durationMs = 10_000L))
        assertEquals(100, safeBufferedPercentage(bufferedPositionMs = 0L, durationMs = 0L))
    }

    @Test
    fun `unset or negative times are omitted`() {
        assertNull(safeBufferedPercentage(bufferedPositionMs = 1_000L, durationMs = C.TIME_UNSET))
        assertNull(safeBufferedPercentage(bufferedPositionMs = C.TIME_UNSET, durationMs = 10_000L))
        assertNull(safeBufferedPercentage(bufferedPositionMs = -1L, durationMs = 10_000L))
        assertNull(safeBufferedPercentage(bufferedPositionMs = 1_000L, durationMs = -5L))
    }

    @Test
    fun `live iptv timestamps that overflow Media3 percentInt clamp to 100`() {
        assertEquals(
            100,
            safeBufferedPercentage(bufferedPositionMs = 1_535_769_691_039L, durationMs = 10L)
        )
        assertEquals(
            100,
            safeBufferedPercentage(bufferedPositionMs = 1_243_001_841_137L, durationMs = 25L)
        )
    }

    private val playerContextLines = listOf(
        "surround_resolve route=x ac3=true",
        "diag_schema schema=1",
        "build sha=abc tree=def",
        "settings tunnel=false surround_mode=AUTO",
        "sink_configure mode=TRUEHD mime=audio/true-hd ch=8 rate=48000 tunnel_req=false",
        "iec_open ok=1 track=1 payload=IEC_BURST"
    )

    private fun pendingWithPlayerContext(): ArrayDeque<String> =
        ArrayDeque<String>().also { queue -> playerContextLines.forEach { appendPendingRawEventLine(queue, it) } }

    @Test
    fun `player context survives thirty minutes of health lines`() {
        val pending = pendingWithPlayerContext()
        for (i in 1..360) appendPendingRawEventLine(pending, "iec_health track=1 n=$i")

        assertTrue(pending.containsAll(playerContextLines))
        val health = pending.filter { it.startsWith("iec_health ") }
        assertEquals(12, health.size)
        assertEquals("iec_health track=1 n=349", health.first())
        assertEquals("iec_health track=1 n=360", health.last())
        assertTrue(pending.size <= 40)
    }

    @Test
    fun `player context survives a run of seeks and the newest events are kept in order`() {
        val pending = pendingWithPlayerContext()
        for (i in 1..200) {
            appendPendingRawEventLine(pending, "iec_pause track=1 #$i")
            appendPendingRawEventLine(pending, "iec_flush track=1 pending=$i")
            appendPendingRawEventLine(pending, "iec_play track=1 #$i")
            if (i % 3 == 0) appendPendingRawEventLine(pending, "iec_health track=1 n=$i")
        }

        assertTrue(pending.containsAll(playerContextLines))
        assertEquals(40, pending.size)
        assertTrue("iec_play track=1 #200" in pending)
        assertFalse("iec_pause track=1 #1" in pending)
        val plays = pending.filter { it.startsWith("iec_play ") }.map { it.substringAfter('#').toInt() }
        assertEquals(plays.sorted(), plays)
    }

    @Test
    fun `without health or context lines the queue trims oldest first as before`() {
        val pending = ArrayDeque<String>()
        val oldestFirst = ArrayDeque<String>()
        for (i in 1..100) {
            appendPendingRawEventLine(pending, "EVENT $i")
            oldestFirst.addLast("EVENT $i")
            while (oldestFirst.size > 40) oldestFirst.removeFirst()
        }

        assertEquals(oldestFirst.toList(), pending.toList())
    }

    @Test
    fun `a reconfigure loop cannot crowd out the build lines or the health lines`() {
        val pending = pendingWithPlayerContext()
        for (i in 2..60) {
            appendPendingRawEventLine(pending, "sink_configure mode=TRUEHD n=$i")
            appendPendingRawEventLine(pending, "iec_open ok=1 track=$i")
            appendPendingRawEventLine(pending, "iec_health track=$i n=$i")
        }

        assertEquals(8, pending.count { it.startsWith("sink_configure ") || it.startsWith("iec_open ") })
        assertTrue("iec_open ok=1 track=60" in pending)
        assertTrue("diag_schema schema=1" in pending)
        assertTrue("build sha=abc tree=def" in pending)
        assertTrue("settings tunnel=false surround_mode=AUTO" in pending)
        assertEquals(12, pending.count { it.startsWith("iec_health ") })
    }

    @Test
    fun `look-alike lines are not treated as player context`() {
        assertFalse(isPlayerContextRawEventLine("iec_open_failed x"))
        assertFalse(isPlayerContextRawEventLine("iec_underruns=3"))
        assertFalse(isPlayerContextRawEventLine("builder x"))
        assertFalse(isPlayerContextRawEventLine("settings_changed a=1"))
        assertFalse(isPlayerContextRawEventLine("BUILD: sha=1"))
        assertTrue(isPlayerContextRawEventLine("iec_open ok=0 mime=audio/true-hd"))
    }

    private fun ringOf(vararg lines: String): ArrayDeque<String> = ArrayDeque<String>().also { it.addAll(lines) }

    private fun ArrayDeque<String>.recordCapped(line: String) {
        addLast(line)
        while (size > 220) removeFirst()
    }

    @Test
    fun `a player rebuild drains everything and leaves the queue empty`() {
        val pending = pendingWithPlayerContext()
        appendPendingRawEventLine(pending, "iec_health track=1 n=1")
        val ring = ringOf()

        drainPendingRawEventLines(pending, keepPlayerContext = false, alreadyRecorded = { it in ring }) {
            ring.recordCapped(it)
        }

        assertTrue(pending.isEmpty())
        assertEquals(playerContextLines + "iec_health track=1 n=1", ring.toList())
    }

    @Test
    fun `a later report carries the player context again after the ring has turned over`() {
        val pending = pendingWithPlayerContext()
        for (i in 1..20) appendPendingRawEventLine(pending, "iec_health track=1 n=$i")
        val ring = ringOf()
        for (i in 1..220) ring.recordCapped("EXO_EVENT: $i")
        val drain = {
            drainPendingRawEventLines(pending, keepPlayerContext = true, alreadyRecorded = { it in ring }) {
                ring.recordCapped(it)
            }
        }

        drain()
        assertTrue(ring.containsAll(playerContextLines))
        assertEquals(12, ring.count { it.startsWith("iec_health ") })
        assertEquals(playerContextLines, pending.toList())

        val sizeAfterFirstReport = ring.size
        drain()
        assertEquals(sizeAfterFirstReport, ring.size)
        playerContextLines.forEach { line -> assertEquals(1, ring.count { it == line }) }

        for (i in 221..600) ring.recordCapped("EXO_EVENT: $i")
        assertTrue(playerContextLines.none { it in ring })
        for (i in 21..25) appendPendingRawEventLine(pending, "iec_health track=1 n=$i")
        drain()
        playerContextLines.forEach { line -> assertEquals(1, ring.count { it == line }) }
        assertEquals(5, ring.count { it.startsWith("iec_health ") })
    }

    @Test
    fun `a rebuild after a report clears the kept player context`() {
        val pending = pendingWithPlayerContext()
        val firstRing = ringOf()
        drainPendingRawEventLines(pending, keepPlayerContext = true, alreadyRecorded = { it in firstRing }) {
            firstRing.recordCapped(it)
        }
        val rebuiltRing = ringOf()

        drainPendingRawEventLines(pending, keepPlayerContext = false, alreadyRecorded = { it in rebuiltRing }) {
            rebuiltRing.recordCapped(it)
        }

        assertTrue(pending.isEmpty())
        assertEquals(playerContextLines, rebuiltRing.toList())
    }
}
