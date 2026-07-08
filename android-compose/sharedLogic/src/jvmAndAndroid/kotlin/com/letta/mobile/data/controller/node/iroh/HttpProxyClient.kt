package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AdminProxyHttpException(
    val statusCode: Int,
    val bodyText: String,
) : RuntimeException("HTTP $statusCode: $bodyText")

data class AdminProxyResponse(
    val statusCode: Int,
    val body: JsonElement,
)

data class AdminProxyTransportResponse(
    val statusCode: Int,
    val bodyText: String,
)

fun interface AdminProxyTransport {
    fun execute(method: String, url: String, body: String?): AdminProxyTransportResponse
}

class AdminProxyRequest private constructor(
    private val segments: List<String>,
    private val queryParams: List<Pair<String, String>>,
) {
    fun url(baseUrl: String): String {
        val path = segments.joinToString(separator = "/", prefix = "/") { encode(it) }
        val query = queryParams.joinToString(separator = "&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return baseUrl.trimEnd('/') + path + query.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
    }

    class Builder {
        private val segments = mutableListOf<String>()
        private val queryParams = mutableListOf<Pair<String, String>>()

        fun segment(value: String) = apply { segments += value }
        fun segments(vararg values: String) = apply { segments += values }
        fun query(key: String, value: String?) = apply {
            if (value != null) queryParams += key to value
        }
        fun build(): AdminProxyRequest = AdminProxyRequest(segments.toList(), queryParams.toList())
    }

    companion object {
        fun path(vararg segments: String): AdminProxyRequest = Builder().segments(*segments).build()

        private fun encode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}

class AdminProxyClient(
    private val adminBaseUrl: String,
    private val transport: AdminProxyTransport = defaultTransportFactory(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    private val baseUrl = adminBaseUrl.trimEnd('/')

    companion object {
        internal var defaultTransportFactory: () -> AdminProxyTransport = { HttpUrlConnectionAdminProxyTransport() }
    }

    fun get(request: AdminProxyRequest): JsonElement = execute("GET", request)
    fun post(request: AdminProxyRequest, body: String = "{}"): JsonElement = execute("POST", request, body)
    fun patch(request: AdminProxyRequest, body: String): JsonElement = execute("PATCH", request, body)
    fun delete(request: AdminProxyRequest): JsonElement = execute("DELETE", request)

    fun execute(method: String, request: AdminProxyRequest, body: String? = null): JsonElement =
        response(method, request, body).body

    fun response(method: String, request: AdminProxyRequest, body: String? = null): AdminProxyResponse {
        val url = request.url(baseUrl)
        val startMs = System.currentTimeMillis()
        return try {
            val response = transport.execute(method, url, body)
            Telemetry.event(
                "AdminRpc",
                "proxy.$method",
                "url" to url,
                "code" to response.statusCode,
                "ms" to (System.currentTimeMillis() - startMs),
            )
            if (response.statusCode !in 200..299) {
                throw AdminProxyHttpException(response.statusCode, response.bodyText)
            }
            AdminProxyResponse(response.statusCode, json.parseToJsonElement(response.bodyText))
        } catch (e: AdminProxyHttpException) {
            throw e
        } catch (e: Exception) {
            Telemetry.event("AdminRpc", "proxy.failed", "url" to url, "error" to (e.message ?: ""))
            throw e
        }
    }
}

class HttpUrlConnectionAdminProxyTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000,
) : AdminProxyTransport {
    override fun execute(method: String, url: String, body: String?): AdminProxyTransportResponse {
        // HttpURLConnection hard-rejects PATCH (ProtocolException "Invalid HTTP
        // method: PATCH") and reflection is sealed on modern JDKs — this broke
        // admin_rpc agent.update (drawer model switch). Route PATCH through the
        // ktor CIO engine instead; other methods keep the proven URLConnection
        // path unchanged.
        if (method == "PATCH") return executePatchViaKtor(url, body)
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream).use { it.write(body) }
            }

            val statusCode = connection.responseCode
            val bodyText = if (statusCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "{\"error\":\"HTTP $statusCode\"}"
            }
            AdminProxyTransportResponse(statusCode, bodyText)
        } finally {
            connection.disconnect()
        }
    }
}

/** PATCH via ktor CIO (HttpURLConnection cannot send PATCH on any JDK). */
private fun executePatchViaKtor(url: String, body: String?): AdminProxyTransportResponse =
    kotlinx.coroutines.runBlocking {
        io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO).use { client ->
            val response = client.request(url) {
                this.method = io.ktor.http.HttpMethod.Patch
                header(io.ktor.http.HttpHeaders.ContentType, "application/json")
                header(io.ktor.http.HttpHeaders.Accept, "application/json")
                setBody(body ?: "{}")
            }
            AdminProxyTransportResponse(response.status.value, response.bodyAsText())
        }
    }

fun adminProxyRequest(vararg segments: String): AdminProxyRequest.Builder =
    AdminProxyRequest.Builder().segments(*segments)
