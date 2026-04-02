package com.nuvio.tv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.bitmapFactoryMaxParallelism
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.nuvio.tv.core.network.buildWithAppDns
import com.nuvio.tv.core.network.NetworkDnsInitializer
import com.nuvio.tv.core.sync.StartupSyncService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import okio.Path.Companion.toOkioPath

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var networkDnsInitializer: NetworkDnsInitializer

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        networkDnsInitializer.start(applicationScope)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            okhttp3.OkHttpClient.Builder().buildWithAppDns()
                        }
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(2))
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(4))
            .bitmapFactoryMaxParallelism(2)
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }
}
