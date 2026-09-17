package com.nuvio.tv.ui.screens.player.iec

import org.junit.Assert.assertEquals
import org.junit.Test

class IecDiagnosticsTest {

    @Test
    fun aRunOfIdenticalLinesIsSummarisedOnItsOwnLineWhenItEnds() {
        val out = mutableListOf<String>()
        val diag = IecDiagnostics(onDiagnosticEvent = { out.add(it) })
        diag.emit("iec_truehd_resync auSize=1 dropped=2")
        diag.emit("iec_truehd_resync auSize=1 dropped=2")
        diag.emit("iec_truehd_resync auSize=1 dropped=2")
        diag.emit("iec_anchor mode=TRUEHD")
        assertEquals(
            listOf(
                "iec_truehd_resync auSize=1 dropped=2",
                "iec_truehd_resync auSize=1 dropped=2 repeat=2",
                "iec_anchor mode=TRUEHD"
            ),
            out
        )
    }

    @Test
    fun eachRunGetsItsOwnSummary() {
        val out = mutableListOf<String>()
        val diag = IecDiagnostics(onDiagnosticEvent = { out.add(it) })
        diag.emit("a x=1")
        diag.emit("a x=1")
        diag.emit("b y=1")
        diag.emit("c z=1")
        assertEquals(listOf("a x=1", "a x=1 repeat=1", "b y=1", "c z=1"), out)
    }

    @Test
    fun healthLinesAreNeverSuppressed() {
        val out = mutableListOf<String>()
        val diag = IecDiagnostics(onDiagnosticEvent = { out.add(it) })
        diag.emit("iec_health mode=TRUEHD underruns=0")
        diag.emit("iec_health mode=TRUEHD underruns=0")
        assertEquals(2, out.size)
    }

    @Test
    fun aHealthLineDoesNotAbsorbAPrecedingRunsCount() {
        val out = mutableListOf<String>()
        val diag = IecDiagnostics(onDiagnosticEvent = { out.add(it) })
        diag.emit("iec_flush reason=seek")
        diag.emit("iec_flush reason=seek")
        diag.emit("iec_health mode=TRUEHD underruns=0")
        assertEquals(
            listOf("iec_flush reason=seek", "iec_flush reason=seek repeat=1", "iec_health mode=TRUEHD underruns=0"),
            out
        )
    }

    @Test
    fun aThrowingCallbackDisablesFurtherEmitsWithoutPropagating() {
        var calls = 0
        val diag = IecDiagnostics(onDiagnosticEvent = { calls++; throw IllegalStateException("boom") })
        diag.emit("iec_anchor a=1")
        diag.emit("iec_anchor a=2")
        assertEquals(1, calls)
    }

    @Test
    fun nullCallbackIsAccepted() {
        val diag = IecDiagnostics(onDiagnosticEvent = null)
        diag.emit("iec_anchor a=1")
        diag.emit("iec_anchor a=1")
    }
}
