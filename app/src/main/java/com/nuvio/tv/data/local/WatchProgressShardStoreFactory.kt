package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import com.nuvio.tv.data.local.proto.WatchProgressShard
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchProgressShardStoreFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val SHARD_COUNT = 16
        private const val STORE_PREFIX = "watch_progress"
    }

    private val cache = ConcurrentHashMap<String, DataStore<WatchProgressShard>>()

    fun get(profileId: Int, shardId: Int): DataStore<WatchProgressShard> {
        require(shardId in 0 until SHARD_COUNT) { "Invalid shard id: $shardId" }
        val storeName = storeName(profileId, shardId)
        return cache.getOrPut(storeName) {
            DataStoreFactory.create(
                serializer = WatchProgressShardSerializer,
                corruptionHandler = watchProgressCorruptionHandler(storeName),
                produceFile = { fileFor(storeName) }
            )
        }
    }

    fun storeName(profileId: Int, shardId: Int): String {
        val shardSuffix = shardId.toString().padStart(2, '0')
        return if (profileId == 1) {
            "${STORE_PREFIX}_shard_$shardSuffix"
        } else {
            "${STORE_PREFIX}_shard_${shardSuffix}_p$profileId"
        }
    }

    suspend fun clearProfile(profileId: Int) {
        if (profileId == 1) return
        repeat(SHARD_COUNT) { shardId ->
            val storeName = storeName(profileId, shardId)
            cache.remove(storeName)
            WatchProgressCorruptionTracker.clear(storeName)
            fileFor(storeName).delete()
        }
    }

    private fun fileFor(storeName: String): File {
        val dir = File(context.filesDir, "datastore")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "$storeName.pb")
    }
}
