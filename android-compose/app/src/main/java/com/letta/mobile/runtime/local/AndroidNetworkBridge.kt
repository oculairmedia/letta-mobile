package com.letta.mobile.runtime.local

import com.letta.mobile.runtime.actions.MobileActionRegistry
import com.letta.mobile.runtime.hardware.DeviceHardwareControlProvider
import com.letta.mobile.runtime.hardware.DeviceHardwareControlTool
import com.letta.mobile.runtime.mobileactions.MobileIntentActionTool
import com.letta.mobile.runtime.sensors.DeviceSensorReadTool
import com.letta.mobile.runtime.sensors.DeviceSensorSnapshotProvider
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface AndroidNetworkBridge {
    fun start(): AndroidNetworkBridgeSession
}

data class AndroidNetworkBridgeSession(
    val baseUrl: String,
    private val closeAction: () -> Unit,
) : Closeable {
    override fun close() = closeAction()
}

@Singleton
class LocalAndroidNetworkBridge @Inject constructor(
    private val sensorSnapshotProvider: DeviceSensorSnapshotProvider,
    private val mobileActionRegistry: MobileActionRegistry,
    private val mobileIntentActionTool: MobileIntentActionTool,
    private val hardwareControlProvider: DeviceHardwareControlProvider,
) : AndroidNetworkBridge {
    private val json = Json { ignoreUnknownKeys = true }

    override fun start(): AndroidNetworkBridgeSession {
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName(LOOPBACK_HOST))
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "android-network-bridge").apply { isDaemon = true }
        }
        val session = BridgeServerSession(
            serverSocket = serverSocket,
            executor = executor,
            json = json,
            sensorReadTool = DeviceSensorReadTool(sensorSnapshotProvider),
            mobileActionRegistry = mobileActionRegistry,
            mobileIntentActionTool = mobileIntentActionTool,
            hardwareControlTool = DeviceHardwareControlTool(hardwareControlProvider),
        )
        executor.execute(session::acceptLoop)
        return AndroidNetworkBridgeSession(
            baseUrl = "http://$LOOPBACK_HOST:${serverSocket.localPort}",
            closeAction = session::close,
        )
    }

    private class BridgeServerSession(
        private val serverSocket: ServerSocket,
        private val executor: ExecutorService,
        private val json: Json,
        private val sensorReadTool: DeviceSensorReadTool,
        private val mobileActionRegistry: MobileActionRegistry,
        private val mobileIntentActionTool: MobileIntentActionTool,
        private val hardwareControlTool: DeviceHardwareControlTool,
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
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength < 0 || contentLength > MAX_REQUEST_BODY_BYTES) {
                socket.outputStream.writeJsonResponse(400, errorBody("invalid_request", "Content-Length out of range."))
                return
            }
            val body = readBody(input, contentLength)
            if (body == null) {
                socket.outputStream.writeJsonResponse(400, errorBody("invalid_request", "Request body ended early."))
                return
            }
            when {
                method == "POST" && path == "/dns/lookup" -> handleDnsLookup(socket.outputStream, body)
                method == "POST" && path == "/fetch" -> handleFetch(socket.outputStream, body)
                method == "POST" && path == "/device/sensors/read" -> handleReadSensors(socket.outputStream, body)
                method == "GET" && path == "/device/mobile-actions/capabilities" -> handleMobileActionCapabilities(socket.outputStream)
                method == "POST" && path == "/device/mobile-actions/execute" -> handleMobileActionExecute(socket.outputStream, body)
                method == "POST" && path == "/device/mobile-actions/intent" -> handleMobileIntentAction(socket.outputStream, body)
                method == "POST" && path.startsWith("/device/hardware/") -> handleHardwareControl(socket.outputStream, path, body)
                else -> socket.outputStream.writeJsonResponse(404, errorBody("not_found", "Unknown route: $path"))
            }
        }

        private fun handleMobileActionCapabilities(output: OutputStream) {
            val response = runCatching { json.parseToJsonElement(mobileActionRegistry.matrixJson()).jsonObject }
                .getOrElse {
                    output.writeJsonResponse(500, errorBody("mobile_actions_failed", "Capability matrix was not valid JSON."))
                    return
                }
            output.writeJsonResponse(200, response)
        }

        private fun handleMobileActionExecute(output: OutputStream, body: String) {
            val request = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (request == null) {
                output.writeJsonResponse(400, errorBody("invalid_request", "Request body is not valid JSON."))
                return
            }
            val toolName = request.string("toolName")?.trim().orEmpty()
            if (toolName.isBlank()) {
                output.writeJsonResponse(400, errorBody("invalid_request", "toolName is required."))
                return
            }
            val input = request["input"]?.jsonObject ?: JsonObject(emptyMap())
            val actionId = request.string("actionId")?.trim()?.takeIf { it.isNotBlank() } ?: com.letta.mobile.runtime.actions.newActionId()
            val responseText = runCatching {
                json.encodeToString(com.letta.mobile.runtime.actions.MobileActionToolResponse.serializer(), mobileActionRegistry.handle(toolName, input, actionId))
            }.getOrElse { error ->
                output.writeJsonResponse(500, errorBody("mobile_actions_failed", error.message ?: "Unable to execute mobile action tool."))
                return
            }
            val response = json.parseToJsonElement(responseText).jsonObject
            output.writeJsonResponse(200, response)
        }

        private fun handleReadSensors(output: OutputStream, body: String) {
            val request = parseJsonBody(output, body) ?: return
            val responseText = runCatching { sensorReadTool.handleJson(request) }
                .getOrElse { error ->
                    output.writeJsonResponse(500, errorBody("read_sensors_failed", error.message ?: "Unable to read sensors."))
                    return
                }
            val response = runCatching { json.parseToJsonElement(responseText).jsonObject }
                .getOrElse {
                    output.writeJsonResponse(500, errorBody("read_sensors_failed", "Tool response was not valid JSON."))
                    return
                }
            output.writeJsonResponse(200, response)
        }

        private fun handleMobileIntentAction(output: OutputStream, body: String) {
            val request = parseJsonBody(output, body) ?: return
            val responseText = runCatching { mobileIntentActionTool.handleJson(request) }
                .getOrElse { error ->
                    output.writeJsonResponse(500, errorBody("mobile_action_failed", error.message ?: "Unable to handle mobile action."))
                    return
                }
            val response = runCatching { json.parseToJsonElement(responseText).jsonObject }
                .getOrElse {
                    output.writeJsonResponse(500, errorBody("mobile_action_failed", "Tool response was not valid JSON."))
                    return
                }
            output.writeJsonResponse(200, response)
        }

        private fun handleHardwareControl(output: OutputStream, path: String, body: String) {
            val request = runCatching {
                if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body).jsonObject
            }.getOrNull()
            if (request == null) {
                output.writeJsonResponse(400, errorBody("invalid_request", "Request body is not valid JSON."))
                return
            }
            val responseText = runCatching {
                when (path) {
                    "/device/hardware/capabilities" -> hardwareControlTool.capabilitiesJson()
                    "/device/hardware/set_flashlight" -> hardwareControlTool.setFlashlightJson(request)
                    "/device/hardware/vibrate" -> hardwareControlTool.vibrateJson(request)
                    "/device/hardware/audio_status" -> hardwareControlTool.audioStatusJson()
                    "/device/hardware/adjust_music_volume" -> hardwareControlTool.adjustMusicVolumeJson(request)
                    else -> null
                }
            }.getOrElse { error ->
                output.writeJsonResponse(500, errorBody("hardware_control_failed", error.message ?: "Hardware control failed."))
                return
            }
            if (responseText == null) {
                output.writeJsonResponse(404, errorBody("not_found", "Unknown route: $path"))
                return
            }
            val response = runCatching { json.parseToJsonElement(responseText).jsonObject }
                .getOrElse {
                    output.writeJsonResponse(500, errorBody("hardware_control_failed", "Tool response was not valid JSON."))
                    return
                }
            output.writeJsonResponse(200, response)
        }

        private fun parseJsonBody(output: OutputStream, body: String): JsonObject? {
            val request = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (request == null) {
                output.writeJsonResponse(400, errorBody("invalid_request", "Request body is not valid JSON."))
            }
            return request
        }

        private fun handleDnsLookup(output: OutputStream, body: String) {
            val request = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            val hostname = request?.string("hostname")?.trim().orEmpty()
            if (hostname.isBlank()) {
                output.writeJsonResponse(400, errorBody("invalid_request", "hostname is required."))
                return
            }
            val addresses = runCatching { InetAddress.getAllByName(hostname).toList() }
                .getOrElse { error ->
                    output.writeJsonResponse(502, errorBody("dns_lookup_failed", error.message ?: "DNS lookup failed."))
                    return
                }
            output.writeJsonResponse(
                200,
                buildJsonObject {
                    put("hostname", hostname)
                    put(
                        "addresses",
                        buildJsonArray {
                            addresses.forEach { address ->
                                add(
                                    buildJsonObject {
                                        put("address", address.hostAddress.orEmpty())
                                        put("family", if (address.hostAddress.orEmpty().contains(':')) 6 else 4)
                                    }
                                )
                            }
                        },
                    )
                },
            )
        }

        private fun handleFetch(output: OutputStream, body: String) {
            val request = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (request == null) {
                output.writeJsonResponse(400, errorBody("invalid_request", "Request body is not valid JSON."))
                return
            }
            val url = request.string("url")?.trim().orEmpty()
            val method = request.string("method")?.uppercase(Locale.US)?.takeIf { it.isNotBlank() } ?: "GET"
            if (url.isBlank()) {
                output.writeJsonResponse(400, errorBody("invalid_request", "url is required."))
                return
            }
            if (method !in ALLOWED_METHODS) {
                output.writeJsonResponse(400, errorBody("invalid_request", "Unsupported method: $method"))
                return
            }
            val uri = runCatching { URI(url) }.getOrNull()
            if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                output.writeJsonResponse(400, errorBody("invalid_request", "Only http(s) URLs are supported."))
                return
            }
            val host = uri.host
            if (isBlockedHost(host)) {
                output.writeJsonResponse(403, errorBody("blocked_host", "Requests to $host are not allowed."))
                return
            }
            val bodyText = request.string("body")
            val headers = request["headers"]?.jsonObject.orEmpty().mapNotNull { (key, value) ->
                val text = value.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                key to text
            }
            val response = runCatching { fetchViaHttpUrlConnection(url, method, headers, bodyText) }
                .getOrElse { error ->
                    output.writeJsonResponse(502, errorBody("fetch_failed", error.message ?: "Fetch failed."))
                    return
                }
            output.writeJsonResponse(200, response)
        }

        private fun fetchViaHttpUrlConnection(
            url: String,
            method: String,
            headers: List<Pair<String, String>>,
            bodyText: String?,
        ): JsonObject {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = FETCH_TIMEOUT_MS
                readTimeout = FETCH_TIMEOUT_MS
                instanceFollowRedirects = true
                headers.forEach { (key, value) ->
                    if (key.lowercase(Locale.US) !in BLOCKED_REQUEST_HEADERS) {
                        setRequestProperty(key, value)
                    }
                }
                if (bodyText != null && method in METHODS_WITH_BODY) {
                    doOutput = true
                    val bytes = bodyText.toByteArray(Charsets.UTF_8)
                    if (bytes.size > MAX_REQUEST_BODY_BYTES) {
                        throw IllegalArgumentException("Request body too large.")
                    }
                    outputStream.use { it.write(bytes) }
                }
            }
            return connection.useResponse { input ->
                val responseBytes = readLimitedBytes(input, MAX_RESPONSE_BYTES)
                val responseHeaders = connection.headerFields.orEmpty()
                    .filterKeys { it != null }
                    .mapValues { (_, values) -> values.orEmpty().joinToString(", ") }
                buildJsonObject {
                    put("status", connection.responseCode)
                    put("statusText", connection.responseMessage.orEmpty())
                    put(
                        "headers",
                        buildJsonObject {
                            responseHeaders.forEach { (key, value) -> put(key, value) }
                        },
                    )
                    put("bodyBase64", java.util.Base64.getEncoder().encodeToString(responseBytes))
                }
            }
        }

        private fun isBlockedHost(host: String): Boolean {
            val normalized = host.lowercase(Locale.US).trimEnd('.')
            if (normalized == "localhost") return true
            val addresses = runCatching { InetAddress.getAllByName(normalized).toList() }.getOrNull() ?: return false
            return addresses.any { address ->
                address.isAnyLocalAddress ||
                    address.isLoopbackAddress ||
                    address.isLinkLocalAddress ||
                    address.isMulticastAddress ||
                    address.hostAddress.orEmpty().startsWith("169.254.169.254")
            }
        }

        private fun errorBody(code: String, message: String): JsonObject = buildJsonObject {
            put("error", buildJsonObject {
                put("message", message)
                put("type", code)
                put("code", code)
            })
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
                403 -> "Forbidden"
                404 -> "Not Found"
                502 -> "Bad Gateway"
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

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val MAX_REQUEST_BODY_BYTES = 2 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024
        private const val FETCH_TIMEOUT_MS = 120_000
        private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH", "DELETE")
        private val BLOCKED_REQUEST_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding")
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun readBody(input: InputStream, length: Int): String? {
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

private fun InputStream.readHttpLine(): String? {
    val line = StringBuilder()
    while (true) {
        val byte = read()
        if (byte == -1) return if (line.isEmpty()) null else line.toString()
        if (byte == '\n'.code) break
        if (byte != '\r'.code) line.append(byte.toChar())
    }
    return line.toString()
}

private fun HttpURLConnection.useResponse(block: (InputStream) -> JsonObject): JsonObject {
    try {
        val input = if (responseCode >= 400) errorStream ?: inputStream else inputStream
        return input.use(block)
    } finally {
        disconnect()
    }
}

private fun readLimitedBytes(input: InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (output.size() + read > limit) {
            throw IllegalStateException("Response body exceeds ${limit} bytes.")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
