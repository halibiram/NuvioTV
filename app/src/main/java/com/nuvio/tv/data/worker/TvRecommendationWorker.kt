package com.nuvio.tv.data.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nuvio.tv.core.recommendations.RecommendationDataStore
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodically syncs TV Home Screen recommendation channels in the background.
 * Scheduled via WorkManager every 30 minutes (configurable in [RecommendationConstants]).
 *
 * Retries up to 3 times on transient failures; after that it reports failure
 * so the periodic schedule continues on the next window.
 */
@HiltWorker
class TvRecommendationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val recommendationManager: TvRecommendationManager,
    private val recommendationDataStore: RecommendationDataStore
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "TvRecWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            if (!recommendationDataStore.isEnabled()) {
                Log.d(TAG, "Recommendations disabled — skipping sync")
                return Result.success()
            }

            recommendationManager.syncAllChannels()
            Log.d(TAG, "Periodic recommendation sync completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Recommendation sync failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
