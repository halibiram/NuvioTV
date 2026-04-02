package com.nuvio.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nuvio.tv.core.network.buildWithAppDns
import com.nuvio.tv.core.network.NetworkDnsInitializer
import com.nuvio.tv.core.sync.StartupSyncService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
@HiltAndroidApp
class NuvioApplication : Application(), ImageLoaderFactory {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var networkDnsInitializer: NetworkDnsInitializer

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        networkDnsInitializer.start(applicationScope)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                okhttp3.OkHttpClient.Builder().buildWithAppDns()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(2))
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(4))
            .bitmapFactoryMaxParallelism(2)
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }
}
