package com.letta.mobile

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.letta.mobile.channel.ChannelHeartbeatScheduler
import com.letta.mobile.channel.ChannelNotificationPublisher
import dagger.hilt.android.HiltAndroidApp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okio.Path.Companion.toOkioPath
import javax.inject.Inject

@HiltAndroidApp
class LettaApplication : Application(), SingletonImageLoader.Factory {
    @Inject
    lateinit var channelHeartbeatScheduler: ChannelHeartbeatScheduler

    @Inject
    lateinit var channelNotificationPublisher: ChannelNotificationPublisher

    private val imageHttpClient: HttpClient by lazy {
        val cacheDir = java.io.File(cacheDir, "coil_http_cache")
        val cacheSize = 20L * 1024 * 1024

        HttpClient(OkHttp) {
            engine {
                config {
                    followRedirects(true)
                    followSslRedirects(true)
                    cache(okhttp3.Cache(cacheDir, cacheSize))
                }
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setupGlobalExceptionHandler()
        channelNotificationPublisher.ensureChannel()
        if (isRobolectricRuntime()) {
            return
        }
        runCatching {
            channelHeartbeatScheduler.schedule()
        }.onFailure { error ->
            Log.w("LettaApp", "Skipping heartbeat scheduling", error)
        }
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = imageHttpClient))
            }
            .crossfade(true)
            .diskCache {
                DiskCache.Builder()
                    .directory(java.io.File(cacheDir, "image_cache").toOkioPath())
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    private fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("LettaApp", "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun isRobolectricRuntime(): Boolean {
        return runCatching {
            Class.forName("org.robolectric.RuntimeEnvironment")
        }.isSuccess
    }
}
