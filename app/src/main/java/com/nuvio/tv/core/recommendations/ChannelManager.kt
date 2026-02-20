package com.nuvio.tv.core.recommendations

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the lifecycle of TV recommendation channels (create / query / delete)
 * and the preview programs within each channel.
 */
@Singleton
class ChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: RecommendationDataStore
) {
    companion object {
        private const val TAG = "ChannelManager"
    }

    // ────────────────────────────────────────────────────────────────
    //  Channel operations
    // ────────────────────────────────────────────────────────────────

    /**
     * Creates a channel on the TV launcher if it doesn't already exist.
     * Returns the channel ID (from ContentProvider) or `null` on failure.
     */
    suspend fun getOrCreateChannel(
        internalId: String,
        displayName: String
    ): Long? {
        // 1. Check cached ID first
        val cachedId = dataStore.getChannelId(internalId)
        if (cachedId != null && channelExists(cachedId)) {
            return cachedId
        }

        // 2. Search the provider for a channel we previously inserted
        val existingId = findChannelByInternalId(internalId)
        if (existingId != null) {
            dataStore.setChannelId(internalId, existingId)
            return existingId
        }

        // 3. Insert a brand-new channel
        return try {
            val channel = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(displayName)
                .setAppLinkIntentUri(
                    Uri.parse("${RecommendationConstants.DEEP_LINK_SCHEME}://${RecommendationConstants.DEEP_LINK_HOST}")
                )
                .setInternalProviderId(internalId)
                .build()

            val channelUri = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channel.toContentValues()
            )

            if (channelUri == null) {
                Log.w(TAG, "ContentResolver.insert returned null for channel: $internalId")
                return null
            }

            val channelId = ContentUris.parseId(channelUri)
            dataStore.setChannelId(internalId, channelId)

            // Request the system to make this channel visible on the home screen.
            // On first call the user gets a prompt; subsequent calls are no-ops.
            TvContractCompat.requestChannelBrowsable(context, channelId)

            Log.i(TAG, "Created channel '$displayName' (id=$channelId)")
            channelId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create channel '$displayName'", e)
            null
        }
    }

    /**
     * Deletes all programs inside a channel so we can insert a fresh set.
     */
    fun clearProgramsForChannel(channelId: Long) {
        try {
            val uri = TvContractCompat.buildPreviewProgramsUriForChannel(channelId)
            val deleted = context.contentResolver.delete(uri, null, null)
            Log.d(TAG, "Cleared $deleted programs from channel $channelId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear programs for channel $channelId", e)
        }
    }

    /**
     * Inserts a list of [PreviewProgram]s into a channel via bulk insert.
     */
    fun insertPrograms(programs: List<PreviewProgram>) {
        if (programs.isEmpty()) return
        try {
            val values = programs.map { it.toContentValues() }.toTypedArray()
            val inserted = context.contentResolver.bulkInsert(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                values
            )
            Log.d(TAG, "Inserted $inserted preview programs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert preview programs", e)
        }
    }

    /**
     * Deletes the channel and removes its cached id.
     */
    suspend fun deleteChannel(internalId: String) {
        val channelId = dataStore.getChannelId(internalId) ?: return
        try {
            val uri = TvContractCompat.buildChannelUri(channelId)
            context.contentResolver.delete(uri, null, null)
            dataStore.clearChannelId(internalId)
            Log.i(TAG, "Deleted channel $internalId (id=$channelId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete channel $internalId", e)
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────

    private fun channelExists(channelId: Long): Boolean {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                TvContractCompat.buildChannelUri(channelId),
                arrayOf(TvContractCompat.Channels._ID),
                null, null, null
            )
            cursor != null && cursor.count > 0
        } catch (e: Exception) {
            false
        } finally {
            cursor?.close()
        }
    }

    private fun findChannelByInternalId(internalId: String): Long? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(
                    TvContractCompat.Channels._ID,
                    TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )
            cursor?.let {
                while (it.moveToNext()) {
                    val idIndex = it.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                    if (idIndex >= 0 && it.getString(idIndex) == internalId) {
                        val channelIdIndex = it.getColumnIndex(TvContractCompat.Channels._ID)
                        if (channelIdIndex >= 0) return it.getLong(channelIdIndex)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error querying channels", e)
            null
        } finally {
            cursor?.close()
        }
    }
}
