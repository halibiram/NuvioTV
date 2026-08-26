package com.nuvio.tv.core.player

/**
 * Thread-safe session gate for speculative playback construction.
 * Holds URL/AFR/resume metadata only; the coordinator owns any ExoPlayer snapshot.
 */
internal class PlaybackPrewarmGate {
    private val lock = Any()
    private var session: PlaybackPrewarmSession? = null
    private var transferredOwnership: Boolean = false

    fun currentSession(): PlaybackPrewarmSession? = synchronized(lock) { session }

    fun beginResolved(next: PlaybackPrewarmSession): PlaybackPrewarmBeginResult {
        synchronized(lock) {
            val existing = session
            if (!PlaybackPrewarmPolicy.shouldReplaceWarm(existing?.url, next.url)) {
                if (existing != null) {
                    session = existing.copy(
                        headers = next.headers.ifEmpty { existing.headers },
                        filename = next.filename ?: existing.filename,
                        mimeType = next.mimeType ?: existing.mimeType,
                        resumePositionMs = if (next.resumePositionMs > 0L) {
                            next.resumePositionMs
                        } else {
                            existing.resumePositionMs
                        },
                        resumeProgress = next.resumeProgress ?: existing.resumeProgress,
                        streamKey = next.streamKey ?: existing.streamKey,
                        afrComplete = existing.afrComplete || next.afrComplete,
                        prepareStarted = existing.prepareStarted || next.prepareStarted
                    )
                }
                return PlaybackPrewarmBeginResult.AlreadyWarm
            }
            session = next
            transferredOwnership = false
            return PlaybackPrewarmBeginResult.Started(existing)
        }
    }

    fun markAfrComplete(url: String) {
        synchronized(lock) {
            val current = session ?: return
            if (!PlaybackPrewarmPolicy.urlsMatch(current.url, url)) return
            session = current.copy(afrComplete = true)
        }
    }

    fun markPrepareStarted(url: String) {
        synchronized(lock) {
            val current = session ?: return
            if (!PlaybackPrewarmPolicy.urlsMatch(current.url, url)) return
            session = current.copy(prepareStarted = true)
        }
    }

    fun updateProgress(url: String, resumePositionMs: Long, resumeProgress: com.nuvio.tv.domain.model.WatchProgress?) {
        synchronized(lock) {
            val current = session ?: return
            if (!PlaybackPrewarmPolicy.urlsMatch(current.url, url)) return
            session = current.copy(
                resumePositionMs = resumePositionMs,
                resumeProgress = resumeProgress ?: current.resumeProgress
            )
        }
    }

    fun claim(expectedUrl: String): PlaybackPrewarmClaimResult {
        synchronized(lock) {
            val decision = PlaybackPrewarmPolicy.decideClaim(session, expectedUrl)
            when (decision) {
                is PlaybackPrewarmClaimResult.Hit -> {
                    val current = session
                    session = null
                    transferredOwnership = true
                    return PlaybackPrewarmClaimResult.Hit(
                        PlaybackPrewarmTicket(session = current!!, engineSnapshot = null)
                    )
                }
                PlaybackPrewarmClaimResult.Mismatch -> {
                    session = null
                    transferredOwnership = false
                    return PlaybackPrewarmClaimResult.Mismatch
                }
                PlaybackPrewarmClaimResult.Miss -> return PlaybackPrewarmClaimResult.Miss
            }
        }
    }

    fun abort(): PlaybackPrewarmSession? {
        synchronized(lock) {
            if (!PlaybackPrewarmPolicy.canAbortAndRelease(transferredOwnership)) {
                return null
            }
            val previous = session
            session = null
            transferredOwnership = false
            return previous
        }
    }

    fun hasTransferredOwnership(): Boolean = synchronized(lock) { transferredOwnership }
}
