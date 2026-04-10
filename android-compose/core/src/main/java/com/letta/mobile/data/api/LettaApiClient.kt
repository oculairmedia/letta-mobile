package com.letta.mobile.data.api

import android.content.Context
import android.util.Log
import com.letta.mobile.data.repository.SettingsRepository
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class LettaApiClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val mutex = Mutex()
    private var cachedClient: HttpClient? = null
    private var cachedBaseUrl: String? = null
    private var cachedToken: String? = null

    open suspend fun getClient(): HttpClient {
        val config = settingsRepository.activeConfig.value
        val url = config?.serverUrl?.trim() ?: "https://api.letta.com"
        val token = config?.accessToken?.trim()

        mutex.withLock {
            if (cachedClient != null && cachedBaseUrl == url && cachedToken == token) {
                return cachedClient!!
            }
            cachedClient?.close()
            cachedClient = createClient(token, if (url.endsWith("/")) url else "$url/")
            cachedBaseUrl = url
            cachedToken = token
            return cachedClient!!
        }
    }

    open fun getBaseUrl(): String {
        val config = settingsRepository.activeConfig.value
        return config?.serverUrl ?: "https://api.letta.com"
    }

    fun invalidateClient() {
        cachedClient?.close()
        cachedClient = null
        cachedBaseUrl = null
        cachedToken = null
    }

    private fun createClient(apiKey: String?, baseUrl: String): HttpClient {
        val cacheDir = java.io.File(context.cacheDir, "http_cache")
        val cacheSize = 10L * 1024 * 1024

        return HttpClient(OkHttp) {
            engine {
                config {
                    followRedirects(true)
                    followSslRedirects(true)
                    cache(okhttp3.Cache(cacheDir, cacheSize))
                }
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

            defaultRequest {
                url(baseUrl)
            }
        }
    }
}
