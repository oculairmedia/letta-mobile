package com.letta.mobile.data.api

import android.util.Log
import com.letta.mobile.data.repository.SettingsRepository
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LettaApiClient @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    suspend fun getClient(): HttpClient {
        val config = settingsRepository.activeConfig.first()
        var baseUrl = config?.serverUrl?.trim() ?: "https://api.letta.com"
        // Ensure URL ends with slash for proper path resolution
        if (!baseUrl.endsWith("/")) {
            baseUrl = "$baseUrl/"
        }
        return createClient(config?.accessToken?.trim(), baseUrl)
    }

    suspend fun getBaseUrl(): String {
        val config = settingsRepository.activeConfig.first()
        return config?.serverUrl ?: "https://api.letta.com"
    }

    private fun createClient(apiKey: String?, baseUrl: String): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                config {
                    followRedirects(true)
                    followSslRedirects(true)
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
