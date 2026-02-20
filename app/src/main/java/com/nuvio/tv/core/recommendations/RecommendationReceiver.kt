package com.nuvio.tv.core.recommendations

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives the system broadcast [android.media.tv.action.INITIALIZE_PROGRAMS]
 * which is sent when the TV launcher needs our channels to be populated
 * (e.g. after a device reboot or first install).
 */
@AndroidEntryPoint
class RecommendationReceiver : BroadcastReceiver() {

    @Inject
    lateinit var recommendationManager: TvRecommendationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.media.tv.action.INITIALIZE_PROGRAMS") {
            Log.i("RecommendationReceiver", "INITIALIZE_PROGRAMS received — syncing channels")
            val pendingResult = goAsync()
            scope.launch {
                try {
                    recommendationManager.syncAllChannels()
                } catch (e: Exception) {
                    Log.e("RecommendationReceiver", "Sync failed", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
