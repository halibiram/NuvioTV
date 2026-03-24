package com.nuvio.tv.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemLogcatCollectorTest {

    private val collector = SystemLogcatCollector()

    @Test
    fun filterForAppSignals_keepsOnlyRelevantLines() {
        val filtered = collector.filterForAppSignals(
            """
            01-01 10:00:00.000 I ActivityManager: unrelated
            01-01 10:00:01.000 D NuvioApplication: startup complete
            01-01 10:00:02.000 E SomeTag: com.nuvio.tv failed request
            """.trimIndent()
        )

        assertTrue(filtered.contains("NuvioApplication: startup complete"))
        assertTrue(filtered.contains("com.nuvio.tv failed request"))
        assertFalse(filtered.contains("ActivityManager: unrelated"))
    }

    @Test
    fun hasMeaningfulOutput_detectsUnavailablePlaceholder() {
        assertFalse(SystemLogcatCollector.hasMeaningfulOutput(SystemLogcatCollector.UNAVAILABLE_MESSAGE))
        assertFalse(SystemLogcatCollector.hasMeaningfulOutput("   "))
        assertTrue(SystemLogcatCollector.hasMeaningfulOutput("01-01 10:00:01.000 D NuvioApplication: startup complete"))
    }

    @Test
    fun filterForAppSignals_returnsEmptyWhenNothingMatches() {
        val filtered = collector.filterForAppSignals("01-01 10:00:00.000 I ActivityManager: unrelated")

        assertEquals("", filtered)
    }
}
