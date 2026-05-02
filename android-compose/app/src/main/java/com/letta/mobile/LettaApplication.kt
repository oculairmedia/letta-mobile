package com.letta.mobile

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.letta.mobile.channel.ChannelHeartbeatScheduler
import com.letta.mobile.channel.ChannelNotificationPublisher
import com.letta.mobile.clientmode.ClientModeController
import com.letta.mobile.debug.AutomationAuthBootstrap
import com.letta.mobile.bot.heartbeat.BotHeartbeatScheduler
import com.letta.mobile.bot.service.BotServiceAutoStarter
import com.letta.mobile.crash.CrashReporter
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.performance.DebugPerformanceMonitor
import com.letta.mobile.performance.ProductionJankStatsMonitor
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
        // CrashReporter chains itself on top of Sentry's handler so
        // unhandled exceptions are both uploaded to Sentry and persisted
        // to app-private storage for surfacing on the next launch.
        crashReporter.install()
        setupGlobalExceptionHandler()
        channelNotificationPublisher.ensureChannel()
        if (isRobolectricRuntime()) {
            return
        }
        clientModeController.get().initialize()
        runCatching {
            AutomationAuthBootstrap.importPendingConfig(this, settingsRepository.get())
        }.onFailure { error ->
            Log.w("LettaApp", "Skipping automation auth bootstrap", error)
        }
        runCatching {
            AutomationAuthBootstrap.importPendingConfig(this, settingsRepository.get())
        }.onFailure { error ->
            Log.w("LettaApp", "Skipping automation auth bootstrap", error)
        }
        runCatching {
            ProductionJankStatsMonitor.install(this)
        }.onFailure { error ->
            Log.w("LettaApp", "Skipping production jank monitor", error)
        }
        applicationScope.launch {
            runCatching {
                DebugPerformanceMonitor.install(this@LettaApplication)
            }.onFailure { error ->
                Log.w("LettaApp", "Skipping debug performance monitor", error)
            }
        }
        runCatching {
            channelHeartbeatScheduler.schedule()
        }.onFailure { error ->
            Log.w("LettaApp", "Skipping heartbeat scheduling", error)
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
