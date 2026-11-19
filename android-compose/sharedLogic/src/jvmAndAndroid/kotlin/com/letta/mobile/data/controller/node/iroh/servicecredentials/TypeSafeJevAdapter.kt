package com.letta.mobile.data.controller.node.iroh.servicecredentials

import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.servicecredentials.ServiceConfigField
import com.letta.mobile.data.servicecredentials.ServiceCredentialErrorCode
import com.letta.mobile.data.servicecredentials.ServiceId
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * TypeSafe Jev, the typed-judgement API (`POST /v1/systemone`, Bearer key). Its test asks one Score
 * question about [SYNTHETIC_TEXT], a fixed sentence compiled in here: no conversation or caller text
 * ever goes to TypeSafe from this adapter. An isolated client: no logging, no redirects, and no
 * response body is passed on, only whether the answer had the expected shape.
 */
internal class TypeSafeJevAdapter(engine: HttpClientEngine) : ServiceAdapter {
    private val client = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    override val descriptor: ServiceDescriptor = DESCRIPTOR

    override suspend fun test(target: ServiceTarget): ServiceCredentialErrorCode? = try {
        val response = client.post("${target.baseUrl.trimEnd('/')}$SYSTEM_ONE_PATH") {
            header(HttpHeaders.Authorization, "Bearer ${target.secret}")
            setBody(TextContent(probeBody(target.config[MODEL_KEY] ?: DEFAULT_MODEL).toString(), ContentType.Application.Json))
        }
        when (response.status) {
            HttpStatusCode.OK -> if (answered(response.bodyAsText())) null else ServiceCredentialErrorCode.UPSTREAM_ERROR
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> ServiceCredentialErrorCode.UNAUTHORIZED
            HttpStatusCode.TooManyRequests -> ServiceCredentialErrorCode.RATE_LIMITED
            else -> ServiceCredentialErrorCode.UPSTREAM_ERROR
        }
    } catch (expectedTimeout: HttpRequestTimeoutException) {
        ServiceCredentialErrorCode.UPSTREAM_TIMEOUT
    } catch (expectedTimeout: ConnectTimeoutException) {
        ServiceCredentialErrorCode.UPSTREAM_TIMEOUT
    } catch (expectedTimeout: SocketTimeoutException) {
        ServiceCredentialErrorCode.UPSTREAM_TIMEOUT
    } catch (expectedFailure: IOException) {
        ServiceCredentialErrorCode.UNREACHABLE
    }

    override fun toString(): String = "TypeSafeJevAdapter"

    /** Whether a 200 body carries an answer to the probe question; the body itself goes nowhere. */
    private fun answered(body: String): Boolean = runCatching {
        val answers = LettaJson.wire.parseToJsonElement(body).jsonObject["answers"]?.jsonObject
        answers?.get(PROBE_QUESTION) is JsonObject
    }.getOrDefault(false)

    companion object {
        /** The only text the test sends: a fixed, made-up sentence. */
        const val SYNTHETIC_TEXT: String = "The weather report for the test city says it will be mild and sunny tomorrow."

        /** The id of the test's one question. */
        const val PROBE_QUESTION: String = "connection_check"

        /** The settings key naming the Jev model. */
        const val MODEL_KEY: String = "model"

        private const val DEFAULT_MODEL = "jev-latest"
        private const val SYSTEM_ONE_PATH = "/v1/systemone"
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val REQUEST_TIMEOUT_MS = 15_000L

        private val DESCRIPTOR = ServiceDescriptor(
            id = ServiceId("typesafe-jev"),
            displayName = "TypeSafe Jev",
            summary = "Typed judgements (score, choice, yes/no) for host features such as the voice mood gate.",
            defaultBaseUrl = "https://api.typesafe.ai",
            baseUrlEditable = false,
            secretLabel = "API key",
            configFields = listOf(
                ServiceConfigField(MODEL_KEY, "Model", "The Jev model or alias requests use.", DEFAULT_MODEL),
            ),
        )

        /** The test request: one Score question over [SYNTHETIC_TEXT]. */
        internal fun probeBody(model: String): JsonObject = buildJsonObject {
            put("state", SYNTHETIC_TEXT)
            put("model", model)
            putJsonObject("questions") {
                putJsonObject(PROBE_QUESTION) {
                    put("type", "score")
                    put("instructions", "How positive the sentence sounds")
                    putJsonArray("criteria") {
                        add("Negative")
                        add("Neutral")
                        add("Positive")
                    }
                }
            }
        }
    }
}
