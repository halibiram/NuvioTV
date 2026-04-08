package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "PrefsCorruption"

object PreferencesCorruptionTracker {
    private val corruptedStores = ConcurrentHashMap.newKeySet<String>()

    fun mark(storeName: String) {
        corruptedStores.add(storeName)
    }

    fun contains(storeName: String): Boolean = storeName in corruptedStores

    fun clear(storeName: String) {
        corruptedStores.remove(storeName)
    }
}

fun preferencesCorruptionHandler(storeName: String): ReplaceFileCorruptionHandler<Preferences> {
    return ReplaceFileCorruptionHandler { exception ->
        PreferencesCorruptionTracker.mark(storeName)
        Log.e(TAG, "DataStore corruption detected for $storeName, replacing with empty preferences", exception)
        emptyPreferences()
    }
}
