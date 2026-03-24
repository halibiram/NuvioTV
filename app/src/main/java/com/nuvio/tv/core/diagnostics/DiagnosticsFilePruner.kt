package com.nuvio.tv.core.diagnostics

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticsFilePruner @Inject constructor() {
    fun prune(rootDirectory: File, maxReports: Int): Int {
        if (!rootDirectory.exists()) return 0

        val staleDirectories = rootDirectory.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(maxReports)
            .orEmpty()

        var prunedCount = 0
        staleDirectories.forEach { directory ->
            if (directory.deleteRecursively()) {
                prunedCount += 1
            }
        }

        return prunedCount
    }
}
