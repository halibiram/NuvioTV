package com.nuvio.tv.core.player

interface PrewarmedPlayerFactory {
    suspend fun prepare(request: PlaybackPrewarmMediaRequest): PlaybackPrewarmEngineSnapshot?
    fun release(snapshot: PlaybackPrewarmEngineSnapshot)
}
