package com.letta.mobile.data.api

import android.content.Context
import android.util.Log
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryContext
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ApiSession(val client: HttpClient, val baseUrl: String)

class AdminApiUnavailableForLocalRuntimeException : IllegalStateException(
    "Admin API widgets require an HTTP(S) Letta or admin-shim URL. Local LettaCode is a chat runtime selector, not an Admin API endpoint."
)

/**
 * letta-mobile-qfa81 (P4 iroh purity): thrown by [LettaApiClient] when an Admin
 * API call is attempted while the active backend is an Iroh endpoint
 * (`iroh://<ticket>`). The Admin API MUST travel over `admin_rpc` frames on the
 * Iroh transport for such backends; silently falling back to some *other* saved
 * HTTP config would send the call to a stale, possibly unrelated server (the
 * dangerous path P4 closes). This is a HARD failure by design: a route with no
 * `admin_rpc` path must fail visibly rather than read the wrong backend over
 * HTTP. Route the call over [com.letta.mobile.data.transport.api.IChannelTransport.adminRpc]
 * (see IrohAdminRpc*Source / IrohAdminRpcTimelineTransport) or gate the feature
 * off while `iroh://` is active.
 */
class IrohAdminApiUnavailableException(serverUrl: String) : IllegalStateException(
    "Admin API call attempted while the active backend is an Iroh endpoint ($serverUrl). " +
        "This route has no admin_rpc path, so it will not fall back to a stale HTTP config. " +
        "Route it over IChannelTransport.adminRpc or gate it off in iroh:// mode."
)

@Singleton
open class LettaApiClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: ISettingsRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val telemetryContext = TelemetryContext()
    private val mutex = Mutex()
    private var cachedClient: HttpClient? = null
    private var cachedBaseUrl: String? = null
    private var cachedToken: String? = null

    open suspend fun getClient(): HttpClient = session().client

    open suspend fun session(): ApiSession {
        val adminConfig = resolveAdminApiConfig()
        val url = adminConfig?.serverUrl?.trim() ?: DEFAULT_ADMIN_API_URL
        val token = adminConfig?.accessToken?.trim()

        return mutex.withLock {
            if (cachedClient != null && cachedBaseUrl == url && cachedToken == token) {
                return@withLock ApiSession(
                    client = cachedClient ?: error("cachedClient null after null check"),
                    baseUrl = url.trimEnd('/'),
                )
            }
            cachedClient?.close()
            cachedClient = createClient(token, if (url.endsWith("/")) url else "$url/")
            cachedBaseUrl = url
            cachedToken = token
            ApiSession(
                client = cachedClient ?: error("cachedClient null after initialization"),
                baseUrl = url.trimEnd('/'),
            )
        }
    }

    open fun getBaseUrl(): String {
        return resolveAdminApiConfig()?.serverUrl ?: DEFAULT_ADMIN_API_URL
    }

    fun invalidateClient() {
        cachedClient?.close()
        cachedClient = null
        cachedBaseUrl = null
        cachedToken = null
    }

    private fun resolveAdminApiConfig(): LettaConfig? {
        val activeConfig = settingsRepository.activeConfig.value ?: return null

        // letta-mobile-qfa81 (P4 iroh purity) CHOKE POINT: this guard MUST run
        // before the HTTP check because a corrupted `https://iroh://<ticket>`
        // config also startsWith "https://" yet is still an Iroh endpoint, not
        // an HTTP admin server. When the active backend is Iroh, hard-fail
        // instead of scanning `configs` for any stale HTTP config — a single
        // guard here closes ~30 per-Api call sites at once. Admin reads with an
        // Iroh path route over admin_rpc before ever reaching this client;
        // anything still landing here has no Iroh path and must fail visibly.
        if (IrohChannelTransport.isIrohUrl(activeConfig.serverUrl)) {
            throw IrohAdminApiUnavailableException(activeConfig.serverUrl)
        }

        if (activeConfig.serverUrl.isHttpAdminUrl()) {
            return activeConfig
        }

        return settingsRepository.configs.value
            .asReversed()
            .firstOrNull { it.serverUrl.isHttpAdminUrl() }
            ?: throw AdminApiUnavailableForLocalRuntimeException()
    }

    private fun String.isHttpAdminUrl(): Boolean {
        val normalized = trim()
        return normalized.startsWith("https://", ignoreCase = true) ||
            normalized.startsWith("http://", ignoreCase = true)
    }

    private fun createClient(apiKey: String?, baseUrl: String): HttpClient {
        val cacheDir = java.io.File(context.cacheDir, "http_cache")
        val cacheSize = 10L * 1024 * 1024

        // letta-mobile-kxsv: dedicated dispatcher with a higher
        // per-host concurrency limit. Default OkHttp Dispatcher caps
        // maxRequestsPerHost = 5, but this client routinely runs:
        //   - up to ChatPushService.MAX_BACKGROUND_PERSISTENT_STREAMS (5)
        //     long-running idle stream pollers against letta.oculair.ca, and
        //   - the user's interactive send-stream against the same host,
        //     which must NOT queue behind the pollers.
        //
        // 5 background slots + queued foreground = the user-visible hang
        // Emmanuel hit on 2026-04-25. 16 gives generous headroom for
        // pollers + active send + reconcile fetches without burdening
        // the server or the device. (Server side already has its own
        // per-IP rate limiting.)
        val httpDispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }

        return HttpClient(OkHttp) {
            engine {
                config {
                    followRedirects(true)
                    followSslRedirects(true)
                    cache(okhttp3.Cache(cacheDir, cacheSize))
                    dispatcher(httpDispatcher)
                    // Keep long-lived streams alive at the transport layer when the
                    // connection negotiates HTTP/2. HTTP/1.1 SSE still depends on
                    // app/server heartbeats plus the explicit stream watchdog.
                    pingInterval(30, TimeUnit.SECONDS)
                }
                addInterceptor(TelemetryInterceptor(telemetryContext))
            }

            install(ContentNegotiation) {
                json(json)
            }

            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        Log.d("LettaApiClient", message)
                    }
                }
                level = if (com.letta.mobile.core.BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.NONE
                sanitizeHeader { header -> header == "Authorization" }
            }

            if (apiKey != null) {
                install(Auth) {
                    bearer {
                        loadTokens {
                            BearerTokens(apiKey, apiKey)
                        }
                    }
                }
            }

            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                exponentialDelay()
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 60_000
            }

            // Pin the non-throwing behavior on non-2xx responses. Ktor 3.x
            // defaults to false, but several repositories (MessageApi.kt:227
            // routing 400 EXPIRED → NoActiveRunException, VibesyncEventStream-
            // Repository.kt:87 routing 404 → EndpointUnavailableException,
            // letta-mobile-t8q7 + bddd3b16) depend on it. Pin it explicitly
            // so a future Ktor upgrade or accidental override doesn't silently
            // break those status-code branches. Matches ServerHealthRepository
            // and poc/chat-cli/LettaApi.
            expectSuccess = false

            defaultRequest {
                url(baseUrl)
            }
        }
    }

    private companion object {
        const val DEFAULT_ADMIN_API_URL = "https://api.letta.com"
    }
}
