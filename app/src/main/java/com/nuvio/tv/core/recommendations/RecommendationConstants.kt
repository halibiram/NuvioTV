package com.nuvio.tv.core.recommendations

/**
 * Constants used across the TV Home Screen Recommendations feature.
 */
object RecommendationConstants {

    // ── Channel internal IDs (used as internalProviderId for channel lookup) ──
    const val CHANNEL_NEW_RELEASES = "new_releases"
    const val CHANNEL_TRENDING = "trending"

    // ── Channel display names shown on the TV launcher ──
    const val CHANNEL_DISPLAY_NEW_RELEASES = "New Releases"
    const val CHANNEL_DISPLAY_TRENDING = "Trending"

    // ── Deep link URI components ──
    const val DEEP_LINK_SCHEME = "nuviotv"
    const val DEEP_LINK_HOST = "content"
    const val DEEP_LINK_PATH_PLAY = "play"
    const val DEEP_LINK_PATH_DETAIL = "detail"

    // Query parameter keys used inside deep link URIs
    const val PARAM_CONTENT_TYPE = "type"
    const val PARAM_VIDEO_ID = "videoId"
    const val PARAM_SEASON = "season"
    const val PARAM_EPISODE = "episode"
    const val PARAM_RESUME_POSITION = "position"
    const val PARAM_NAME = "name"
    const val PARAM_POSTER = "poster"
    const val PARAM_BACKDROP = "backdrop"

    // ── WorkManager ──
    const val WORK_NAME_PERIODIC_SYNC = "tv_recommendation_sync"
    const val SYNC_INTERVAL_MINUTES = 30L

    // ── Item limits per channel ──
    const val MAX_NEW_RELEASES_ITEMS = 25
    const val MAX_TRENDING_ITEMS = 20
    const val MAX_WATCH_NEXT_ITEMS = 10
}
