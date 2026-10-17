package com.letta.mobile.runtime.local

import android.content.Context
import io.mockk.mockk
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OnDeviceOpenAiBridgeTest {
    @Test
    fun `models endpoint serves the id letta code looks up for the agent handle`() {
        listOf("google/gemma-3n-E2B-it-litert-lm", "local/default").forEach { modelHandle ->
            val selection = selection().copy(modelHandle = modelHandle)
            LocalOpenAiOnDeviceBridge(FakeEngine()).start(selection).use { session ->
                val response = get("${session.baseUrl}/models", session.authToken)

                assertEquals(200, response.code)
                assertEquals(listOf(lettaCodeLookupId(selection.lettaCodeModelHandle)), response.modelIds())
                assertEquals(listOf(selection.openAiModelId), response.modelIds())
                assertTrue(response.body.contains("letta-mobile"))
            }
        }
    }

    @Test
    fun `models endpoint rejects missing bearer token`() {
        val bridge = LocalOpenAiOnDeviceBridge(FakeEngine())
        bridge.start(selection()).use { session ->
            val response = get("${session.baseUrl}/models")

            assertEquals(401, response.code)
            assertTrue(response.body.contains("unauthorized"))
        }
    }

    @Test
    fun `models endpoint rejects wrong bearer token`() {
        val bridge = LocalOpenAiOnDeviceBridge(FakeEngine())
        bridge.start(selection()).use { session ->
            val response = get("${session.baseUrl}/models", "wrong-token")

            assertEquals(401, response.code)
            assertTrue(response.body.contains("unauthorized"))
        }
    }

    @Test
    fun `bridge sessions receive distinct bearer tokens`() {
        val bridge = LocalOpenAiOnDeviceBridge(FakeEngine())
        bridge.start(selection()).use { first ->
            bridge.start(selection()).use { second ->
                assertNotEquals(first.authToken, second.authToken)
            }
        }
    }

    @Test
    fun `chat completions endpoint delegates prompt to engine`() {
        val engine = FakeEngine(response = "Hello from device")
        val bridge = LocalOpenAiOnDeviceBridge(engine)
        bridge.start(selection()).use { session ->
            val response = post(
                url = "${session.baseUrl}/chat/completions",
                body = """
                    {
                      "model": "gemma-3n",
                      "messages": [
                        { "role": "system", "content": "Be direct." },
                        { "role": "user", "content": "Say hello" }
                      ]
                    }
                """.trimIndent(),
                bearerToken = session.authToken,
            )

            assertEquals(200, response.code)
            assertTrue(response.body.contains("Hello from device"))
            assertEquals("Be direct.", engine.lastRequest?.systemInstruction)
            assertEquals(OnDeviceMessage.user("Say hello"), engine.lastRequest?.latest)
        }
    }


    @Test
    fun `chat completions endpoint passes image content parts to engine`() {
        val engine = FakeEngine(response = "saw image")
        val bridge = LocalOpenAiOnDeviceBridge(engine)
        bridge.start(selection()).use { session ->
            val response = post(
                url = "${session.baseUrl}/chat/completions",
                body = """
                    {
                      "messages": [{"role": "user", "content": [
                        {"type": "image_url", "image_url": {"url": "data:image/png;base64,AQIDBA=="}},
                        {"type": "text", "text": "Describe this"}
                      ]}]
                    }
                """.trimIndent(),
                bearerToken = session.authToken,
            )

            assertEquals(200, response.code)
            assertEquals("Describe this", engine.lastRequest?.latest?.text)
            val image = engine.lastRequest?.images.orEmpty().single()
            assertEquals("image/png", image.mediaType)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), image.bytes)
        }
    }


    @Test
    fun `chat completions endpoint accepts bare model and echoes it`() {
        assertAcceptedAndEchoed("google/gemma-3n-E2B-it-litert-lm")
    }

    @Test
    fun `chat completions endpoint accepts prefixed model and echoes it`() {
        assertAcceptedAndEchoed("lmstudio/google/gemma-3n-E2B-it-litert-lm")
    }

    @Test
    fun `chat completions endpoint accepts mismatched model and echoes it`() {
        assertAcceptedAndEchoed("totally/different-model")
    }

    @Test
    fun `chat completions endpoint reports engine failure as unavailable`() {
        val bridge = LocalOpenAiOnDeviceBridge(
            FakeEngine(failure = IllegalStateException("runtime missing")),
        )
        bridge.start(selection()).use { session ->
            val response = post(
                url = "${session.baseUrl}/chat/completions",
                body = """{"messages":[{"role":"user","content":"Hello"}]}""",
                bearerToken = session.authToken,
            )

            assertEquals(503, response.code)
            assertTrue(response.body.contains("on_device_runtime_unavailable"))
            assertTrue(response.body.contains("runtime missing"))
        }
    }

    @Test
    fun `tools reach the engine verbatim`() {
        val engine = FakeEngine(response = "plain response")
        LocalOpenAiOnDeviceBridge(engine).start(selection()).use { session ->
            val response = post(
                url = "${session.baseUrl}/chat/completions",
                body = """{"messages": [{"role": "user", "content": "say hi"}], "tools": [$READ_TOOL], "tool_choice": "auto"}""",
                bearerToken = session.authToken,
            )

            assertEquals(200, response.code)
            assertTrue(response.body.contains("plain response"))
            val tool = engine.lastRequest?.tools.orEmpty().single()
            assertEquals("Read", tool.name)
            assertEquals(Json.parseToJsonElement(READ_TOOL).jsonObject["function"], Json.parseToJsonElement(tool.functionJson))
        }
    }

    @Test
    fun `a tool call round trips through the bridge to a final answer`() {
        val engine = FakeEngine(
            replies = ArrayDeque(
                listOf(
                    OnDeviceReply("", listOf(OnDeviceToolCall(null, "Read", """{"file_path":"/notes.txt"}"""))),
                    OnDeviceReply("The note says: buy milk."),
                ),
            ),
        )
        LocalOpenAiOnDeviceBridge(engine).start(selection()).use { session ->
            // 1. The model asks for a tool: an OpenAI tool_calls reply with a fresh id.
            val first = post(
                url = "${session.baseUrl}/chat/completions",
                body = """{"stream": true, "messages": [{"role": "user", "content": "read my note"}], "tools": [$READ_TOOL]}""",
                bearerToken = session.authToken,
            )
            assertEquals(200, first.code)
            assertTrue(first.body.contains("\"finish_reason\":\"tool_calls\""))
            val call = first.sseToolCalls().single()
            val callId = call.getValue("id").jsonPrimitive.content
            assertTrue(callId.startsWith("call_"))
            assertEquals("Read", call.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals("""{"file_path":"/notes.txt"}""", call.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content)

            // 2. letta-code ran it and sends the call and its result back: the engine gets both.
            val second = post(
                url = "${session.baseUrl}/chat/completions",
                body = """
                    {"stream": true, "tools": [$READ_TOOL], "messages": [
                      {"role": "user", "content": "read my note"},
                      {"role": "assistant", "content": null, "tool_calls": [{"id": "$callId", "type": "function",
                        "function": {"name": "Read", "arguments": "{\"file_path\":\"/notes.txt\"}"}}]},
                      {"role": "tool", "tool_call_id": "$callId", "content": "buy milk"}
                    ]}
                """.trimIndent(),
                bearerToken = session.authToken,
            )
            assertEquals(200, second.code)
            assertTrue(second.body.contains("The note says: buy milk."))
            assertTrue(second.body.contains("\"finish_reason\":\"stop\""))
            val sent = engine.requests.last()
            assertEquals(OnDeviceToolCall(callId, "Read", """{"file_path":"/notes.txt"}"""), sent.history.last().toolCalls.single())
            assertEquals(listOf(OnDeviceToolResult("Read", "buy milk")), sent.latest.toolResults)
        }
    }

    @Test
    fun `a context overflow is a non retryable error that names it`() {
        val overflow = OnDeviceRequestRejected("on_device_context_overflow", "needs 6120 tokens, the window is 4096")
        LocalOpenAiOnDeviceBridge(FakeEngine(failure = overflow)).start(selection()).use { session ->
            val response = post(
                url = "${session.baseUrl}/chat/completions",
                body = """{"messages":[{"role":"user","content":"Hello"}]}""",
                bearerToken = session.authToken,
            )

            assertEquals(422, response.code)
            assertTrue(response.body.contains("on_device_context_overflow"))
            assertTrue(response.body.contains("6120"))
        }
    }

    @Test
    fun `a malformed conversation is a bad request and never reaches the engine`() {
        val engine = FakeEngine()
        LocalOpenAiOnDeviceBridge(engine).start(selection()).use { session ->
            val response = post(
                url = "${session.baseUrl}/chat/completions",
                body = """{"messages":[{"role":"tool","tool_call_id":"nope","content":"orphan"}]}""",
                bearerToken = session.authToken,
            )

            assertEquals(400, response.code)
            assertTrue(response.body.contains("invalid_request"))
            assertTrue(engine.requests.isEmpty())
        }
    }

    // PR #536: reject malformed/negative Content-Length; accept explicit valid one.
    @Test
    fun `chat completions rejects malformed Content-Length header`() {
        val bridge = LocalOpenAiOnDeviceBridge(FakeEngine())
        bridge.start(selection()).use { session ->
            val response = postRawRequest(
                url = "${session.baseUrl}/chat/completions",
                body = """{"messages":[{"role":"user","content":"Hello"}]}""",
                bearerToken = session.authToken,
                contentLength = "abc",
            )

            assertEquals(400, response.code)
            assertTrue(response.body.contains("Invalid Content-Length header."))
        }
    }

    @Test
    fun `chat completions rejects negative Content-Length header`() {
        val bridge = LocalOpenAiOnDeviceBridge(FakeEngine())
        bridge.start(selection()).use { session ->
            val response = postRawRequest(
                url = "${session.baseUrl}/chat/completions",
                body = """{"messages":[{"role":"user","content":"Hello"}]}""",
                bearerToken = session.authToken,
                contentLength = "-5",
            )

            assertEquals(400, response.code)
            assertTrue(response.body.contains("Invalid Content-Length header."))
        }
    }

    @Test
    fun `chat completions accepts explicit Content-Length header`() {
        val bridge = LocalOpenAiOnDeviceBridge(FakeEngine(response = "Hello from device"))
        val body = """{"messages":[{"role":"user","content":"Hello from header"}]}"""
        val contentLength = body.toByteArray(Charsets.UTF_8).size.toString()
        bridge.start(selection()).use { session ->
            val response = postRawRequest(
                url = "${session.baseUrl}/chat/completions",
                body = body,
                bearerToken = session.authToken,
                contentLength = contentLength,
            )

            assertEquals(200, response.code)
            assertTrue(response.body.contains("Hello from device"))
        }
    }

    @Test
    fun `tool call reply becomes openai tool_calls with finish_reason tool_calls`() {
        val engine = FakeEngine(
            replies = ArrayDeque(listOf(OnDeviceReply("Checking.", listOf(OnDeviceToolCall(null, "Memory", """{"op":"view"}"""))))),
        )
        LocalOpenAiOnDeviceBridge(engine).start(selection()).use { session ->
            val response = post(
                url = "${session.baseUrl}/chat/completions",
                body = """{"messages": [{"role": "user", "content": "check memory"}], "tools": [$READ_TOOL]}""",
                bearerToken = session.authToken,
            )

            assertEquals(200, response.code)
            val message = Json.parseToJsonElement(response.body).jsonObject.getValue("choices").jsonArray[0].jsonObject
            assertEquals("tool_calls", message.getValue("finish_reason").jsonPrimitive.content)
            val body = message.getValue("message").jsonObject
            assertEquals("Checking.", body.getValue("content").jsonPrimitive.content)
            val function = body.getValue("tool_calls").jsonArray.single().jsonObject.getValue("function").jsonObject
            assertEquals("Memory", function.getValue("name").jsonPrimitive.content)
        }
    }

    @Test
    fun `streaming chat completions endpoint emits delta and done sentinel`() {
        val bridge = LocalOpenAiOnDeviceBridge(FakeEngine(response = "streamed device text"))
        bridge.start(selection()).use { session ->
            val response = post(
                url = "${session.baseUrl}/chat/completions",
                body = """
                    {
                      "stream": true,
                      "messages": [
                        { "role": "user", "content": [
                          { "type": "text", "text": "Hello" }
                        ] }
                      ]
                    }
                """.trimIndent(),
                bearerToken = session.authToken,
            )

            assertEquals(200, response.code)
            assertTrue(response.contentType.orEmpty().startsWith("text/event-stream"))
            assertTrue(response.body.contains("streamed device text"))
            assertTrue(response.body.contains("\"finish_reason\":\"stop\""))
            assertTrue(response.body.contains("data: [DONE]"))
        }
    }

    @Test
    fun `LiteRT engine rejects missing model path before native initialization`() {
        val engine = LiteRtLmOnDeviceChatCompletionEngine(mockk<Context>(relaxed = true))

        val result = engine.generate(selection(modelPath = null), OnDeviceChatRequest.userText("hello"))

        assertTrue(result.isFailure)
        assertEquals("No on-device model file has been imported.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `LiteRT engine rejects nonexistent model file before native initialization`() {
        val engine = LiteRtLmOnDeviceChatCompletionEngine(mockk<Context>(relaxed = true))
        val missing = File("/tmp/missing-model.litertlm")

        val result = engine.generate(selection(modelPath = missing.absolutePath), OnDeviceChatRequest.userText("hello"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains(missing.absolutePath))
    }

    private fun assertAcceptedAndEchoed(requestedModel: String) {
        val engine = FakeEngine(response = "served by device")
        val bridge = LocalOpenAiOnDeviceBridge(engine)
        bridge.start(selection()).use { session ->
            val response = post(
                url = "${session.baseUrl}/chat/completions",
                body = """
                    {
                      "model": "$requestedModel",
                      "messages": [
                        { "role": "user", "content": "Say hello" }
                      ]
                    }
                """.trimIndent(),
                bearerToken = session.authToken,
            )

            assertEquals(200, response.code)
            assertTrue(response.body.contains("served by device"))
            assertTrue(response.body.contains("\"model\":\"$requestedModel\""))
            assertEquals("Say hello", engine.lastRequest?.latest?.text)
        }
    }

    private fun selection(modelPath: String? = "/data/model/gemma.litertlm"): EmbeddedLettaCodeModelSelection = EmbeddedLettaCodeModelSelection(
        modelHandle = "local/gemma-3n",
        modelPath = modelPath,
        runtime = "litert-lm",
        accelerator = "gpu",
        maxTokens = 4096,
    )

    private fun Response.modelIds(): List<String> =
        Json.parseToJsonElement(body).jsonObject.getValue("data").jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }

    private fun get(url: String, bearerToken: String? = null): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        return connection.readResponse()
    }

    private fun post(
        url: String,
        body: String,
        bearerToken: String? = null,
    ): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return connection.readResponse()
    }

    private fun postRawRequest(
        url: String,
        body: String,
        bearerToken: String? = null,
        contentLength: String,
    ): Response {
        val uri = java.net.URI(url)
        val requestBody = body.toByteArray(Charsets.UTF_8)

        return Socket(uri.host, uri.port).use { socket ->
            val request = buildString {
                append("POST ${uri.path} HTTP/1.1\r\n")
                append("Host: ${uri.host}\r\n")
                append("Content-Type: application/json\r\n")
                if (bearerToken != null) {
                    append("Authorization: Bearer $bearerToken\r\n")
                }
                append("Content-Length: $contentLength\r\n")
                append("Connection: close\r\n\r\n")
            }
            val output = socket.getOutputStream()
            output.write(request.toByteArray(Charsets.UTF_8))
            output.write(requestBody)
            output.flush()

            val responseText = socket.getInputStream().bufferedReader().readText()
            parseHttpResponse(responseText)
        }
    }

    private fun parseHttpResponse(responseText: String): Response {
        val headerSplitIndex = responseText.indexOf("\r\n\r\n")
        val headersBlock = if (headerSplitIndex >= 0) responseText.substring(0, headerSplitIndex) else responseText
        val bodyText = if (headerSplitIndex >= 0) responseText.substring(headerSplitIndex + 4) else ""
        val statusLine = headersBlock.substringBefore("\r\n").trimEnd()
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        val contentType = headersBlock
            .lines()
            .drop(1)
            .firstOrNull { it.startsWith("content-type", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return Response(
            code = status,
            body = bodyText,
            contentType = contentType,
        )
    }

    private fun HttpURLConnection.readResponse(): Response {
        val code = responseCode
        val stream = if (code < 400) inputStream else errorStream
        return Response(
            code = code,
            body = stream.bufferedReader().use { it.readText() },
            contentType = contentType,
        )
    }

    private data class Response(val code: Int, val body: String, val contentType: String?)

    /** The tool_calls of an SSE reply's chunks. */
    private fun Response.sseToolCalls() = body.lineSequence()
        .filter { it.startsWith("data: {") }
        .flatMap { line ->
            Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject.getValue("choices").jsonArray
                .flatMap { choice -> choice.jsonObject.getValue("delta").jsonObject["tool_calls"]?.jsonArray.orEmpty() }
        }
        .map { it.jsonObject }
        .toList()

    private class FakeEngine(
        private val response: String = "ok",
        private val failure: Throwable? = null,
        private val replies: ArrayDeque<OnDeviceReply> = ArrayDeque(),
    ) : OnDeviceChatCompletionEngine {
        val requests = mutableListOf<OnDeviceChatRequest>()
        val lastRequest: OnDeviceChatRequest? get() = requests.lastOrNull()

        override fun generate(
            modelSelection: EmbeddedLettaCodeModelSelection,
            request: OnDeviceChatRequest,
            generation: OnDeviceGeneration,
        ): Result<OnDeviceReply> {
            requests += request
            failure?.let { return Result.failure(it) }
            return Result.success(replies.removeFirstOrNull() ?: OnDeviceReply(response))
        }
    }

    private companion object {
        const val READ_TOOL =
            """{"type": "function", "function": {"name": "Read", "description": "Read a file.",
            "parameters": {"type": "object", "required": ["file_path"]}}}"""
    }
}
