package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.protobuf.InvalidProtocolBufferException
import com.nuvio.tv.data.local.proto.WatchProgressShard
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "WatchProgressShard"

object WatchProgressCorruptionTracker {
    private val corruptedStores = ConcurrentHashMap.newKeySet<String>()

    fun mark(storeName: String) {
        corruptedStores.add(storeName)
    }

    fun contains(storeName: String): Boolean = storeName in corruptedStores

    fun clear(storeName: String) {
        corruptedStores.remove(storeName)
    }
}

object WatchProgressShardSerializer : Serializer<WatchProgressShard> {
    override val defaultValue: WatchProgressShard = WatchProgressShard.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): WatchProgressShard {
        return try {
            WatchProgressShard.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Unable to read watch progress shard.", exception)
        } catch (_: EOFException) {
            defaultValue
        }
    }

    override suspend fun writeTo(t: WatchProgressShard, output: OutputStream) {
        t.writeTo(output)
    }
}

fun watchProgressCorruptionHandler(storeName: String): ReplaceFileCorruptionHandler<WatchProgressShard> {
    return ReplaceFileCorruptionHandler { exception ->
        WatchProgressCorruptionTracker.mark(storeName)
        Log.e(TAG, "Watch progress shard corruption detected for $storeName, replacing with empty shard", exception)
        WatchProgressShard.getDefaultInstance()
    }
}
