package com.nuvio.tv.core.voice

import android.app.SearchManager
import android.content.Intent
import android.provider.MediaStore

/**
 * Reads `android.media.action.MEDIA_PLAY_FROM_SEARCH` the way
 * [Common intents](https://developer.android.com/guide/components/intents-common)
 * define it: [MediaStore.EXTRA_MEDIA_TITLE] when present, otherwise
 * [SearchManager.QUERY]. The spoken sentence is not interpreted here.
 */
object VoiceSearchIntent {
    fun parse(intent: Intent): VoicePlaybackRequest {
        return VoiceQueryParser.parse(
            VoiceSearchFields(
                query = intent.getStringExtra(SearchManager.QUERY),
                title = intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE)
            )
        )
    }
}
