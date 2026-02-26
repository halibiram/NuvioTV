package com.nuvio.tv

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.nuvio.tv.core.sync.StartupSyncService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), ImageLoaderFactory {

    @Inject lateinit var startupSyncService: StartupSyncService

    override fun onCreate() {
        super.onCreate()
    }

    override fun newImageLoader(): ImageLoader {
        // Dedicated OkHttpClient for image loading with:
        // - Shorter connect timeout (15s) to fail fast on unreachable hosts
        // - Longer read timeout (30s) for large images on slow connections
        // - Retry on connection failure enabled
        val imageOkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(imageOkHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.30)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            // Increase parallelism to avoid heavy queuing on screens with many images
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(4))
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(8))
            .bitmapFactoryMaxParallelism(4)
            // Respect all cache layers to avoid redundant network fetches
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .allowRgb565(true)
            .crossfade(false)
            // Log failed image loads for debugging
            .logger(object : coil.util.Logger {
                override var minLevel: coil.util.Logger.Level = coil.util.Logger.Level.Warn
                override fun log(
                    tag: String,
                    level: coil.util.Logger.Level,
                    message: String?,
                    throwable: Throwable?
                ) {
                    if (level >= coil.util.Logger.Level.Warn) {
                        Log.w("CoilImageLoader", "$message", throwable)
                    }
                }
            })
            .build()
    }
}
