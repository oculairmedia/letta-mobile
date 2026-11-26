package com.letta.mobile

import ca.oculair.meridian.BuildConfig
import com.letta.mobile.util.attr
import com.letta.mobile.ui.chat.surface.timeline.rows.TimelineRenderProbe

import com.letta.mobile.ui.components.beam.telemetry.installBeamShaderTelemetry

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.letta.mobile.data.platform.MemoryPressure
import com.letta.mobile.platform.AndroidMemoryPressure
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okio.Path.Companion.toOkioPath
import okhttp3.ConnectionPool
import javax.inject.Inject

@HiltAndroidApp
class LettaApplication : Application(), SingletonImageLoader.Factory {
    private val telemetryRecorder = com.letta.mobile.util.TelemetryRecorder()
    private val telemetryRecording = com.letta.mobile.util.Telemetry.installRecorder(telemetryRecorder)
    private var flightRecording: AutoCloseable? = null

    override fun onTerminate() {
        flightRecording?.close()
        telemetryRecording.close()
        super.onTerminate()
    }

    /**
     * Single field inject required by `@HiltAndroidApp`. Collaborators live on
     * [LettaApplicationDependencies] via constructor injection.
     *
     * Constructing [LettaApplicationDependencies] eagerly creates
     * [EncryptedPrefsHelper] so the legacy static-bridge singleton (syf4) is
     * never null when storage providers call `getEncryptedPrefs(...)`.
     */
    @Inject
    lateinit var deps: LettaApplicationDependencies

    private val crashReporter get() = deps.crashReporter
    private val appStartupCoordinator get() = deps.appStartupCoordinator

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
        com.letta.mobile.util.Telemetry.delegate = com.letta.mobile.util.AndroidTelemetryDelegate()
        // Connection, turn and feed telemetry kept past logcat: `adb shell run-as <package> cat files/telemetry/flight-record.log`.
        flightRecording = com.letta.mobile.util.flight.installFileFlightRecording(
            telemetryRecorder, java.io.File(filesDir, "telemetry"), BuildConfig.VERSION_NAME,
        )
        // Core-loop capture is opt-in on the root debug flavour; release never enables it.
        com.letta.mobile.util.Telemetry.renderDiagEnabled.set(
            BuildConfig.DEBUG && BuildConfig.FLAVOR == "root" &&
                android.util.Log.isLoggable("LettaCoreLoop", android.util.Log.VERBOSE),
        )
        if (BuildConfig.DEBUG && BuildConfig.FLAVOR == "root") {
            com.letta.mobile.util.Telemetry.event(
                "CoreLoop", "gate",
                "enabled" attr com.letta.mobile.util.Telemetry.renderDiagEnabled.get(),
            )
        }
        if (com.letta.mobile.util.Telemetry.renderDiagEnabled.get()) {
            TimelineRenderProbe.sink = com.letta.mobile.ui.chat.surface.timeline.rows.TimelineRenderSink {
                    name, key, identity, stored, assistant, hashes ->
                if (name == "draw") {
                    com.letta.mobile.util.Telemetry.event(
                        "CoreLoop", name, "key" attr key, "identity" attr identity,
                        "stored" attr stored, "assistant" attr assistant, "hashes" attr hashes,
                    )
                } else {
                    com.letta.mobile.util.Telemetry.event("CoreLoop", name, "key" attr key)
                }
            }
        }
        installBeamShaderTelemetry()
        // CrashReporter and global exception handler must run on main thread
        // before anything else so uncaught exceptions are captured from the start.
        crashReporter.install()
        setupGlobalExceptionHandler()
        // An Iroh host relays an agent's shell_layout calls here; they change the shell on screen.
        com.letta.mobile.data.shell.layout.edit.ShellLayoutRelay.install()
        if (isRobolectricRuntime()) {
            return
        }
        deps.notebookLocalStore.startPolling(1_000)
        appStartupCoordinator.start(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // onLowMemory is the same event as TRIM_MEMORY_COMPLETE, which already maps to CRITICAL here.
        AndroidMemoryPressure.levelFor(level)?.let(MemoryPressure::emit)
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
