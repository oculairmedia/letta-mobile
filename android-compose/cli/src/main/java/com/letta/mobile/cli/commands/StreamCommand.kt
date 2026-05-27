package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.letta.mobile.cli.runtime.MergeTracer
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
 * Direct REST/SSE tracer retained as a low-level comparison path.
 *
 * The admin-shim commands are the canonical mobile-path harness. This command
 * is still useful when we need to inspect raw REST/SSE chunks or compare server
 * wire behavior against WebSocket delivery.
 */
internal class StreamCommand : AdminShimCommand(
    name = "stream",
    help = "Direct Letta REST/SSE path for low-level comparison.",
) {

    private val conversationId by option(
        "--conversation",
        envvar = "LETTA_CONVERSATION_ID",
        help = "Conversation ID to send into."
    )

    private val message by option(
        "--message",
        "-m",
        help = "User message text to send."
    ).required()

    private val raw by option(
        "--raw",
        help = "Print only raw SSE frames without merge-trace decoration."
    ).flag(default = false)

    override fun run() = runBlocking {
        val client = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 10 * 60 * 1000
                connectTimeoutMillis = 30 * 1000
                socketTimeoutMillis = 5 * 60 * 1000
            }
        }

        try {
            val tracer = MergeTracer(verbose = !raw)
            sendAndStream(client, tracer)
            tracer.printSummary()
        } finally {
            client.close()
        }
    }

    private suspend fun sendAndStream(client: HttpClient, tracer: MergeTracer) {
        val resolvedConversationId = requireConversationId(conversationId)
        println("[CLI] POST $baseUrl/v1/conversations/$resolvedConversationId/messages (SSE)")
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
        val response = client.post("${baseUrl.trimEnd('/')}/v1/conversations/$resolvedConversationId/messages") {
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
