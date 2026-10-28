package com.nuvio.tv.core.voice

import com.nuvio.tv.domain.model.Video

fun findVoiceEpisode(videos: List<Video>, season: Int, episode: Int): Video? {
    return videos.firstOrNull { it.season == season && it.episode == episode && it.available != false }
        ?: videos.firstOrNull { it.season == season && it.episode == episode }
}
