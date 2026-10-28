package com.nuvio.tv.core.voice

import java.net.URLEncoder

object VoiceDeepLinks {
    const val EXTRA_START_PLAYBACK = "android.intent.extra.START_PLAYBACK"
    const val MEDIA_PLAY_FROM_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"

    fun resume(): String = "nuvio://voice/resume"

    fun detail(type: String, id: String, season: Int?, episode: Int?): String {
        val base = "nuvio://detail/${encode(type)}/${encode(id)}"
        val query = buildList {
            if (season != null) add("season=$season")
            if (episode != null) add("episode=$episode")
        }
        return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
    }

    fun choose(request: VoicePlaybackRequest): String {
        val query = buildList {
            request.title?.takeIf { it.isNotBlank() }?.let { add("q=${encode(it)}") }
            request.id?.takeIf { it.isNotBlank() }?.let { add("id=${encode(it)}") }
            request.type?.takeIf { it.isNotBlank() }?.let { add("type=${encode(it)}") }
            request.season?.let { add("season=$it") }
            request.episode?.let { add("episode=$it") }
        }
        return if (query.isEmpty()) resume() else "nuvio://voice/choose?${query.joinToString("&")}"
    }

    fun forUnresolved(request: VoicePlaybackRequest): String =
        if (request.resume) resume() else choose(request)

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
