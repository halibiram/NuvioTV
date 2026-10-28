package com.nuvio.tv.domain.deeplink

sealed interface AppDeepLink {
    data class Meta(
        val type: String,
        val id: String,
        val season: Int? = null,
        val episode: Int? = null
    ) : AppDeepLink

    data class VoiceQuery(
        val query: String,
        val id: String? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    ) : AppDeepLink

    data object VoiceResume : AppDeepLink

    data class AddonInstall(
        val manifestUrl: String
    ) : AppDeepLink

    data class Party(
        val code: String
    ) : AppDeepLink
}
