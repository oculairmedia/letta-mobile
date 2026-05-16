package com.letta.mobile

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.letta.mobile.crash.CrashReporter
import com.letta.mobile.startup.AppStartupCoordinator
import com.letta.mobile.util.EncryptedPrefsHelper
import dagger.hilt.android.HiltAndroidApp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okio.Path.Companion.toOkioPath
import okhttp3.ConnectionPool
import javax.inject.Inject

@HiltAndroidApp
class LettaApplication : Application(), SingletonImageLoader.Factory {
    @Inject
    lateinit var crashReporter: CrashReporter

    @Inject
    internal lateinit var appStartupCoordinator: AppStartupCoordinator

    /**
     * Eagerly inject the legacy static-bridge singleton (syf4 migration) so
     * `getEncryptedPrefs(...)` calls (still used during repository construction
     * before the migration to constructor injection completes) never see a null
     * `INSTANCE`. Without this, the first caller to reach the static bridge
     * before any other code requests the singleton will NPE on `INSTANCE!!`.
     *
     * `EncryptedPrefsHelper` is critical because `SettingsRepository.<init>`
     * calls `EncryptedPrefsHelper.getEncryptedPrefs(...)` synchronously.
     */
    @Suppress("unused")
    @Inject
    lateinit var encryptedPrefsHelper: EncryptedPrefsHelper

    private val imageHttpClient: HttpClient by lazy {
        val cacheDir = java.io.File(cacheDir, "coil_http_cache")
        val cacheSize = 20L * 1024 * 1024

        HttpClient(OkHttp) {
            engine {
                config {
                    followRedirects(true)
                    followSslRedirects(true)
                    cache(okhttp3.Cache(cacheDir, cacheSize))
                    // 5 idle connections, 120s keep-alive. Image loads in
                    // chat are bursty (multiple attachments per message)
                    // so pooling prevents repeated TLS handshakes.
                    connectionPool(ConnectionPool(5, 120, java.util.concurrent.TimeUnit.SECONDS))
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
        // CrashReporter and global exception handler must run on main thread
        // before anything else so uncaught exceptions are captured from the start.
        crashReporter.install()
        setupGlobalExceptionHandler()
        if (isRobolectricRuntime()) {
            return
        }
        appStartupCoordinator.start(this)
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                @OptIn(ExperimentalCoilApi::class)
                add(KtorNetworkFetcherFactory(httpClient = imageHttpClient))
            }
            // Cap memory cache at 20% of available heap. Coil's default (25%)
            // is generous; chat message lists + decoded bitmaps already
            // consume significant memory so we keep the ceiling tighter.
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, percent = 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(java.io.File(cacheDir, "image_cache").toOkioPath())
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            // 200ms crossfade: fast enough for list scrolling, visible enough
            // to mask the swap from placeholder to real image.
            .crossfade(200)
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
