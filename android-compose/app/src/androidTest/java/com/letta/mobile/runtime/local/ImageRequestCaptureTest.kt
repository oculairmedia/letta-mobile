package com.letta.mobile.runtime.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.BuildConfig
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.runtime.*
import com.letta.mobile.runtime.hardware.AndroidDeviceHardwareControlProvider
import com.letta.mobile.runtime.sensors.AndroidDeviceSensorSnapshotProvider
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * letta-mobile-8ll0c-v2: TRUE end-to-end image-pipeline test.
 *
 * Captures the REAL HTTP request body that letta.js sends to the provider,
 * not just the engine-level image bytes. This proves the actual image_url
 * field in the chat/completions request is valid (data:image/<type>;base64,<nonempty>),
 * catching the xybm2-class bug (data:undefined) at the HTTP wire level.
 *
 * Defense in depth over:
 * - #486 JVM floor (re-implements builder in Kotlin)
 * - tier12 test (captures at engine level, after letta.js processing)
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ImageRequestCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bridgesToStop = mutableListOf<LettaCodeNodeBridge>()
    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun stopBridges() = runBlocking {
        bridgesToStop.forEach { bridge -> bridge.stop() }
        bridgesToStop.clear()
    }

    /**
     * Tier 13: Capture the REAL HTTP chat/completions request body that
     * letta.js sends, validate image_url fields at the wire level.
     */
    @Test
    fun tier13CapturedHttpRequestHasValidImageUrl() = runBlocking {
        assumeTrue("Tier 13 requires embedded native", BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED)
        assumeTrue("Tier 13 requires embedded assets", BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED)

        val capturedRequestBodies = CopyOnWriteArrayList<String>()
        val mockProvider = CapturingHttpProvider(capturedRequestBodies)
        val baseUrl = mockProvider.start()

        try {
            val nodeBridge = NativeLettaCodeNodeBridge().also(bridgesToStop::add)
            val controller = AndroidLettaCodeRuntimeController(
                context = context,
                assetExtractor = EmbeddedLettaCodeAssetExtractor(context),
                nodeBridge = nodeBridge,
                runtimeStatusProvider = BuildConfigEmbeddedLettaCodeRuntimeStatusProvider(),
                localBackendStore = LettaCodeLocalBackendStore(context),
                androidNetworkBridge = LocalAndroidNetworkBridge(
                    sensorSnapshotProvider = AndroidDeviceSensorSnapshotProvider(context),
                    hardwareControlProvider = AndroidDeviceHardwareControlProvider(context),
                ),
                onDeviceOpenAiBridge = object : OnDeviceOpenAiBridge {
                    override fun start(modelSelection: EmbeddedLettaCodeModelSelection): OnDeviceOpenAiBridgeSession =
                        error("on-device bridge must not start when a custom provider is configured")
                },
            )
            val backend = LocalLettaBackend(
                descriptor = BackendDescriptor(
                    backendId = BackendId("test-backend"),
                    runtimeId = RuntimeId("test-runtime"),
                    kind = BackendKind.LocalLettaCode,
                    label = "Image capture test",
                    capabilities = BackendCapabilities(
                        supportsStreaming = true,
                        supportsMemFs = true,
                        supportsTools = true,
                        supportsApprovals = false,
                        supportsAgentFileImport = false,
                        supportsAgentFileExport = false,
                    ),
                ),
                engine = LettaCodeTurnEngine(
                    client = AndroidLettaCodeHeadlessClient(controller, LettaCodeStreamJsonMapper(), LettaCodeLocalBackendStore(context)),
                    config = LettaConfig(
                        id = "test-backend",
                        mode = LettaConfig.Mode.LOCAL,
                        serverUrl = "local-lettacode://device",
                        localProviderBaseUrl = baseUrl,
                        localProviderModel = "test-vision-model",
                    ),
                ),
                outbox = InMemoryRuntimeEventOutbox(
                    eventIdFactory = { _, offset -> RuntimeEventId("test-${offset.value}") },
                    clock = { EpochMillis(System.currentTimeMillis()) },
                ),
                memFsStore = NoopMemFsStore,
            )

            // A tiny valid 1x1 PNG (base64)
            val onePxPng =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

            val event = withTimeoutOrNull(LOCAL_TURN_TIMEOUT_MS) {
                backend.runTurn(
                    TurnCommand(
                        backendId = BackendId("test-backend"),
                        runtimeId = RuntimeId("test-runtime"),
                        agentId = AgentId("test-agent-${UUID.randomUUID()}"),
                        conversationId = ConversationId("test-conversation-${UUID.randomUUID()}"),
                        input = TurnInput.UserMessage(
                            localMessageId = "test-message-${UUID.randomUUID()}",
                            text = "What is in this image?",
                            imageParts = listOf(
                                TurnImagePart(base64 = onePxPng, mediaType = "image/png"),
                            ),
                        ),
                    )
                ).firstOrNull { envelope ->
                    val payload = envelope.payload
                    payload is RuntimeEventPayload.RemoteStreamFrame && payload.body.isNotBlank()
                }
            }

            assertTrue("expected a provider reply", event != null)
            assertTrue(
                "expected at least one captured HTTP request",
                capturedRequestBodies.isNotEmpty(),
            )

            // Parse the captured request and validate image_url
            val requestBody = capturedRequestBodies.first()
            val request = json.parseToJsonElement(requestBody).jsonObject

            val messages = request["messages"] as? JsonArray
            assertNotNull("request must have messages array", messages)
            requireNotNull(messages)

            val imageUrls = mutableListOf<String>()
            for (msg in messages) {
                val content = (msg as? JsonObject)?.get("content") as? JsonArray ?: continue
                for (part in content) {
                    val partObj = part as? JsonObject ?: continue
                    val type = partObj["type"]?.jsonPrimitive?.content
                    if (type == "image_url" || type == "input_image") {
                        val imageUrl = partObj["image_url"]?.jsonPrimitive?.content
                            ?: (partObj["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.content
                        if (imageUrl != null) {
                            imageUrls.add(imageUrl)
                        }
                    }
                }
            }

            assertTrue(
                "request must contain at least one image_url",
                imageUrls.isNotEmpty(),
            )

            val imageUrl = imageUrls.first()
            val validPattern = Regex("^data:image/[^;]+;base64,.+\$")
            assertTrue(
                "image_url must match data:image/<type>;base64,<nonempty> (got: ${imageUrl.take(100)}...)",
                validPattern.matches(imageUrl),
            )

            assertFalse(
                "image_url must NOT contain 'undefined' (xybm2 bug)",
                imageUrl.contains("undefined"),
            )

            println("✓ Captured HTTP request has valid image_url: ${imageUrl.take(60)}...")
        } finally {
            mockProvider.close()
        }
    }

    private object NoopMemFsStore : MemFsStore {
        override suspend fun read(path: MemFsPath): MemFsFile? = null
        override suspend fun write(command: MemFsWriteCommand): MemFsCommit =
            error("MemFS writes are not used by this test.")
        override suspend fun delete(command: MemFsDeleteCommand): MemFsCommit =
            error("MemFS deletes are not used by this test.")
        override fun commits(afterRevision: MemFsRevision) = emptyFlow<MemFsCommit>()
    }

    private companion object {
        private const val LOCAL_TURN_TIMEOUT_MS = 180_000L
    }
}

/**
 * Minimal HTTP server that captures raw request bodies and returns a mock
 * OpenAI chat/completions response.
 */
private class CapturingHttpProvider(
    private val capturedBodies: MutableList<String>,
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    fun start(): String {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        executor.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                executor.execute { handleRequest(client) }
            }
        }
        return "http://127.0.0.1:${socket.localPort}/v1"
    }

    fun close() {
        serverSocket?.close()
        executor.shutdownNow()
    }

    private fun handleRequest(client: Socket) {
        client.use { socket ->
            val input = socket.getInputStream().bufferedReader()
            val output = socket.getOutputStream()

            // Read request line
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1]

            // Read headers
            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // Read body
            if (method == "POST" && path.startsWith("/v1/chat/completions")) {
                val bodyChars = CharArray(contentLength)
                input.read(bodyChars, 0, contentLength)
                val body = String(bodyChars)
                capturedBodies.add(body)

                // Return mock response
                val response = """
                    {
                      "id": "chatcmpl-test",
                      "object": "chat.completion",
                      "created": ${System.currentTimeMillis() / 1000},
                      "model": "test-vision-model",
                      "choices": [{
                        "index": 0,
                        "message": {
                          "role": "assistant",
                          "content": "Test response"
                        },
                        "finish_reason": "stop"
                      }],
                      "usage": { "prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20 }
                    }
                """.trimIndent()

                output.writeHttpResponse(200, "OK", response)
            } else {
                output.writeHttpResponse(404, "Not Found", """{"error":"Not Found"}""")
            }
        }
    }

    private fun OutputStream.writeHttpResponse(status: Int, statusText: String, body: String) {
        val response = buildString {
            appendLine("HTTP/1.1 $status $statusText")
            appendLine("Content-Type: application/json")
            appendLine("Content-Length: ${body.toByteArray().size}")
            appendLine("Connection: close")
            appendLine()
            append(body)
        }
        write(response.toByteArray())
        flush()
    }
}
