package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerPreference

object PlaybackPrewarmPolicy {

    fun urlsMatch(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        return normalizeUrl(left) == normalizeUrl(right)
    }

    fun decideClaim(
        session: PlaybackPrewarmSession?,
        expectedUrl: String
    ): PlaybackPrewarmClaimResult {
        if (session == null) return PlaybackPrewarmClaimResult.Miss
        return if (urlsMatch(session.url, expectedUrl)) {
            PlaybackPrewarmClaimResult.Hit(PlaybackPrewarmTicket(session = session, engineSnapshot = null))
        } else {
            PlaybackPrewarmClaimResult.Mismatch
        }
    }

    fun shouldReplaceWarm(existingUrl: String?, newUrl: String): Boolean {
        if (newUrl.isBlank()) return false
        return !urlsMatch(existingUrl, newUrl)
    }

    fun shouldWarmEngine(
        url: String?,
        isTorrent: Boolean,
        playerPreference: PlayerPreference,
        engine: InternalPlayerEngine,
        contentPlaybackActive: Boolean = false
    ): Boolean {
        if (contentPlaybackActive) return false
        if (url.isNullOrBlank()) return false
        if (isTorrent) return false
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        if (playerPreference != PlayerPreference.INTERNAL) return false
        return engine == InternalPlayerEngine.EXOPLAYER
    }

    fun canAbortAndRelease(hasTransferredOwnership: Boolean): Boolean = !hasTransferredOwnership

    internal fun normalizeUrl(url: String): String = url.trim()
}
