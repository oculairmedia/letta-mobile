package com.letta.mobile.cli.runtime

import com.github.ajalt.clikt.core.UsageError
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal suspend fun HttpClient.executeJsonRestRequest(
    verb: String,
    url: String,
    token: String,
    body: String?,
    headers: List<CliHeaderParam> = emptyList(),
): HttpResponse {
    val normalizedVerb = verb.uppercase()
    if (normalizedVerb == "GET" && body != null) {
        throw UsageError("GET does not support --body or --body-file")
    }
    return when (normalizedVerb) {
        "GET" -> get(url) {
            bearerAuth(token)
            jsonHeaders(headers)
        }
        "POST" -> post(url) {
            bearerAuth(token)
            jsonHeaders(headers)
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        "PUT" -> put(url) {
            bearerAuth(token)
            jsonHeaders(headers)
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        "PATCH" -> patch(url) {
            bearerAuth(token)
            jsonHeaders(headers)
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        "DELETE" -> delete(url) {
            bearerAuth(token)
            jsonHeaders(headers)
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        else -> error("Unsupported REST verb: $verb")
    }
}

internal fun formatJsonResponse(text: String, compact: Boolean, raw: Boolean): String? {
    if (text.isBlank()) return null
    if (raw) return text
    val parsed = runCatching { CliJson.parseToJsonElement(text) }.getOrNull()
        ?: return text
    val encoder = if (compact) compactJson else prettyJson
    return encoder.encodeToString(JsonElement.serializer(), parsed)
}

private fun io.ktor.client.request.HttpRequestBuilder.jsonHeaders(headers: List<CliHeaderParam>) {
    header(HttpHeaders.Accept, ContentType.Application.Json)
    headers {
        headers.forEach { param -> append(param.name, param.value) }
    }
}

private val prettyJson = Json {
    prettyPrint = true
    explicitNulls = false
    encodeDefaults = true
}

private val compactJson = Json {
    explicitNulls = false
    encodeDefaults = true
}
