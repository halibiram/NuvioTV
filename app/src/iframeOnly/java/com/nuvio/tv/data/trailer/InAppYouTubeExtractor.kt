package com.nuvio.tv.data.trailer

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InAppYouTubeExtractor @Inject constructor() {
    suspend fun extractPlaybackSource(youtubeUrl: String): TrailerPlaybackSource? = null
}
