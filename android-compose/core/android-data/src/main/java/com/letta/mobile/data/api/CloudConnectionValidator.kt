package com.letta.mobile.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CloudConnectionValidationResult {
    data object Success : CloudConnectionValidationResult
    data class Failed(val message: String) : CloudConnectionValidationResult
}

@Singleton
open class CloudConnectionValidator @Inject constructor() {
    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = VALIDATION_TIMEOUT_MS
                connectTimeoutMillis = VALIDATION_TIMEOUT_MS
                socketTimeoutMillis = VALIDATION_TIMEOUT_MS
            }
            expectSuccess = false
        }
    }

    open suspend fun validate(
        baseUrl: String,
        apiToken: String,
    ): CloudConnectionValidationResult = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val trimmedToken = apiToken.trim()
        if (trimmedToken.isBlank()) {
            return@withContext CloudConnectionValidationResult.Failed(
                "Letta Cloud API key is required. Paste a key from app.letta.com before saving."
            )
        }

        val response = try {
            client.get("$normalizedBaseUrl/v1/agents") {
                header("Authorization", "Bearer $trimmedToken")
                parameter("limit", 1)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return@withContext CloudConnectionValidationResult.Failed(
                "Could not reach Letta Cloud. Check your connection and try again."
            )
        }

        return@withContext when (response.status.value) {
            in 200..299 -> CloudConnectionValidationResult.Success
            401 -> CloudConnectionValidationResult.Failed(
                "Letta Cloud rejected this API key. Check the key and paste it again."
            )
            403 -> CloudConnectionValidationResult.Failed(
                "This API key does not have access to Letta Cloud agents."
            )
            404 -> CloudConnectionValidationResult.Failed(
                "Letta Cloud did not expose the agents endpoint. Check the server URL."
            )
            408 -> CloudConnectionValidationResult.Failed("Letta Cloud timed out. Try again.")
            429 -> CloudConnectionValidationResult.Failed(
                "Letta Cloud is rate limiting this key. Wait a moment and try again."
            )
            in 500..599 -> CloudConnectionValidationResult.Failed(
                "Letta Cloud is having trouble right now. Try again later."
            )
            else -> CloudConnectionValidationResult.Failed(
                "Letta Cloud connection failed with HTTP ${response.status.value}."
            )
        }
    }

    private companion object {
        private const val VALIDATION_TIMEOUT_MS = 10_000L
    }
}
