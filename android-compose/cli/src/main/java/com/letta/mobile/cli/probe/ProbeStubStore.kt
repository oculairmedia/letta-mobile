package com.letta.mobile.cli.probe

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * In-memory conversation/message/run store shared between the hermetic stub
 * app-server controller and its local HTTP admin API.
 */
class ProbeStubStore {
    data class StoredMessage(
        val id: String,
        val conversationId: String,
        val messageType: String,
        val content: String,
    )

    private val nextMessageSeq = AtomicLong(0)
    private val messagesByConversation = ConcurrentHashMap<String, MutableList<StoredMessage>>()
    val runStatuses = ConcurrentHashMap<String, String>()

    fun nextMessageId(): String = "stub-msg-%08d".format(nextMessageSeq.incrementAndGet())

    fun append(conversationId: String, messageType: String, content: String): StoredMessage {
        val message = StoredMessage(nextMessageId(), conversationId, messageType, content)
        messagesByConversation.computeIfAbsent(conversationId) { java.util.Collections.synchronizedList(mutableListOf()) }
            .add(message)
        return message
    }

    fun seed(conversationId: String, count: Int, payloadBytes: Int): Long {
        var totalBytes = 0L
        repeat(count) { index ->
            val body = buildString(payloadBytes) {
                append("seed-").append(index).append(':')
                while (length < payloadBytes) append('x')
            }
            append(conversationId, "assistant_message", body)
            totalBytes += body.length
        }
        return totalBytes
    }

    fun conversationIds(): List<String> = messagesByConversation.keys.sorted()

    fun listMessages(conversationId: String, limit: Int, after: String?): List<StoredMessage> {
        val all = messagesByConversation[conversationId]?.toList().orEmpty()
        val startIndex = if (after == null) 0 else all.indexOfFirst { it.id == after } + 1
        if (startIndex < 0 || startIndex > all.size) return emptyList()
        return all.drop(startIndex).take(limit)
    }

    fun messageCount(conversationId: String): Int = messagesByConversation[conversationId]?.size ?: 0
}

/**
 * Minimal local HTTP admin API for hermetic probe runs. Implements just the
 * endpoints the iroh admin_rpc proxy ([ConversationAdminHandlers]/[RunAdminHandlers])
 * and the probe's setup/verification calls need:
 *
 *   GET  /v1/conversations                       -> JSON array
 *   GET  /v1/conversations/{id}/messages         -> JSON array (limit/after paging)
 *   GET  /v1/runs/{id}                           -> {"id","status"}
 *   POST /probe/seed {conversation_id,count,payload_bytes} -> {"seeded","total_bytes"}
 *
 * Only the stub serve process and the probe's non-iroh setup path ever dial
 * this server; the `no-http` scenario asserts the probe's iroh data path never does.
 */
class ProbeStubAdminServer(private val store: ProbeStubStore, port: Int = 0) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
        executor = Executors.newFixedThreadPool(4)
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
    }

    val boundPort: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$boundPort"

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path.trimEnd('/')
        val query = parseQuery(exchange.requestURI.rawQuery)
        val segments = path.split('/').filter { it.isNotEmpty() }
        try {
            when {
                exchange.requestMethod == "GET" && segments == listOf("v1", "conversations") ->
                    respond(exchange, 200, conversationsJson())

                exchange.requestMethod == "GET" && segments.size == 4 &&
                    segments[0] == "v1" && segments[1] == "conversations" && segments[3] == "messages" ->
                    respond(exchange, 200, messagesJson(segments[2], query))

                exchange.requestMethod == "GET" && segments.size == 5 &&
                    segments[0] == "v1" && segments[1] == "conversations" && segments[3] == "messages" ->
                    respond(exchange, 200, messageJson(segments[2], segments[4]))

                exchange.requestMethod == "GET" && segments.size == 3 &&
                    segments[0] == "v1" && segments[1] == "runs" ->
                    respond(exchange, 200, runJson(segments[2]))

                exchange.requestMethod == "POST" && segments == listOf("probe", "seed") ->
                    respond(exchange, 200, seedJson(exchange))

                else -> respond(exchange, 404, """{"error":"not found: ${exchange.requestMethod} $path"}""")
            }
        } catch (error: Exception) {
            respond(exchange, 500, """{"error":"${error.message?.replace("\"", "'") ?: "stub failure"}"}""")
        }
    }

    private fun conversationsJson(): String = buildJsonArray {
        store.conversationIds().forEach { id ->
            add(buildJsonObject { put("id", id) })
        }
    }.toString()

    private fun messagesJson(conversationId: String, query: Map<String, String>): String {
        val limit = query["limit"]?.toIntOrNull() ?: 50
        val after = query["after"]
        return buildJsonArray {
            store.listMessages(conversationId, limit, after).forEach { add(it.toJson()) }
        }.toString()
    }

    private fun messageJson(conversationId: String, messageId: String): String =
        store.listMessages(conversationId, Int.MAX_VALUE, null)
            .firstOrNull { it.id == messageId }
            ?.toJson()?.toString()
            ?: """{"error":"message not found"}"""

    private fun runJson(runId: String): String = buildJsonObject {
        put("id", runId)
        put("status", store.runStatuses[runId] ?: "unknown")
    }.toString()

    private fun seedJson(exchange: HttpExchange): String {
        val body = exchange.requestBody.bufferedReader().readText().ifBlank { "{}" }
        val obj = json.parseToJsonElement(body).jsonObject
        val conversationId = obj["conversation_id"]?.jsonPrimitive?.contentOrNull
            ?: error("conversation_id required")
        val count = obj["count"]?.jsonPrimitive?.intOrNull ?: 24
        val payloadBytes = obj["payload_bytes"]?.jsonPrimitive?.intOrNull ?: 65_536
        val totalBytes = store.seed(conversationId, count, payloadBytes)
        return buildJsonObject {
            put("seeded", count)
            put("total_bytes", totalBytes)
        }.toString()
    }

    private fun ProbeStubStore.StoredMessage.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("conversation_id", conversationId)
        put("message_type", messageType)
        put("content", content)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate { pair ->
            val (key, value) = pair.split('=', limit = 2)
            java.net.URLDecoder.decode(key, Charsets.UTF_8) to java.net.URLDecoder.decode(value, Charsets.UTF_8)
        }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private inline fun HttpExchange.use(block: (HttpExchange) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
