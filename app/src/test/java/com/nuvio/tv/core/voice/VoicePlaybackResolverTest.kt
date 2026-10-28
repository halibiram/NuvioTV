package com.nuvio.tv.core.voice

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackResolverTest {

    @Test
    fun `empty search resumes and a titled search keeps the original words`() = runTest {
        val search = RecordingSearch(
            listOf(match("tt1", "movie", "Kara Sevda"))
        )
        val resolver = VoicePlaybackResolver(search, FakeResume(progress("Kara Sevda")))

        val resumed = resolver.resolve(VoiceQueryParser.parse(""))
        assertTrue(resumed is VoiceResolution.Resume)
        assertEquals(null, search.last)

        val played = resolver.resolve(VoiceQueryParser.parse("Kara Sevda"))
        assertEquals("Kara Sevda", search.last?.title)
        assertTrue(played is VoiceResolution.Play)
        assertEquals("tt1", (played as VoiceResolution.Play).match.id)
    }

    @Test
    fun `season words are searched as part of the title`() = runTest {
        val search = RecordingSearch(emptyList())
        val resolver = VoicePlaybackResolver(search, FakeResume(null))

        val resolution = resolver.resolve(VoiceQueryParser.parse("3. sezon 5. bölüm"))

        assertEquals("3. sezon 5. bölüm", search.last?.title)
        assertTrue(resolution is VoiceResolution.NotFound)
    }

    @Test
    fun `addon search receives the title unchanged`() = runTest {
        val catalog = FakeCatalog()
        val search = AddonVoiceTitleSearch(FakeAddons(searchableAddon()), catalog)

        search.search(VoiceQueryParser.parse("千と千尋の神隠し"))

        assertEquals("千と千尋の神隠し", catalog.lastQuery)
    }

    @Test
    fun `an ambiguous title is one choose link and never a stream url`() {
        val request = VoiceQueryParser.parse("Dune")
        val suggestions = VoiceAssistantSuggestions.from(
            request,
            VoiceResolution.Choose(
                listOf(
                    match("tt1", "movie", "Dune"),
                    match("tt2", "movie", "Dune: Part Two")
                )
            )
        )

        assertEquals(1, suggestions.size)
        assertEquals("nuvio://voice/choose?q=Dune", suggestions.single().intentData)
        assertTrue(suggestions.single().intentData.startsWith("nuvio://"))
    }

    private fun match(id: String, type: String, name: String) = VoiceMatch(
        id = id,
        type = type,
        name = name,
        releaseInfo = null,
        poster = "https://example.test/poster.jpg",
        addonBaseUrl = "https://addon.example"
    )

    private fun progress(name: String) = WatchProgress(
        contentId = "tt1",
        contentType = "series",
        name = name,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1:1:1",
        season = 1,
        episode = 1,
        episodeTitle = null,
        position = 1L,
        duration = 10L,
        lastWatched = 5L
    )

    private fun searchableAddon(): Addon {
        return Addon(
            id = "addon",
            name = "Addon",
            version = "1",
            description = null,
            logo = null,
            baseUrl = "https://addon.example",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "top",
                    name = "Top",
                    extra = listOf(CatalogExtra(name = "search"))
                )
            ),
            types = listOf(ContentType.MOVIE),
            resources = emptyList()
        )
    }

    private class RecordingSearch(
        private val results: List<VoiceMatch>
    ) : VoiceTitleSearch {
        var last: VoicePlaybackRequest? = null

        override suspend fun search(request: VoicePlaybackRequest): List<VoiceMatch> {
            last = request
            return results
        }
    }

    private class FakeResume(private val item: WatchProgress?) : VoiceResumeSource {
        override suspend fun latest(): WatchProgress? = item
    }

    private class FakeAddons(private val addon: Addon) : VoiceAddonSource {
        override suspend fun enabledAddons(): List<Addon> = listOf(addon)
    }

    private class FakeCatalog : CatalogRepository {
        var lastQuery: String? = null

        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean,
            posterScreen: CustomPosterScreen
        ): Flow<NetworkResult<CatalogRow>> {
            lastQuery = extraArgs["search"]
            return flowOf(
                NetworkResult.Success(
                    CatalogRow(
                        addonId = addonId,
                        addonName = addonName,
                        addonBaseUrl = addonBaseUrl,
                        catalogId = catalogId,
                        catalogName = catalogName,
                        type = ContentType.MOVIE,
                        items = emptyList()
                    )
                )
            )
        }
    }
}
