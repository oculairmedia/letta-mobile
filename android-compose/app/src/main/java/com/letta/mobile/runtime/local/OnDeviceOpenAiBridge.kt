package com.letta.mobile.runtime.local

import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

data class OnDeviceImage(
    val bytes: ByteArray,
    val mediaType: String?,
) {
    @Suppress("NoAnyType") // equals' signature is fixed by Any; content equality for the byte array.
    override fun equals(other: Any?): Boolean =
        this === other || other is OnDeviceImage && bytes.contentEquals(other.bytes) && mediaType == other.mediaType

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mediaType.hashCode()
}

data class OnDeviceOpenAiBridgeSession(
    val baseUrl: String,
    val authToken: String,
    private val closeAction: () -> Unit,
) : Closeable {
    override fun close() = closeAction()
}

interface OnDeviceOpenAiBridge {
    fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession
}

/**
 * Runs one on-device generation. An engine streams its output into [OnDeviceGeneration.accept]
 * and stops as soon as that returns false, registers its native cancel with
 * [OnDeviceGeneration.onCancel] (the bridge cancels on client disconnect and timeout), and
 * returns [OnDeviceGeneration.text]: never a run that outlives its request.
 */
interface OnDeviceChatCompletionEngine {
    fun generate(
        modelSelection: EmbeddedLettaCodeModelSelection,
        prompt: String,
        images: List<OnDeviceImage> = emptyList(),
        generation: OnDeviceGeneration = OnDeviceGeneration(),
    ): Result<String>
}

@Singleton
class LocalOpenAiOnDeviceBridge internal constructor(
    private val engine: OnDeviceChatCompletionEngine,
    private val capabilities: OnDeviceModelCapabilities,
    private val generationTimeoutMs: Long,
) : OnDeviceOpenAiBridge {
    @Inject constructor(
        engine: OnDeviceChatCompletionEngine,
        capabilities: OnDeviceModelCapabilities,
    ) : this(engine, capabilities, OnDeviceGenerationLimits.DEFAULT_TIMEOUT_MS)

    /** No catalog (tests, the debug self-test): image support unknown, images pass through. */
    constructor(engine: OnDeviceChatCompletionEngine) : this(engine, OnDeviceModelCapabilities.Unknown)

    private val json = Json { ignoreUnknownKeys = true }
    private val secureRandom = SecureRandom()

    override fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession {
        logLiteRtInfo(
            "bridge_start",
            "handle=${modelSelection.modelHandle} routesToProvider=${modelSelection.routesToOpenAiCompatibleProvider} modelPath=${modelSelection.modelPath}",
        )
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName(LOOPBACK_HOST))
        val authToken = newSessionToken()
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "on-device-openai-bridge").apply { isDaemon = true }
        }
        val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "on-device-openai-bridge-timeout").apply { isDaemon = true }
        }
        val session = BridgeServerSession(
            serverSocket = serverSocket,
            executor = executor,
            timer = timer,
            modelSelection = modelSelection,
            engine = engine,
            capability = capabilities.forSelection(modelSelection),
            generationTimeoutMs = generationTimeoutMs,
            json = json,
            authToken = authToken,
        )
        executor.execute(session::acceptLoop)
        logLiteRtInfo("bridge_started", "handle=${modelSelection.modelHandle} port=${serverSocket.localPort}")
        return OnDeviceOpenAiBridgeSession(
            baseUrl = "http://$LOOPBACK_HOST:${serverSocket.localPort}/v1",
            authToken = authToken,
            closeAction = session::close,
        )
    }

    private fun newSessionToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private class BridgeServerSession(
        private val serverSocket: ServerSocket,
        private val executor: ExecutorService,
        private val timer: ScheduledExecutorService,
        private val modelSelection: EmbeddedLettaCodeModelSelection,
        private val engine: OnDeviceChatCompletionEngine,
        private val capability: OnDeviceModelCapability,
        private val generationTimeoutMs: Long,
        private val json: Json,
        private val authToken: String,
    ) : OpenAiCompletionWriter by OpenAiCompletions(json) {
        @Volatile private var closed = false
        private val guards = OnDeviceGenerationGuards(executor, timer)

        /** The id letta-code looks up for the agents' handle (by4vm.121.1), never the prefixed handle. */
        private val servedModelId: String = lettaCodeLookupId(modelSelection.lettaCodeModelHandle)

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
            timer.shutdownNow()
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
            if (!isAuthorized(headers["authorization"])) {
                socket.outputStream.writeJsonResponse(
                    401,
                    errorBody("unauthorized", "Missing or invalid bearer token."),
                    extraHeaders = mapOf("WWW-Authenticate" to "Bearer"),
                )
                return
            }
            val rawContentLength = headers["content-length"]
            val contentLength = rawContentLength?.toIntOrNull()
            if (rawContentLength != null && (contentLength == null || contentLength < 0)) {
                socket.outputStream.writeJsonResponse(
                    400,
                    errorBody("invalid_request", "Invalid Content-Length header."),
                )
                return
            }
            val resolvedContentLength = contentLength ?: 0
            if (resolvedContentLength > MAX_BODY_BYTES) {
                socket.outputStream.writeJsonResponse(
                    400,
                    errorBody("invalid_request", "Content-Length out of range: $resolvedContentLength"),
                )
                return
            }
            val body = readBody(input, resolvedContentLength)
            if (body == null) {
                socket.outputStream.writeJsonResponse(
                    400,
                    errorBody("invalid_request", "Request body ended before Content-Length bytes."),
                )
                return
            }
            android.util.Log.d("OnDeviceOpenAiBridge", "request: $method $path bodyBytes=$resolvedContentLength")
            route(socket, "$method $path", body)
        }

        private fun route(socket: Socket, route: String, body: String) {
            when (route) {
                "GET /v1/models" -> socket.outputStream.writeJsonResponse(200, modelsBody())
                "GET $LM_STUDIO_MODELS_PATH" -> socket.outputStream.writeJsonResponse(200, lmStudioModelsBody(servedModelId, capability))
                "POST /v1/chat/completions" -> handleChatCompletion(socket, body)
                else -> socket.outputStream.writeJsonResponse(404, errorBody("not_found", "Unknown route: ${route.substringAfter(' ')}"))
            }
        }

        private fun isAuthorized(authorization: String?): Boolean {
            val value = authorization?.trim() ?: return false
            if (!value.regionMatches(0, "Bearer ", 0, "Bearer ".length, ignoreCase = true)) return false
            val supplied = value.substring("Bearer ".length).trim()
            return MessageDigest.isEqual(
                supplied.toByteArray(Charsets.UTF_8),
                authToken.toByteArray(Charsets.UTF_8),
            )
        }

        private fun handleChatCompletion(socket: Socket, body: String) {
            val output = socket.outputStream
            val request = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (request == null) {
                output.writeJsonResponse(400, errorBody("invalid_request", "Request body is not valid JSON."))
                return
            }
            val requestedModelId = request["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val responseModelId = requestedModelId ?: servedModelId
            android.util.Log.i(
                LITERT_BRIDGE_TAG,
                "incoming model=${requestedModelId ?: "<missing>"} served=$servedModelId comparison=not_enforced response=$responseModelId",
            )
            val filteredRequest = applyOnDeviceToolPolicy(request)
            val prompt = OnDeviceToolCallProtocol.renderPrompt(filteredRequest)
            val images = imagesForEngine(output, OnDeviceToolCallProtocol.extractImages(filteredRequest)) ?: return
            val stream = request["stream"]?.jsonPrimitive?.booleanOrNull == true
            val generation = OnDeviceGeneration(OnDeviceGenerationLimits.forRequest(request, modelSelection, generationTimeoutMs))
            logLiteRtInfo(
                "bridge_inference_start",
                "handle=${modelSelection.modelHandle} promptChars=${prompt.length} images=${images.size} stream=$stream " +
                    "maxOutputTokens=${generation.limits.maxOutputTokens} timeoutMs=${generation.limits.timeoutMs}",
            )
            val result = guards.guard(socket, generation) {
                engine.generate(modelSelection, prompt, images, generation)
            }
            if (answeredStoppedGeneration(output, generation)) return
            writeResult(output, result, stream, ResponseModelId(responseModelId))
        }

        private fun writeResult(output: OutputStream, result: Result<String>, stream: Boolean, responseModelId: ResponseModelId) {
            result.fold(
                onSuccess = { raw ->
                    logLiteRtInfo("bridge_inference_complete", "handle=${modelSelection.modelHandle} rawChars=${raw.length}")
                    if (raw.isDegenerateLiteRtOutput()) {
                        logLiteRtInfo("bridge_repetition_guard", "handle=${modelSelection.modelHandle} rawChars=${raw.length} preview=${raw.take(80).lineSafe()}")
                        output.writeJsonResponse(
                            NON_RETRYABLE_STATUS,
                            errorBody("on_device_degenerate_output", "On-device model produced degenerate repeated output; try a shorter prompt or reset the conversation."),
                        )
                        return@fold
                    }
                    when (val turn = OnDeviceToolCallProtocol.parseModelOutput(raw)) {
                        is OnDeviceToolCallProtocol.ModelTurn.Text ->
                            if (stream) {
                                output.writeStreamingCompletion(turn.text, responseModelId)
                            } else {
                                output.writeJsonResponse(200, completionBody(turn.text, responseModelId))
                            }
                        is OnDeviceToolCallProtocol.ModelTurn.ToolCall ->
                            if (stream) {
                                output.writeStreamingToolCall(turn, responseModelId)
                            } else {
                                output.writeJsonResponse(200, toolCallCompletionBody(turn, responseModelId))
                            }
                    }
                },
                onFailure = { error ->
                    logLiteRtError("bridge_inference_error", "handle=${modelSelection.modelHandle}", error)
                    output.writeJsonResponse(
                        503,
                        errorBody("on_device_runtime_unavailable", error.fullLiteRtErrorText()),
                    )
                },
            )
        }


        /**
         * The images to hand the engine, or null after answering: a catalog model without vision
         * gets a clear error instead of a reply that silently ignores the picture.
         */
        private fun imagesForEngine(output: OutputStream, all: List<OnDeviceImage>): List<OnDeviceImage>? {
            if (all.isNotEmpty() && capability.supportsImages == false) {
                logLiteRtInfo("bridge_images_rejected", "handle=${modelSelection.modelHandle} images=${all.size}")
                output.writeJsonResponse(
                    NON_RETRYABLE_STATUS,
                    errorBody(
                        "on_device_images_unsupported",
                        "The on-device model $servedModelId does not accept images. Pick a vision model in Settings or send text.",
                    ),
                )
                return null
            }
            val kept = all.takeLast(MAX_IMAGES_PER_REQUEST)
            if (kept.size < all.size) {
                logLiteRtInfo(
                    "bridge_images_trimmed",
                    "handle=${modelSelection.modelHandle} kept=${kept.size} dropped=${all.size - kept.size}",
                )
            }
            return kept
        }

        /**
         * True when a stop left nothing to reply with: the client is gone (nobody reads a reply,
         * and the engine is already free), or the timeout fired before any output.
         */
        private fun answeredStoppedGeneration(output: OutputStream, generation: OnDeviceGeneration): Boolean {
            val reason = generation.stopReason ?: return false
            logLiteRtInfo(
                "bridge_generation_stopped",
                "handle=${modelSelection.modelHandle} reason=${reason.wire} outputChars=${generation.text.length}",
            )
            return when {
                reason == OnDeviceStopReason.ClientDisconnected -> true
                reason == OnDeviceStopReason.Timeout && generation.text.isBlank() -> {
                    output.writeJsonResponse(
                        NON_RETRYABLE_STATUS,
                        errorBody("on_device_timeout", "On-device model did not answer within ${generation.limits.timeoutMs / 1_000} s."),
                    )
                    true
                }
                else -> false
            }
        }

        private fun applyOnDeviceToolPolicy(request: JsonObject): JsonObject {
            if (!modelSelection.isLiteRtLmRuntime()) return request
            val tools = request["tools"] as? JsonArray
            if (tools.isNullOrEmpty()) return request
            logLiteRtInfo(
                "bridge_tool_policy",
                "handle=${modelSelection.modelHandle} runtime=${modelSelection.runtime} action=drop_tools count=${tools.size}",
            )
            return JsonObject(request.filterKeys { key -> key != "tools" && key != "tool_choice" })
        }

        private fun modelsBody(): JsonObject = buildJsonObject {
            put("object", "list")
            put(
                "data",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", servedModelId)
                            put("object", "model")
                            put("owned_by", "letta-mobile")
                        }
                    )
                },
            )
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

        private fun OutputStream.writeJsonResponse(
            status: Int,
            body: JsonObject,
            extraHeaders: Map<String, String> = emptyMap(),
        ) {
            val bytes = json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8)
            writeHeaders(status, "application/json; charset=utf-8", bytes.size, extraHeaders)
            write(bytes)
            flush()
        }

        private fun OutputStream.writeHeaders(
            status: Int,
            contentType: String,
            contentLength: Int? = null,
            extraHeaders: Map<String, String> = emptyMap(),
        ) {
            val reason = when (status) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                NON_RETRYABLE_STATUS -> "Unprocessable Content"
                503 -> "Service Unavailable"
                else -> "OK"
            }
            val headers = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: $contentType\r\n")
                contentLength?.let { append("Content-Length: $it\r\n") }
                extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
                append("Connection: close\r\n")
                append("\r\n")
            }
            write(headers.toByteArray(Charsets.UTF_8))
        }
    }

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val LITERT_BRIDGE_TAG = "LITERT_BRIDGE"


        /**
         * The most recent images only: each costs the small engine context (gemma-3n: ~256 tokens
         * of a 4096-token window), and older ones belong to turns the prompt already trimmed.
         */
        private const val MAX_IMAGES_PER_REQUEST = 2

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

private fun EmbeddedLettaCodeModelSelection.isLiteRtLmRuntime(): Boolean =
    runtime.equals("litert-lm", ignoreCase = true) ||
        modelHandle.contains("litert", ignoreCase = true)


private fun String.isDegenerateLiteRtOutput(): Boolean {
    val compact = filterNot { it.isWhitespace() }
    if (compact.length < 120) return false
    val mostCommon = compact.groupingBy { it }.eachCount().maxOfOrNull { it.value } ?: 0
    if (mostCommon.toDouble() / compact.length >= 0.85) return true
    val lower = lowercase()
    val tokens = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.size < 40) return false
    val topTokenCount = tokens.groupingBy { it }.eachCount().maxOfOrNull { it.value } ?: 0
    return topTokenCount.toDouble() / tokens.size >= 0.75
}

private fun String.lineSafe(): String = replace('\n', ' ').replace('\r', ' ')
