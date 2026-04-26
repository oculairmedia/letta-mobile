package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.letta.mobile.cli.runtime.MergeTracer
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.stream.SseParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `stream` — send a message into a conversation and watch the streamed
 * response chunk by chunk.
 *
 * For each frame received:
 *   1. Print the raw SSE frame (type + len + content preview)
 *   2. Apply the same merge heuristic TimelineSyncLoop uses (see
 *      MergeTracer) and print BEFORE → AFTER state
 *   3. Flag any frame that hits a non-trivial merge branch
 *
 * After [DONE] (or the connection closes), print the final assembled text.
 *
 * This is the bug-hunt's primary instrument: if wire frames are clean and
 * the inline merge produces a clean final, then the garbling Emmanuel
 * sees on device is happening downstream of the merge (display layer,
 * fuzzyCollapse, ServerEvent race). If the wire frames or inline merge
 * already produce garbage, the bug is upstream and we have a deterministic
 * repro.
 */
class StreamCommand : CliktCommand(name = "stream") {

    private val baseUrl by option(
        "--base-url",
        envvar = "LETTA_BASE_URL",
        help = "Letta server base URL. Default: https://letta.oculair.ca"
    ).default("https://letta.oculair.ca")

    private val token by option(
        "--token",
        envvar = "LETTA_TOKEN",
        help = "Bearer token for the Letta API. Required."
    ).required()

    private val conversationId by option(
        "--conversation",
        envvar = "LETTA_CONVERSATION_ID",
        help = "Conversation ID to send into. Streaming uses POST /v1/conversations/{id}/messages — same endpoint the app uses."
    ).required()

    private val message by option(
        "--message", "-m",
        help = "User message text to send."
    ).required()

    private val raw by option(
        "--raw",
        help = "If set, print only raw SSE frames without merge-trace decoration."
    ).default("false")

    override fun run() = runBlocking {
        val client = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true
                })
            }
            // SSE streams are long-running. Default Ktor timeouts (10s
            // request, 5s socket) kill the connection mid-stream.
            install(HttpTimeout) {
                requestTimeoutMillis = 10 * 60 * 1000     // 10min total cap
                connectTimeoutMillis = 30 * 1000          // 30s to connect
                socketTimeoutMillis = 5 * 60 * 1000       // 5min idle between bytes
            }
        }

        try {
            val tracer = MergeTracer(verbose = raw != "true")
            sendAndStream(client, tracer)
            tracer.printSummary()
        } finally {
            client.close()
        }
    }

    /**
     * Mirrors `MessageApi.sendConversationMessage` — POST a message to
     * `/v1/conversations/{id}/messages` and read back the SSE stream
     * from the SAME response. The app uses exactly this endpoint to
     * send + stream in a single call, so this is the closest possible
     * reproduction of the bug Emmanuel sees.
     */
    private suspend fun sendAndStream(client: HttpClient, tracer: MergeTracer) {
        println("[CLI] POST $baseUrl/v1/conversations/$conversationId/messages (SSE)")
        println("[CLI]   message=\"${message.take(80)}${if (message.length > 80) "..." else ""}\"")
        val payload = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", message)
                })
            })
        }.toString()
        println("[CLI] -----------------------------------------------------")
        val response = client.post("$baseUrl/v1/conversations/$conversationId/messages") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (response.status.value !in 200..299) {
            val errBody = response.bodyAsText()
            println("[CLI] send/stream failed: HTTP ${response.status.value}: $errBody")
            throw IllegalStateException("send/stream failed (${response.status.value})")
        }
        SseParser.parse(response.bodyAsChannel()).collect { frame ->
            tracer.onFrame(frame)
        }
        println("[CLI] -----------------------------------------------------")
        println("[CLI] STREAM CLOSED")
    }
}
