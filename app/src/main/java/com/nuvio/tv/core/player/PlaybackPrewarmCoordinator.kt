package com.nuvio.tv.core.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamLinkCacheDataStore
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Overlaps stream search, last-link, AFR, ExoPlayer construction, and
 * PreloadMediaSource sample-queue prefetch with Detail/CW/Stream.
 * ExoPlayer.prepare() still runs after claim once a surface exists, so Fire TV
 * does not hold a second idle decoder.
 */
@Singleton
class PlaybackPrewarmCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val streamRepository: StreamRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val trailerPlayerPool: TrailerPlayerPool,
    private val prewarmedPlayerFactory: PrewarmedPlayerFactory
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = PlaybackPrewarmGate()
    private val lock = Any()
    private var engineSnapshot: PlaybackPrewarmEngineSnapshot? = null
    private var streamWarmJob: Job? = null
    private var lastLinkWarmJob: Job? = null
    private var resolvedWarmJob: Job? = null
    private var lastStreamKey: PlaybackPrewarmStreamKey? = null
    private var lastClaimedUrl: String? = null
    private var contentPlaybackActive: Boolean = false

    fun warmStreams(
        key: PlaybackPrewarmStreamKey,
        includeResolvedLastLink: Boolean = true
    ) {
        if (key.type.isBlank() || key.videoId.isBlank()) return
        synchronized(lock) {
            if (lastStreamKey == key && streamWarmJob?.isActive == true) return
            lastStreamKey = key
            streamWarmJob?.cancel()
            streamWarmJob = scope.launch {
                Log.i(
                    TAG,
                    "PREWARM_START phase=streams type=${key.type} videoId=${key.videoId} " +
                        "S${key.season ?: "-"}E${key.episode ?: "-"}"
                )
                runCatching {
                    streamRepository.getStreamsFromAllAddons(
                        type = key.type,
                        videoId = key.videoId,
                        season = key.season,
                        episode = key.episode,
                        forceRefresh = false
                    ).collect { /* session cache is populated by the repository */ }
                }
            }
        }
        if (includeResolvedLastLink) {
            lastLinkWarmJob?.cancel()
            lastLinkWarmJob = scope.launch {
                maybeWarmLastLink(key)
            }
        }
    }

    fun warmResolvedPlayback(request: PlaybackPrewarmMediaRequest) {
        val url = request.url
        if (url.isBlank()) return
        val started = gate.beginResolved(
            PlaybackPrewarmSession(
                url = url,
                headers = request.headers,
                filename = request.filename,
                resumePositionMs = request.resumePositionMs,
                resumeProgress = request.resumeProgress,
                streamKey = PlaybackPrewarmStreamKey(
                    type = "",
                    videoId = request.videoId.orEmpty(),
                    season = request.season,
                    episode = request.episode,
                    contentId = request.contentId
                ).takeIf { !request.videoId.isNullOrBlank() },
                startedAtElapsedMs = SystemClock.elapsedRealtime()
            )
        )
        when (started) {
            PlaybackPrewarmBeginResult.AlreadyWarm -> {
                Log.d(TAG, "PREWARM_START phase=resolved alreadyWarm host=${safeHost(url)}")
                return
            }
            is PlaybackPrewarmBeginResult.Started -> {
                synchronized(lock) { releaseEngineSnapshotLocked() }
                started.previous?.let { previous ->
                    Log.i(TAG, "PREWARM_START phase=resolved replaced host=${safeHost(previous.url)}")
                }
            }
        }
        yieldForContentPlayback()
        resolvedWarmJob?.cancel()
        resolvedWarmJob = scope.launch {
            Log.i(TAG, "PREWARM_START phase=resolved host=${safeHost(url)}")
            val settings = playerSettingsDataStore.playerSettings.first()
            val resume = if (request.startFromBeginning) {
                0L to null
            } else {
                loadResume(request)
            }
            if (resume.first > 0L || resume.second != null) {
                gate.updateProgress(url, resume.first, resume.second)
            }
            warmAfrIfNeeded(
                url = url,
                headers = request.headers,
                filename = request.filename,
                mode = settings.frameRateMatchingMode
            )
            if (!PlaybackPrewarmPolicy.shouldWarmEngine(
                    url = url,
                    isTorrent = request.isTorrent,
                    playerPreference = settings.playerPreference,
                    engine = settings.internalPlayerEngine,
                    contentPlaybackActive = synchronized(lock) { contentPlaybackActive }
                )
            ) {
                Log.i(TAG, "PREWARM_BUILD_MS skipped host=${safeHost(url)}")
                return@launch
            }
            gate.markPrepareStarted(url)
            val buildStartedAt = SystemClock.elapsedRealtime()
            val snapshot = try {
                prewarmedPlayerFactory.prepare(
                    request.copy(resumePositionMs = resume.first)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "PREWARM build failed host=${safeHost(url)}", error)
                null
            }
            val buildMs = SystemClock.elapsedRealtime() - buildStartedAt
            Log.i(TAG, "PREWARM_BUILD_MS=$buildMs host=${safeHost(url)} snapshot=${snapshot != null}")
            if (snapshot == null) return@launch
            try {
                ensureActive()
            } catch (cancelled: CancellationException) {
                prewarmedPlayerFactory.release(snapshot)
                throw cancelled
            }
            synchronized(lock) {
                if (!shouldRetainPreparedSnapshotLocked(url)) {
                    prewarmedPlayerFactory.release(snapshot)
                    return@launch
                }
                releaseEngineSnapshotLocked()
                engineSnapshot = snapshot
            }
        }
    }

    fun yieldForContentPlayback() {
        streamRepository.setLocalPluginSearchPaused(true)
        val yieldNow = { trailerPlayerPool.yield() }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            yieldNow()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(yieldNow)
        }
    }

    fun peekResumeProgress(url: String): WatchProgress? {
        val session = gate.currentSession() ?: return null
        if (!PlaybackPrewarmPolicy.urlsMatch(session.url, url)) return null
        return session.resumeProgress
    }

    fun peekResumePositionMs(url: String): Long {
        val session = gate.currentSession() ?: return 0L
        if (!PlaybackPrewarmPolicy.urlsMatch(session.url, url)) return 0L
        return session.resumePositionMs
    }

    fun claim(expectedUrl: String, adoptEngine: Boolean): PlaybackPrewarmClaimResult {
        streamRepository.setLocalPluginSearchPaused(true)
        val decision = gate.claim(expectedUrl)
        val snapshot = synchronized(lock) {
            when (decision) {
                is PlaybackPrewarmClaimResult.Hit -> {
                    contentPlaybackActive = true
                    val current = engineSnapshot
                    engineSnapshot = null
                    lastClaimedUrl = if (current == null) expectedUrl else null
                    current
                }
                PlaybackPrewarmClaimResult.Mismatch -> {
                    lastClaimedUrl = null
                    releaseEngineSnapshotLocked()
                    null
                }
                PlaybackPrewarmClaimResult.Miss -> {
                    lastClaimedUrl = null
                    null
                }
            }
        }
        return when (decision) {
            is PlaybackPrewarmClaimResult.Hit -> {
                if (!adoptEngine && snapshot != null) {
                    prewarmedPlayerFactory.release(snapshot)
                    logClaim("hit-no-engine", expectedUrl)
                    PlaybackPrewarmClaimResult.Hit(decision.ticket.copy(engineSnapshot = null))
                } else {
                    logClaim(if (snapshot != null) "hit" else "hit-metadata", expectedUrl)
                    PlaybackPrewarmClaimResult.Hit(decision.ticket.copy(engineSnapshot = snapshot))
                }
            }
            PlaybackPrewarmClaimResult.Mismatch -> {
                logClaim("mismatch", expectedUrl)
                PlaybackPrewarmClaimResult.Mismatch
            }
            PlaybackPrewarmClaimResult.Miss -> {
                logClaim("miss", expectedUrl)
                PlaybackPrewarmClaimResult.Miss
            }
        }
    }

    fun takeEngineSnapshotIfPresent(): PlaybackPrewarmEngineSnapshot? {
        synchronized(lock) {
            val current = engineSnapshot ?: return null
            engineSnapshot = null
            lastClaimedUrl = null
            contentPlaybackActive = true
            return current
        }
    }

    fun abandonUnclaimedEngine() {
        resolvedWarmJob?.cancel()
        synchronized(lock) {
            lastClaimedUrl = null
            releaseEngineSnapshotLocked()
        }
    }

    fun notifyPlaybackReleased() {
        synchronized(lock) {
            contentPlaybackActive = false
            lastClaimedUrl = null
            releaseEngineSnapshotLocked()
        }
        streamRepository.setLocalPluginSearchPaused(false)
    }

    fun abort(reason: String) {
        val session = gate.abort()
        if (session == null) {
            Log.d(TAG, "PREWARM abort ignored reason=$reason transferred=${gate.hasTransferredOwnership()}")
            return
        }
        resolvedWarmJob?.cancel()
        synchronized(lock) {
            lastClaimedUrl = null
            releaseEngineSnapshotLocked()
        }
        streamRepository.setLocalPluginSearchPaused(false)
        Log.i(TAG, "PREWARM abort reason=$reason host=${safeHost(session.url)}")
    }

    private suspend fun maybeWarmLastLink(key: PlaybackPrewarmStreamKey) {
        val settings = playerSettingsDataStore.playerSettings.first()
        if (!settings.streamReuseLastLinkEnabled) return
        if (settings.playerPreference != PlayerPreference.INTERNAL) return
        val cacheKey = "${key.type.lowercase()}|${key.videoId}"
        val cached = streamLinkCacheDataStore.getValid(
            contentKey = cacheKey,
            maxAgeMs = settings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
        ) ?: return
        val url = cached.url.takeIf { it.isNotBlank() } ?: return
        warmResolvedPlayback(
            PlaybackPrewarmMediaRequest(
                url = url,
                headers = cached.headers,
                filename = cached.filename,
                contentId = key.contentId,
                videoId = key.videoId,
                season = key.season,
                episode = key.episode,
                isTorrent = cached.infoHash != null && cached.url.isBlank()
            )
        )
    }

    private suspend fun loadResume(
        request: PlaybackPrewarmMediaRequest
    ): Pair<Long, WatchProgress?> {
        if (request.startFromBeginning) return 0L to null
        if (request.resumeProgress != null) {
            val saved = request.resumeProgress
            val position = when {
                saved.duration > 0L -> saved.resolveResumePosition(saved.duration)
                saved.position > 0L -> saved.position
                else -> 0L
            }
            return position.coerceAtLeast(0L) to saved
        }
        val contentId = request.contentId ?: return request.resumePositionMs to null
        val progress = runCatching {
            if (request.season != null && request.episode != null) {
                watchProgressRepository.getEpisodeProgress(contentId, request.season, request.episode).firstOrNull()
            } else {
                watchProgressRepository.getProgress(contentId).firstOrNull()
            }
        }.getOrNull()
        val inProgress = progress?.takeIf { it.isInProgress() }
        val position = when {
            inProgress == null -> request.resumePositionMs
            inProgress.duration > 0L -> inProgress.resolveResumePosition(inProgress.duration)
            inProgress.position > 0L -> inProgress.position
            else -> request.resumePositionMs
        }
        return position.coerceAtLeast(0L) to inProgress
    }

    private suspend fun warmAfrIfNeeded(
        url: String,
        headers: Map<String, String>,
        filename: String?,
        mode: FrameRateMatchingMode
    ) {
        if (mode == FrameRateMatchingMode.OFF) return
        if (FrameRateUtils.getCachedFrameRate(url, headers, filename) != null) {
            gate.markAfrComplete(url)
            Log.i(TAG, "PREWARM_AFR_MS=0 cache=hit host=${safeHost(url)}")
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        val detection = withContext(Dispatchers.IO) {
            val probeJob = kotlin.coroutines.coroutineContext[Job]
            FrameRateUtils.detectFrameRateWithOkHttpProbe(
                context = context,
                sourceUrl = url,
                headers = FrameRateUtils.streamHeadersForAfrProbe(headers),
                filename = filename,
                isCancelled = { probeJob?.isActive != true }
            )
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (detection != null) {
            FrameRateUtils.cacheFrameRate(url, headers, detection, filename)
            gate.markAfrComplete(url)
        }
        Log.i(
            TAG,
            "PREWARM_AFR_MS=$elapsed hit=${detection != null} fps=${detection?.snapped ?: -1f} " +
                "host=${safeHost(url)}"
        )
    }

    private fun shouldRetainPreparedSnapshotLocked(url: String): Boolean {
        if (PlaybackPrewarmPolicy.urlsMatch(gate.currentSession()?.url, url)) return true
        return PlaybackPrewarmPolicy.urlsMatch(lastClaimedUrl, url)
    }

    private fun releaseEngineSnapshotLocked() {
        val snapshot = engineSnapshot ?: return
        engineSnapshot = null
        prewarmedPlayerFactory.release(snapshot)
    }

    private fun logClaim(outcome: String, url: String) {
        Log.i(TAG, "PREWARM_CLAIM $outcome host=${safeHost(url)}")
    }

    private fun safeHost(url: String): String {
        return runCatching {
            android.net.Uri.parse(url).host ?: "unknown"
        }.getOrDefault("unknown")
    }

    private companion object {
        const val TAG = "PlaybackPrewarm"
    }
}
