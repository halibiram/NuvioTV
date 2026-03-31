package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TrailerPlaybackMode
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.data.remote.api.TrailerResponse
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class TrailerServiceYouTubeSessionCacheTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `reuses successful playback source for same youtube key within session`() = runTest {
        val trailerApi = mockk<TrailerApi>()
        val tmdbApi = mockk<TmdbApi>()
        val extractor = mockk<InAppYouTubeExtractor>()
        val trailerSettingsDataStore = mockk<TrailerSettingsDataStore>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val tmdbService = mockk<TmdbService>()
        every { trailerSettingsDataStore.settings } returns flowOf(TrailerSettings(playbackMode = TrailerPlaybackMode.IN_APP))
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings(language = "en"))
        every { tmdbService.apiKey() } returns "tmdb-key"
        val service = TrailerService(trailerApi, tmdbApi, extractor, trailerSettingsDataStore, tmdbSettingsDataStore, tmdbService)

        val cached = TrailerPlaybackSource(
            videoUrl = "https://cdn.example/video.mp4",
            audioUrl = "https://cdn.example/audio.m4a"
        )
        coEvery { extractor.extractPlaybackSource(any()) } returnsMany listOf(
            cached,
            TrailerPlaybackSource(videoUrl = "https://cdn.example/should-not-be-used.mp4")
        )

        val first = service.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val second = service.getTrailerPlaybackSourceFromYouTubeUrl("https://youtu.be/dQw4w9WgXcQ")

        assertEquals("https://cdn.example/video.mp4", first?.videoUrl)
        assertEquals("https://cdn.example/video.mp4", second?.videoUrl)
        assertEquals("https://cdn.example/audio.m4a", second?.audioUrl)
        coVerify(exactly = 1) { extractor.extractPlaybackSource(any()) }
        coVerify(exactly = 0) { trailerApi.getTrailer(any(), any(), any()) }
    }

    @Test
    fun `returns null when in-app mode cannot resolve youtube trailer`() = runTest {
        val trailerApi = mockk<TrailerApi>()
        val tmdbApi = mockk<TmdbApi>()
        val extractor = mockk<InAppYouTubeExtractor>()
        val trailerSettingsDataStore = mockk<TrailerSettingsDataStore>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val tmdbService = mockk<TmdbService>()
        every { trailerSettingsDataStore.settings } returns flowOf(TrailerSettings(playbackMode = TrailerPlaybackMode.IN_APP))
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings(language = "en"))
        every { tmdbService.apiKey() } returns "tmdb-key"
        val service = TrailerService(trailerApi, tmdbApi, extractor, trailerSettingsDataStore, tmdbSettingsDataStore, tmdbService)

        coEvery { extractor.extractPlaybackSource("https://www.youtube.com/watch?v=dQw4w9WgXcQ") } returns null
        coEvery { trailerApi.getTrailer(any(), any(), any()) } returns Response.success(TrailerResponse(url = null))

        val first = service.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val second = service.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        assertNull(first)
        assertNull(second)
        coVerify(exactly = 2) { extractor.extractPlaybackSource("https://www.youtube.com/watch?v=dQw4w9WgXcQ") }
        coVerify(exactly = 2) { trailerApi.getTrailer(any(), any(), any()) }
    }

    @Test
    fun `returns youtube iframe source directly when iframe mode is selected`() = runTest {
        val trailerApi = mockk<TrailerApi>()
        val tmdbApi = mockk<TmdbApi>()
        val extractor = mockk<InAppYouTubeExtractor>()
        val trailerSettingsDataStore = mockk<TrailerSettingsDataStore>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val tmdbService = mockk<TmdbService>()
        every { trailerSettingsDataStore.settings } returns flowOf(TrailerSettings(playbackMode = TrailerPlaybackMode.YOUTUBE_IFRAME))
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings(language = "en"))
        every { tmdbService.apiKey() } returns "tmdb-key"
        val service = TrailerService(trailerApi, tmdbApi, extractor, trailerSettingsDataStore, tmdbSettingsDataStore, tmdbService)

        val first = service.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val second = service.getTrailerPlaybackSourceFromYouTubeUrl("https://youtu.be/dQw4w9WgXcQ")

        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", first?.videoUrl)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", second?.videoUrl)
        coVerify(exactly = 0) { extractor.extractPlaybackSource(any()) }
        coVerify(exactly = 0) { trailerApi.getTrailer(any(), any(), any()) }
    }
}
