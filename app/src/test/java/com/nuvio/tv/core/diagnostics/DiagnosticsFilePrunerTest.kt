package com.nuvio.tv.core.diagnostics

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsFilePrunerTest {

    private val subject = DiagnosticsFilePruner()

    @Test
    fun prune_keepsNewestDirectoriesWithinLimit() {
        val root = createTempDirectory(prefix = "diagnostics-pruner-").toFile()
        val oldest = root.resolve("oldest").apply { mkdirs(); setLastModified(1_000L) }
        val middle = root.resolve("middle").apply { mkdirs(); setLastModified(2_000L) }
        val newest = root.resolve("newest").apply { mkdirs(); setLastModified(3_000L) }

        val prunedCount = subject.prune(root, maxReports = 2)

        assertEquals(1, prunedCount)
        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newest.exists())

        root.deleteRecursively()
    }

    @Test
    fun prune_returnsZeroWhenDirectoryDoesNotExist() {
        val root = File(createTempDirectory(prefix = "diagnostics-pruner-missing-").toFile(), "missing")

        val prunedCount = subject.prune(root, maxReports = 3)

        assertEquals(0, prunedCount)
    }

    @Test
    fun prune_ignoresRegularFilesWhenApplyingLimit() {
        val root = createTempDirectory(prefix = "diagnostics-pruner-files-").toFile()
        root.resolve("note.txt").writeText("ignore me")
        val keep = root.resolve("keep").apply { mkdirs(); setLastModified(2_000L) }
        val remove = root.resolve("remove").apply { mkdirs(); setLastModified(1_000L) }

        val prunedCount = subject.prune(root, maxReports = 1)

        assertEquals(1, prunedCount)
        assertTrue(keep.exists())
        assertFalse(remove.exists())
        assertTrue(root.resolve("note.txt").exists())

        root.deleteRecursively()
    }
}
