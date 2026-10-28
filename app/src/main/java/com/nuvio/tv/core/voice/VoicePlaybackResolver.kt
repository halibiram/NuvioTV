package com.nuvio.tv.core.voice

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

interface VoiceResumeSource {
    suspend fun latest(): WatchProgress?
}

@Singleton
class WatchProgressVoiceResumeSource @Inject constructor(
    private val watchProgressRepository: WatchProgressRepository
) : VoiceResumeSource {
    override suspend fun latest(): WatchProgress? {
        return watchProgressRepository.continueWatching.first().maxByOrNull { it.lastWatched }
    }
}

@Singleton
class VoicePlaybackResolver @Inject constructor(
    private val titleSearch: VoiceTitleSearch,
    private val resumeSource: VoiceResumeSource
) {
    suspend fun resolve(request: VoicePlaybackRequest): VoiceResolution {
        if (request.resume) {
            val progress = resumeSource.latest() ?: return VoiceResolution.NotFound
            return VoiceResolution.Resume(progress)
        }
        if (request.title.isNullOrBlank() && request.id.isNullOrBlank()) {
            return VoiceResolution.NotFound
        }
        if (request.id != null && request.type != null) {
            return VoiceResolution.Play(matchFromRequest(request, request.type), request.season, request.episode)
        }
        if (request.id != null && request.season != null) {
            return VoiceResolution.Play(matchFromRequest(request, "series"), request.season, request.episode)
        }

        val ranked = VoiceMatchRanker.rank(request, titleSearch.search(request))
        if (request.id != null) {
            ranked.matches.firstOrNull { it.id.equals(request.id, ignoreCase = true) }?.let { match ->
                return VoiceResolution.Play(match, request.season, request.episode)
            }
        }
        ranked.winner?.let { return VoiceResolution.Play(it, request.season, request.episode) }
        if (ranked.matches.isEmpty()) return VoiceResolution.NotFound
        return VoiceResolution.Choose(ranked.matches)
    }

    private fun matchFromRequest(request: VoicePlaybackRequest, type: String): VoiceMatch {
        val id = request.id.orEmpty()
        return VoiceMatch(
            id = id,
            type = type,
            name = request.title?.takeIf { it.isNotBlank() } ?: id,
            releaseInfo = null,
            poster = null,
            addonBaseUrl = null
        )
    }
}
