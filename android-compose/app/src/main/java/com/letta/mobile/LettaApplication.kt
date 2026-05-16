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
import com.letta.mobile.channel.ChannelHeartbeatScheduler
import com.letta.mobile.channel.ChannelNotificationPublisher
import com.letta.mobile.bot.clientmode.ClientModeController
import com.letta.mobile.debug.AutomationAuthBootstrap
import com.letta.mobile.bot.heartbeat.BotHeartbeatScheduler
import com.letta.mobile.bot.service.BotServiceAutoStarter
import com.letta.mobile.crash.CrashReporter
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.performance.DebugPerformanceMonitor
import com.letta.mobile.performance.ProductionJankStatsMonitor
import com.letta.mobile.util.EncryptedPrefsHelper
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import okhttp3.ConnectionPool
import javax.inject.Inject

@HiltAndroidApp
class LettaApplication : Application(), SingletonImageLoader.Factory {
    @Inject
    lateinit var channelHeartbeatScheduler: ChannelHeartbeatScheduler

    @Inject
    lateinit var channelNotificationPublisher: ChannelNotificationPublisher

    @Inject
    lateinit var botServiceAutoStarter: BotServiceAutoStarter

    @Inject
    lateinit var botHeartbeatScheduler: BotHeartbeatScheduler

    @Inject
    lateinit var crashReporter: CrashReporter

    @Inject
    lateinit var settingsRepository: Lazy<SettingsRepository>

    @Inject
    lateinit var clientModeController: Lazy<ClientModeController>

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

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val imageHttpClient: HttpClient by lazy {
        val cacheDir = java.io.File(cacheDir, "coil_http_cache")
        val cacheSize = 20L * 1024 * 1024

        HttpClient(OkHttp) {
            engine {
                config {
                    followRedirects(true)
                    followSslRedirects(true)
                    cache(okhttp3.Cache(cacheDir, cacheSize))
                    // 5 idle connections, 120s keep-alive — image loads in
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
        // Notification channel registration must happen before the app can post
        // any notifications, but not before first frame — safe to defer.
        applicationScope.launch {
            channelNotificationPublisher.ensureChannel()
        }
        applicationScope.launch {
            runCatching {
                AutomationAuthBootstrap.importPendingConfig(this@LettaApplication, settingsRepository.get())
            }.onFailure { error ->
                Log.w("LettaApp", "Skipping automation auth bootstrap", error)
            }
        }
        applicationScope.launch {
            runCatching {
                clientModeController.get().initialize()
            }.onFailure { error ->
                Log.w("LettaApp", "Skipping client mode init", error)
            }
        }
        applicationScope.launch {
            runCatching {
                ProductionJankStatsMonitor.install(this@LettaApplication)
            }.onFailure { error ->
                Log.w("LettaApp", "Skipping production jank monitor", error)
            }
        }
        applicationScope.launch {
            runCatching {
                DebugPerformanceMonitor.install(this@LettaApplication)
            }.onFailure { error ->
                Log.w("LettaApp", "Skipping debug performance monitor", error)
            }
        }
        applicationScope.launch {
            runCatching {
                channelHeartbeatScheduler.schedule()
            }.onFailure { error ->
                Log.w("LettaApp", "Skipping heartbeat scheduling", error)
            }
        }
        applicationScope.launch {
            runCatching {
                botServiceAutoStarter.restoreIfConfigured()
            }.onFailure { error ->
                Log.w("LettaApp", "Skipping bot auto-start restore", error)
            }
        }
        applicationScope.launch {
            runCatching {
                botHeartbeatScheduler.schedule()
            }.onFailure { error ->
                Log.w("LettaApp", "Skipping bot heartbeat scheduling", error)
            }
        }
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
            // 200ms crossfade — fast enough for list scrolling, visible enough
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
