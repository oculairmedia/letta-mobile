package com.letta.mobile.runtime.local

import com.letta.mobile.runtime.actions.DeviceActionCommandRunner
import com.letta.mobile.runtime.actions.MobileActionRegistry
import com.letta.mobile.runtime.hardware.DeviceHardwareControlProvider
import com.letta.mobile.runtime.hardware.DeviceHardwareControlTool
import com.letta.mobile.runtime.mobileactions.MobileIntentActionTool
import com.letta.mobile.runtime.sensors.DeviceSensorReadTool
import com.letta.mobile.runtime.sensors.DeviceSensorSampler
import com.letta.mobile.runtime.sensors.DeviceSensorSnapshotProvider
import com.letta.mobile.runtime.sensors.NoopDeviceSensorSampler
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
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
    val authToken: String,
    private val closeAction: () -> Unit,
) : Closeable {
    override fun close() = closeAction()
}

@Singleton
class LocalAndroidNetworkBridge @Inject constructor(
    private val sensorSnapshotProvider: DeviceSensorSnapshotProvider,
    private val sensorSampler: DeviceSensorSampler = NoopDeviceSensorSampler,
    private val mobileActionRegistry: MobileActionRegistry,
    private val mobileIntentActionTool: MobileIntentActionTool,
    private val hardwareControlProvider: DeviceHardwareControlProvider,
    private val deviceActionCommandRunner: DeviceActionCommandRunner,
) : AndroidNetworkBridge {
    private val json = Json { ignoreUnknownKeys = true }

    override fun start(): AndroidNetworkBridgeSession {
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName(LOOPBACK_HOST))
        val authToken = newBridgeToken()
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "android-network-bridge").apply { isDaemon = true }
        }
        val session = BridgeServerSession(
            serverSocket = serverSocket,
            executor = executor,
            json = json,
            authToken = authToken,
            sensorReadTool = DeviceSensorReadTool(sensorSnapshotProvider, sensorSampler),
            mobileActionRegistry = mobileActionRegistry,
            mobileIntentActionTool = mobileIntentActionTool,
            hardwareControlTool = DeviceHardwareControlTool(hardwareControlProvider),
            deviceActionCommandRunner = deviceActionCommandRunner,
        )
        executor.execute(session::acceptLoop)
        return AndroidNetworkBridgeSession(
            baseUrl = "http://$LOOPBACK_HOST:${serverSocket.localPort}",
            authToken = authToken,
            closeAction = session::close,
        )
    }

    private class BridgeServerSession(
        private val serverSocket: ServerSocket,
        private val executor: ExecutorService,
        private val json: Json,
        private val authToken: String,
        private val sensorReadTool: DeviceSensorReadTool,
        private val mobileActionRegistry: MobileActionRegistry,
        private val mobileIntentActionTool: MobileIntentActionTool,
        private val hardwareControlTool: DeviceHardwareControlTool,
        private val deviceActionCommandRunner: DeviceActionCommandRunner,
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
            if (!headers.isAuthorized(authToken)) {
                socket.outputStream.writeJsonResponse(401, errorBody("unauthorized", "Android bridge authorization token is missing or invalid."))
                return
            }
            when {
                method == "POST" && path == "/dns/lookup" -> handleDnsLookup(socket.outputStream, body)
                method == "POST" && path == "/fetch" -> handleFetch(socket.outputStream, body)
                method == "POST" && path == "/device/sensors/read" -> handleReadSensors(socket.outputStream, body)
                method == "POST" && path == "/device/actions/command" -> handleDeviceActionCommand(socket.outputStream, body)
                method == "GET" && path == "/device/mobile-actions/capabilities" -> handleMobileActionCapabilities(socket.outputStream)
                method == "POST" && path == "/device/mobile-actions/execute" -> handleMobileActionExecute(socket.outputStream, body)
                method == "POST" && path == "/device/mobile-actions/intent" -> handleMobileIntentAction(socket.outputStream, body)
                method == "POST" && path.startsWith("/device/hardware/") -> handleHardwareControl(socket.outputStream, path, body)
                else -> socket.outputStream.writeJsonResponse(404, errorBody("not_found", "Unknown route: $path"))
            }
        }

        private fun handleDeviceActionCommand(output: OutputStream, body: String) {
            val response = runCatching { json.parseToJsonElement(deviceActionCommandRunner.runJson(body)).jsonObject }
                .getOrElse { error ->
                    output.writeJsonResponse(500, errorBody("device_action_failed", error.message ?: "Device action command failed."))
                    return
                }
            output.writeJsonResponse(200, response)
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
            runCatching { fetchViaHttpUrlConnection(output, url, method, headers, bodyText) }
                .getOrElse { error ->
                    if (error is BridgeResponseStartedException) return
                    output.writeJsonResponse(502, errorBody("fetch_failed", error.message ?: "Fetch failed."))
                    return
                }
        }

        private fun fetchViaHttpUrlConnection(
            output: OutputStream,
            url: String,
            method: String,
            headers: List<Pair<String, String>>,
            bodyText: String?,
        ) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = FETCH_CONNECT_TIMEOUT_MS
                readTimeout = FETCH_READ_IDLE_TIMEOUT_MS
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
            connection.useResponse { input ->
                val responseHeaders = connection.headerFields.orEmpty()
                    .filterKeys { it != null }
                    .mapValues { (_, values) -> values.orEmpty() }
                output.writeUpstreamFetchHeaders(
                    status = connection.responseCode,
                    statusText = connection.responseMessage.orEmpty(),
                    headers = responseHeaders,
                )
                try {
                    output.streamLimited(input, MAX_RESPONSE_BYTES)
                } catch (error: Throwable) {
                    throw BridgeResponseStartedException(error)
                }
            }
        }

        private fun isBlockedHost(host: String): Boolean {
            val normalized = host.lowercase(Locale.US).trimEnd('.')
            if (java.lang.Boolean.getBoolean(ALLOW_LOOPBACK_FETCH_FOR_TESTS_PROPERTY) && (normalized == "localhost" || normalized == "127.0.0.1" || normalized == "[::1]" || normalized == "::1")) return false
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

        private fun OutputStream.writeJsonResponse(status: Int, body: JsonObject, extraHeaders: Map<String, String> = emptyMap()) {
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
                403 -> "Forbidden"
                404 -> "Not Found"
                502 -> "Bad Gateway"
                else -> "OK"
            }
            val headers = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: $contentType\r\n")
                contentLength?.let { append("Content-Length: $it\r\n") }
                extraHeaders.forEach { (key, value) -> append("$key: $value\r\n") }
                append("Connection: close\r\n")
                append("\r\n")
            }
            write(headers.toByteArray(Charsets.UTF_8))
        }

        private fun OutputStream.writeUpstreamFetchHeaders(
            status: Int,
            statusText: String,
            headers: Map<String, List<String>>,
        ) {
            val reason = statusText.ifBlank { httpReason(status) }
            val headerText = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                headers.forEach { (key, values) ->
                    val normalized = key.trim()
                    if (normalized.isBlank() || normalized.lowercase(Locale.US) in BLOCKED_RESPONSE_HEADERS) return@forEach
                    values.filter { it.isNotBlank() }.forEach { value ->
                        append(normalized).append(": ").append(value.replace("\r", "").replace("\n", "")).append("\r\n")
                    }
                }
                append("X-Android-Bridge-Upstream-Status: $status\r\n")
                append("X-Android-Bridge-Upstream-Status-Text: ${URLEncoder.encode(statusText, "UTF-8")}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            write(headerText.toByteArray(Charsets.UTF_8))
            flush()
        }

        private class BridgeResponseStartedException(cause: Throwable) : RuntimeException(cause)

        private fun httpReason(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "OK"
        }

        private fun OutputStream.streamLimited(input: InputStream, limit: Int) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (total + read > limit) {
                    val allowed = limit - total
                    if (allowed > 0) {
                        write(buffer, 0, allowed)
                        flush()
                    }
                    throw IllegalStateException("Response body exceeds ${limit} bytes.")
                }
                write(buffer, 0, read)
                flush()
                total += read
            }
        }
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        // Raised from 2MB to accommodate multimodal requests: the composer
        // allows up to 4 images at ≤2MB raw each, which become ~1.33x larger
        // as base64 inside the provider JSON. 2MB rejected image-bearing
        // provider requests at the bridge before they reached the upstream
        // (letta-mobile-nojhc). 24MB comfortably covers the worst case.
        private const val MAX_REQUEST_BODY_BYTES = 24 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024
        private const val FETCH_CONNECT_TIMEOUT_MS = 30_000
        private const val FETCH_READ_IDLE_TIMEOUT_MS = 120_000
        private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH", "DELETE")
        private val BLOCKED_REQUEST_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding")
        private val BLOCKED_RESPONSE_HEADERS = setOf("connection", "content-length", "transfer-encoding")
        private const val ALLOW_LOOPBACK_FETCH_FOR_TESTS_PROPERTY = "com.letta.mobile.androidNetworkBridge.allowLoopbackFetchForTests"

        private fun newBridgeToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

private fun Map<String, String>.isAuthorized(expectedToken: String): Boolean =
    this["authorization"]?.trim() == "Bearer $expectedToken"

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

private fun HttpURLConnection.useResponse(block: (InputStream) -> Unit) {
    try {
        val input = if (responseCode >= 400) errorStream ?: inputStream else inputStream
        input.use(block)
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
