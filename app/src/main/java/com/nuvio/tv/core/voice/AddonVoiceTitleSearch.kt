package com.nuvio.tv.core.voice

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

interface VoiceAddonSource {
    suspend fun enabledAddons(): List<Addon>
}

interface VoiceTitleSearch {
    suspend fun search(request: VoicePlaybackRequest): List<VoiceMatch>
}

@Singleton
class InstalledVoiceAddonSource @Inject constructor(
    private val addonRepository: AddonRepository,
    private val serverCatalog: ServerCatalog
) : VoiceAddonSource {
    override suspend fun enabledAddons(): List<Addon> {
        return addonRepository.getInstalledAddons().first().enabledAddons() +
            serverCatalog.searchAddons.first()
    }
}

@Singleton
class AddonVoiceTitleSearch @Inject constructor(
    private val addonSource: VoiceAddonSource,
    private val catalogRepository: CatalogRepository
) : VoiceTitleSearch {
    override suspend fun search(request: VoicePlaybackRequest): List<VoiceMatch> {
        val query = request.title?.trim().orEmpty().ifBlank { request.id?.trim().orEmpty() }
        if (query.length < 2) return emptyList()
        val targets = addonSource.enabledAddons()
            .flatMap { addon ->
                addon.catalogs
                    .filter { catalog -> catalog.acceptsVoiceSearch() && catalog.matchesVoiceType(request.type) }
                    .map { catalog -> addon to catalog }
            }
            .take(MAX_CATALOGS)
        if (targets.isEmpty()) return emptyList()

        return coroutineScope {
            targets.map { (addon, catalog) ->
                async { searchCatalog(addon, catalog, query) }
            }.flatMap { it.await() }
        }.distinctBy { it.id to it.type }
    }

    private suspend fun searchCatalog(
        addon: Addon,
        catalog: CatalogDescriptor,
        query: String
    ): List<VoiceMatch> {
        val result = try {
            withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                catalogRepository.getCatalog(
                    addonBaseUrl = addon.baseUrl,
                    addonId = addon.id,
                    addonName = addon.displayName,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = catalog.apiType,
                    extraArgs = mapOf("search" to query),
                    supportsSkip = catalog.supportsExtra("skip"),
                    posterScreen = CustomPosterScreen.SEARCH
                ).first { it !is NetworkResult.Loading }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val row = (result as? NetworkResult.Success)?.data ?: return emptyList()
        return row.items.mapNotNull { item ->
            if (item.id.startsWith("__placeholder_") || item.name.isBlank()) return@mapNotNull null
            val type = item.type.toVoiceType(item.rawType) ?: return@mapNotNull null
            VoiceMatch(
                id = item.id,
                type = type,
                name = item.name,
                releaseInfo = item.releaseInfo,
                poster = item.poster,
                addonBaseUrl = item.sourceAddonBaseUrl ?: addon.baseUrl
            )
        }
    }

    private companion object {
        const val MAX_CATALOGS = 6
        const val CATALOG_TIMEOUT_MS = 2_500L
    }
}

internal fun CatalogDescriptor.acceptsVoiceSearch(): Boolean {
    return supportsExtra("search") &&
        extra.none { property ->
            property.isRequired && !property.name.equals("search", ignoreCase = true)
        }
}

internal fun CatalogDescriptor.matchesVoiceType(type: String?): Boolean {
    val api = apiType.lowercase()
    val isMovie = api == "movie"
    val isSeries = api == "series" || api == "tv"
    return when (type) {
        "movie" -> isMovie
        "series" -> isSeries
        else -> isMovie || isSeries
    }
}

private fun ContentType.toVoiceType(raw: String): String? = when (this) {
    ContentType.MOVIE -> "movie"
    ContentType.SERIES, ContentType.TV -> "series"
    ContentType.UNKNOWN -> when (raw.trim().lowercase()) {
        "movie" -> "movie"
        "series", "tv" -> "series"
        else -> null
    }
    else -> null
}
