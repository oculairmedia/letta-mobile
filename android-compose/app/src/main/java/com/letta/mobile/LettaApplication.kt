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
import com.letta.mobile.bot.heartbeat.BotHeartbeatScheduler
import com.letta.mobile.bot.service.BotServiceAutoStarter
import com.letta.mobile.crash.CrashReporter
import com.letta.mobile.performance.DebugPerformanceMonitor
import com.letta.mobile.util.Telemetry
import dagger.hilt.android.HiltAndroidApp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        runStartupHooks()
    }

    internal fun runStartupHooks(
        isRobolectricRuntime: Boolean = isRobolectricRuntime(),
        runAsyncInline: Boolean = false,
    ) {
        val startMs = System.currentTimeMillis()
        Telemetry.event(APP_TAG, "onCreate.start")
        // CrashReporter chains itself on top of Sentry's handler so
        // unhandled exceptions are both uploaded to Sentry and persisted
        // to app-private storage for surfacing on the next launch.
        crashReporter.install()
        setupGlobalExceptionHandler()
        channelNotificationPublisher.ensureChannel()
        if (isRobolectricRuntime) {
            emitOnCreateEnd(startMs)
            return
        }

        runStartupComponent("channelHeartbeat") {
            channelHeartbeatScheduler.schedule()
        }

        val asyncStartup: suspend () -> Unit = {
            coroutineScope {
                launch {
                    runStartupComponentAsync("perfMonitor") {
                        DebugPerformanceMonitor.install(this@LettaApplication)
                    }
                }
                launch {
                    runStartupComponentAsync("botAutostart") {
                        botServiceAutoStarter.restoreIfConfigured()
                    }
                }
                launch {
                    runStartupComponentAsync("botHeartbeat") {
                        botHeartbeatScheduler.schedule()
                    }
                }
            }
        }

        if (runAsyncInline) {
            runBlocking { asyncStartup() }
            emitOnCreateEnd(startMs)
        } else {
            applicationScope.launch {
                try {
                    asyncStartup()
                } finally {
                    emitOnCreateEnd(startMs)
                }
            }
        }
    }

    override fun onTerminate() {
        Telemetry.event(APP_TAG, "onTerminate")
        super.onTerminate()
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return try {
            ImageLoader.Builder(context)
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
        } catch (t: Throwable) {
            Telemetry.error(APP_TAG, "init.failed", t, "component" to "imageLoader")
            throw t
        }
    }

    private fun runStartupComponent(component: String, block: () -> Unit) {
        runCatching {
            block()
        }.onFailure { error ->
            Telemetry.error(APP_TAG, "init.failed", error, "component" to component)
            Log.w(LOG_TAG, "Skipping $component initialization", error)
        }
    }

    private suspend fun runStartupComponentAsync(component: String, block: suspend () -> Unit) {
        runCatching {
            block()
        }.onFailure { error ->
            Telemetry.error(APP_TAG, "init.failed", error, "component" to component)
            Log.w(LOG_TAG, "Skipping $component initialization", error)
        }
    }

    private fun emitOnCreateEnd(startMs: Long) {
        Telemetry.event(
            APP_TAG,
            "onCreate.end",
            durationMs = System.currentTimeMillis() - startMs,
        )
    }

    private fun setupGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(LOG_TAG, "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun isRobolectricRuntime(): Boolean {
        return runCatching {
            Class.forName("org.robolectric.RuntimeEnvironment")
        }.isSuccess
    }

    private companion object {
        const val APP_TAG = "App"
        const val LOG_TAG = "LettaApp"
    }
}
