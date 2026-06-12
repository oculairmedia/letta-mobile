package com.letta.mobile.runtime.local

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

data class OnDeviceOpenAiBridgeSession(
    val baseUrl: String,
    private val closeAction: () -> Unit,
) : Closeable {
    override fun close() = closeAction()
}

interface OnDeviceOpenAiBridge {
    fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession
}

@Singleton
class DisabledOnDeviceOpenAiBridge @Inject constructor() : OnDeviceOpenAiBridge {
    override fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession {
        error("Embedded LettaCode on-device provider bridge is disabled until bridge/device smoke is ready.")
    }
}

interface OnDeviceChatCompletionEngine {
    fun generate(modelSelection: EmbeddedLettaCodeModelSelection, prompt: String): Result<String>
}

@Singleton
class LocalOpenAiOnDeviceBridge @Inject constructor(
    private val engine: OnDeviceChatCompletionEngine,
) : OnDeviceOpenAiBridge {
    private val json = Json { ignoreUnknownKeys = true }

    override fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession {
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName(LOOPBACK_HOST))
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "on-device-openai-bridge").apply { isDaemon = true }
        }
        val session = BridgeServerSession(
            serverSocket = serverSocket,
            executor = executor,
            modelSelection = modelSelection,
            engine = engine,
            json = json,
        )
        executor.execute(session::acceptLoop)
        return OnDeviceOpenAiBridgeSession(
            baseUrl = "http://$LOOPBACK_HOST:${serverSocket.localPort}/v1",
            closeAction = session::close,
        )
    }

    private class BridgeServerSession(
        private val serverSocket: ServerSocket,
        private val executor: ExecutorService,
        private val modelSelection: EmbeddedLettaCodeModelSelection,
        private val engine: OnDeviceChatCompletionEngine,
        private val json: Json,
    ) {
        @Volatile private var closed = false

        fun acceptLoop() {
            while (!closed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                executor.execute { socket.use(::handleSocket) }
            }
        }

        fun close() {
            closed = true
            runCatching { serverSocket.close() }
            executor.shutdownNow()
        }

        private fun handleSocket(socket: Socket) {
            // Content-Length counts BYTES; never wrap the request stream in a
            // Reader before the body is consumed, or multibyte UTF-8 bodies
            // block forever (chars < bytes).
            val input = socket.getInputStream().buffered()
            val requestLine = input.readHttpLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                socket.outputStream.writeJsonResponse(400, errorBody("invalid_request", "Malformed request line."))
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val path = parts[1].substringBefore("?")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readHttpLine() ?: return
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                        line.substring(separator + 1).trim()
                }
            }
            if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
                socket.outputStream.apply {
                    write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.UTF_8))
                    flush()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                socket.outputStream.writeJsonResponse(
                    400,
                    errorBody("invalid_request", "Content-Length out of range: $contentLength"),
                )
                return
            }
            val body = readBody(input, contentLength)
            if (body == null) {
                socket.outputStream.writeJsonResponse(
                    400,
                    errorBody("invalid_request", "Request body ended before Content-Length bytes."),
                )
                return
            }
            android.util.Log.d("OnDeviceOpenAiBridge", "request: $method $path bodyBytes=$contentLength")
            when {
                method == "GET" && path == "/v1/models" -> socket.outputStream.writeJsonResponse(200, modelsBody())
                method == "POST" && path == "/v1/chat/completions" -> handleChatCompletion(socket.outputStream, body)
                else -> socket.outputStream.writeJsonResponse(404, errorBody("not_found", "Unknown route: $path"))
            }
        }

        private fun handleChatCompletion(output: OutputStream, body: String) {
            val request = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (request == null) {
                output.writeJsonResponse(400, errorBody("invalid_request", "Request body is not valid JSON."))
                return
            }
            val prompt = request["messages"]?.let(::messagesToPrompt).orEmpty()
            val stream = request["stream"]?.jsonPrimitive?.booleanOrNull == true
            val result = engine.generate(modelSelection, prompt)
            result.fold(
                onSuccess = { text ->
                    if (stream) {
                        output.writeStreamingCompletion(text)
                    } else {
                        output.writeJsonResponse(200, completionBody(text))
                    }
                },
                onFailure = { error ->
                    output.writeJsonResponse(
                        503,
                        errorBody("on_device_runtime_unavailable", error.message ?: "On-device runtime unavailable."),
                    )
                },
            )
        }

        private fun modelsBody(): JsonObject = buildJsonObject {
            put("object", "list")
            put(
                "data",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", modelSelection.openAiModelId)
                            put("object", "model")
                            put("owned_by", "letta-mobile")
                        }
                    )
                },
            )
        }

        private fun completionBody(text: String): JsonObject = buildJsonObject {
            put("id", "chatcmpl-${UUID.randomUUID()}")
            put("object", "chat.completion")
            put("created", Instant.now().epochSecond)
            put("model", modelSelection.openAiModelId)
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            put(
                                "message",
                                buildJsonObject {
                                    put("role", "assistant")
                                    put("content", text)
                                },
                            )
                            put("finish_reason", "stop")
                        }
                    )
                },
            )
        }

        private fun OutputStream.writeStreamingCompletion(text: String) {
            val id = "chatcmpl-${UUID.randomUUID()}"
            val created = Instant.now().epochSecond
            writeHeaders(200, "text/event-stream; charset=utf-8")
            writeSseChunk(id, created, text, finishReason = null)
            writeSseChunk(id, created, "", finishReason = "stop")
            write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
            flush()
        }

        private fun OutputStream.writeSseChunk(
            id: String,
            created: Long,
            text: String,
            finishReason: String?,
        ) {
            val body = buildJsonObject {
                put("id", id)
                put("object", "chat.completion.chunk")
                put("created", created)
                put("model", modelSelection.openAiModelId)
                put(
                    "choices",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("index", 0)
                                put(
                                    "delta",
                                    buildJsonObject {
                                        if (finishReason == null) {
                                            put("role", "assistant")
                                            put("content", text)
                                        }
                                    },
                                )
                                if (finishReason == null) {
                                    put("finish_reason", null as String?)
                                } else {
                                    put("finish_reason", finishReason)
                                }
                            }
                        )
                    },
                )
            }
            write("data: ${json.encodeToString(JsonObject.serializer(), body)}\n\n".toByteArray(Charsets.UTF_8))
        }

        private fun messagesToPrompt(element: JsonElement): String =
            (element as? JsonArray)
                ?.joinToString("\n") { message ->
                    val item = message.jsonObject
                    val role = item["role"]?.jsonPrimitive?.contentOrNull ?: "user"
                    val content = item["content"]?.let(::contentToText).orEmpty()
                    "$role: $content"
                }
                .orEmpty()

        private fun contentToText(element: JsonElement): String = when (element) {
            is JsonArray -> element.joinToString("\n") { part ->
                val partObject = part as? JsonObject
                partObject?.get("text")?.jsonPrimitive?.contentOrNull ?: part.toString()
            }
            else -> element.jsonPrimitive.contentOrNull ?: element.toString()
        }

        private fun errorBody(code: String, message: String): JsonObject = buildJsonObject {
            put(
                "error",
                buildJsonObject {
                    put("message", message)
                    put("type", code)
                    put("code", code)
                },
            )
        }

        private fun OutputStream.writeJsonResponse(status: Int, body: JsonObject) {
            val bytes = json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)
            writeHeaders(status, "application/json; charset=utf-8", bytes.size)
            write(bytes)
            flush()
        }

        private fun OutputStream.writeHeaders(status: Int, contentType: String, contentLength: Int? = null) {
            val reason = when (status) {
                200 -> "OK"
                400 -> "Bad Request"
                404 -> "Not Found"
                503 -> "Service Unavailable"
                else -> "OK"
            }
            val headers = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: $contentType\r\n")
                contentLength?.let { append("Content-Length: $it\r\n") }
                append("Connection: close\r\n")
                append("\r\n")
            }
            write(headers.toByteArray(Charsets.UTF_8))
        }
    }

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"

        // Generous bound for chat-completion payloads (the full local system
        // prompt + transcript is ~50 KB today); rejects pathological
        // Content-Length values before allocation.
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
    }
}

/** Reads exactly [length] bytes; null when the stream ends early (truncated request). */
private fun readBody(input: java.io.InputStream, length: Int): String? {
    if (length <= 0) return ""
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = input.read(buffer, offset, length - offset)
        if (read <= 0) return null
        offset += read
    }
    return String(buffer, Charsets.UTF_8)
}

/** Reads a CRLF- (or LF-) terminated line as ISO-8859-1 bytes; null on EOF. */
private fun java.io.InputStream.readHttpLine(): String? {
    val line = StringBuilder()
    while (true) {
        val byte = read()
        if (byte == -1) return if (line.isEmpty()) null else line.toString()
        if (byte == '\n'.code) break
        if (byte != '\r'.code) line.append(byte.toChar())
    }
    return line.toString()
}
