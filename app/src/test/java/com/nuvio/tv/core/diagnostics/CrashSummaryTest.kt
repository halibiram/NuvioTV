package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashSummaryTest {

    @Test
    fun fromThrowable_capturesThrowableMetadata() {
        val throwable = IllegalStateException("boom")

        val summary = CrashSummary.fromThrowable(
            throwable = throwable,
            threadName = "main",
            capturedAtEpochMs = 1234L
        )

        assertEquals(IllegalStateException::class.java.name, summary.type)
        assertEquals("boom", summary.message)
        assertEquals("main", summary.threadName)
        assertEquals(1234L, summary.capturedAtEpochMs)
        assertTrue(summary.stackTrace.contains("IllegalStateException"))
    }
}
