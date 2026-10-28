package com.nuvio.tv.core.voice



import android.app.SearchManager

import android.content.ContentProvider

import android.content.ContentValues

import android.content.UriMatcher

import android.database.Cursor

import android.database.MatrixCursor

import android.net.Uri

import android.provider.BaseColumns

import android.util.Log

import com.nuvio.tv.R

import dagger.hilt.EntryPoint

import dagger.hilt.InstallIn

import dagger.hilt.android.EntryPointAccessors

import dagger.hilt.components.SingletonComponent

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.runBlocking

import kotlinx.coroutines.withTimeoutOrNull



/**

 * Global-search provider the TV assistant queries before it sends

 * [android.intent.action.VIEW] with [VoiceDeepLinks.EXTRA_START_PLAYBACK].

 * Rows contain a title and a nuvio:// link only — never an addon key or a stream URL.

 */

class VoiceSearchProvider : ContentProvider() {

    @EntryPoint

    @InstallIn(SingletonComponent::class)

    interface VoiceSearchEntryPoint {

        fun voicePlaybackResolver(): VoicePlaybackResolver

    }



    private lateinit var uriMatcher: UriMatcher



    override fun onCreate(): Boolean {

        val auth = authority()
        uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            registerSuggestPaths(auth)
        }

        return true

    }



    override fun query(

        uri: Uri,

        projection: Array<out String>?,

        selection: String?,

        selectionArgs: Array<out String>?,

        sortOrder: String?

    ): Cursor {

        if (uri.authority != authority()) {
            Log.w(TAG, "Ignoring unknown voice search authority: $uri")
            return emptyCursor()
        }
        if (uriMatcher.match(uri) == UriMatcher.NO_MATCH) {
            Log.i(TAG, "Voice search using fallback path handling for $uri")
        }

        val query = queryText(uri, selectionArgs)

        Log.i(TAG, "Voice search query='$query' uri=$uri")

        val request = VoiceQueryParser.parse(query)

        val suggestions = try {

            runBlocking(Dispatchers.IO) {

                withTimeoutOrNull(SEARCH_TIMEOUT_MS) { suggestionsFor(request) }.orEmpty()

            }

        } catch (error: Exception) {

            Log.w(TAG, "Voice search failed for '$query'", error)

            VoiceAssistantSuggestions.from(request, VoiceResolution.NotFound)

        }

        if (suggestions.isEmpty()) {

            Log.i(TAG, "Voice search returned no suggestions for '$query'")

        }

        return MatrixCursor(COLUMNS).apply {

            suggestions.forEachIndexed { index, suggestion ->

                addRow(

                    arrayOf<Any?>(

                        index.toLong(),

                        cardTitle(suggestion),

                        subtitle(suggestion),

                        suggestion.imageUrl,

                        suggestion.intentData,

                        android.content.Intent.ACTION_VIEW,

                        productionYear(suggestion.releaseInfo),

                        UNKNOWN_DURATION_MS

                    )

                )

            }

        }

    }



    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE



    override fun insert(uri: Uri, values: ContentValues?): Uri? = null



    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0



    override fun update(

        uri: Uri,

        values: ContentValues?,

        selection: String?,

        selectionArgs: Array<out String>?

    ): Int = 0



    private suspend fun suggestionsFor(request: VoicePlaybackRequest): List<VoiceSuggestion> {

        val appContext = context?.applicationContext ?: return VoiceAssistantSuggestions.from(

            request,

            VoiceResolution.NotFound

        )

        val resolver = EntryPointAccessors.fromApplication(

            appContext,

            VoiceSearchEntryPoint::class.java

        ).voicePlaybackResolver()

        return VoiceAssistantSuggestions.from(request, resolver.resolve(request))

    }



    private fun cardTitle(suggestion: VoiceSuggestion): String {

        if (suggestion.title.isNotBlank()) return suggestion.title

        val resources = context?.resources ?: return suggestion.title

        return when (suggestion.kind) {

            VoiceSuggestionKind.RESUME -> resources.getString(R.string.voice_suggestion_resume)

            VoiceSuggestionKind.CHOOSE -> resources.getString(R.string.voice_suggestion_choose)

            VoiceSuggestionKind.DETAIL -> suggestion.title

        }

    }



    private fun subtitle(suggestion: VoiceSuggestion): String? {

        val resources = context?.resources ?: return suggestion.releaseInfo

        return when (suggestion.kind) {

            VoiceSuggestionKind.RESUME -> resources.getString(R.string.voice_suggestion_resume)

            VoiceSuggestionKind.CHOOSE -> resources.getString(R.string.voice_suggestion_choose)

            VoiceSuggestionKind.DETAIL -> suggestion.releaseInfo

        }

    }



    private fun queryText(uri: Uri, selectionArgs: Array<out String>?): String {

        val fromArgs = selectionArgs?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        if (fromArgs != null) return Uri.decode(fromArgs)

        val last = uri.lastPathSegment?.trim().orEmpty()

        if (last.isNotEmpty() && last != SearchManager.SUGGEST_URI_PATH_QUERY) {

            return Uri.decode(last)

        }

        return uri.getQueryParameter("q").orEmpty()

    }



    private fun authority(): String = context?.packageName.orEmpty()



    private fun emptyCursor(): Cursor = MatrixCursor(COLUMNS)



    private fun productionYear(releaseInfo: String?): Long {

        if (releaseInfo.isNullOrBlank()) return 0L

        return YEAR.find(releaseInfo)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

    }



    private fun UriMatcher.registerSuggestPaths(auth: String) {
        val queryPath = SearchManager.SUGGEST_URI_PATH_QUERY
        addURI(auth, queryPath, SEARCH_SUGGEST)
        addURI(auth, "$queryPath/*", SEARCH_SUGGEST)
        addURI(auth, "search/$queryPath", SEARCH_SUGGEST)
        addURI(auth, "search/$queryPath/*", SEARCH_SUGGEST)
    }

    private companion object {

        const val TAG = "VoiceSearchProvider"

        const val SEARCH_TIMEOUT_MS = 4_000L

        const val SEARCH_SUGGEST = 1

        const val UNKNOWN_DURATION_MS = 0L

        val YEAR = Regex("(\\d{4})")

        val COLUMNS = arrayOf(

            BaseColumns._ID,

            SearchManager.SUGGEST_COLUMN_TEXT_1,

            SearchManager.SUGGEST_COLUMN_TEXT_2,

            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,

            SearchManager.SUGGEST_COLUMN_INTENT_DATA,

            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,

            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,

            SearchManager.SUGGEST_COLUMN_DURATION

        )

    }

}


