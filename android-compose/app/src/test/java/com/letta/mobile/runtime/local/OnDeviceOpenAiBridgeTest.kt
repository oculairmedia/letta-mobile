package com.letta.mobile.runtime.local

import android.content.Context
import io.mockk.mockk
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceOpenAiBridgeTest {
    @Test
    fun `models endpoint exposes selected model id`() {
        val bridge = LocalOpenAiOnDeviceBridge(FakeEngine())
        bridge.start(selection()).use { session ->
            val response = get("${session.baseUrl}/models", session.authToken)

            assertEquals(200, response.code)
            assertTrue(response.body.contains("gemma-3n"))
            assertTrue(response.body.contains("letta-mobile"))
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
            assertEquals("system: Be direct.\nuser: Say hello", engine.lastPrompt)
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
            assertTrue(engine.lastPrompt.orEmpty().contains("user:"))
            assertTrue(engine.lastPrompt.orEmpty().contains("Describe this"))
            assertEquals(1, engine.lastImages.size)
            assertEquals("image/png", engine.lastImages.single().mediaType)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), engine.lastImages.single().bytes)
        }
    }

    @Test
    fun `chat completions endpoint reports engine failure as unavailable`() {
        val bridge = LocalOpenAiOnDeviceBridge(
            FakeEngine(result = Result.failure(IllegalStateException("runtime missing"))),
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

    // letta-mobile-69i0z: prompt-format tool calling through the bridge.
    @Test
    fun `tool call reply becomes openai tool_calls with finish_reason tool_calls`() {
        val engine = FakeEngine(
            response = "```tool_call\n{\"name\": \"memory\", \"arguments\": {\"op\": \"view\"}}\n```",
        )
        val bridge = LocalOpenAiOnDeviceBridge(engine)
        bridge.start(selection()).use { session ->
            val response = post(
                url = "${session.baseUrl}/chat/completions",
                body = """
                    {
                      "messages": [{"role": "user", "content": "check memory"}],
                      "tools": [{"type": "function", "function": {
                        "name": "memory", "description": "Edit memory.",
                        "parameters": {"type": "object"}
                      }}]
                    }
                """.trimIndent(),
                bearerToken = session.authToken,
            )

            assertEquals(200, response.code)
            assertTrue(response.body.contains("\"tool_calls\""))
            assertTrue(response.body.contains("\"name\":\"memory\""))
            assertTrue(response.body.contains("\"finish_reason\":\"tool_calls\""))
            // Tool schemas must reach the model through the prompt.
            assertTrue(engine.lastPrompt.orEmpty().contains("- memory: Edit memory."))
            assertTrue(engine.lastPrompt.orEmpty().contains("```tool_call"))
        }
    }

    @Test
    fun `streamed tool call emits tool_calls delta then tool_calls finish`() {
        val engine = FakeEngine(
            response = "```tool_call\n{\"name\": \"Bash\", \"arguments\": {\"command\": \"ls\"}}\n```",
        )
        val bridge = LocalOpenAiOnDeviceBridge(engine)
        bridge.start(selection()).use { session ->
            val response = post(
                url = "${session.baseUrl}/chat/completions",
                body = """
                    {
                      "stream": true,
                      "messages": [{"role": "user", "content": "list files"}],
                      "tools": [{"type": "function", "function": {
                        "name": "Bash", "description": "Run a command.",
                        "parameters": {"type": "object"}
                      }}]
                    }
                """.trimIndent(),
                bearerToken = session.authToken,
            )

            assertEquals(200, response.code)
            assertTrue(response.contentType.orEmpty().startsWith("text/event-stream"))
            assertTrue(response.body.contains("\"tool_calls\""))
            assertTrue(response.body.contains("\"finish_reason\":\"tool_calls\""))
            assertTrue(response.body.contains("data: [DONE]"))
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

        val result = engine.generate(selection(modelPath = null), "hello")

        assertTrue(result.isFailure)
        assertEquals("No on-device model file has been imported.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `LiteRT engine rejects nonexistent model file before native initialization`() {
        val engine = LiteRtLmOnDeviceChatCompletionEngine(mockk<Context>(relaxed = true))
        val missing = File("/tmp/missing-model.litertlm")

        val result = engine.generate(selection(modelPath = missing.absolutePath), "hello")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains(missing.absolutePath))
    }

    private fun selection(modelPath: String? = "/data/model/gemma.litertlm"): EmbeddedLettaCodeModelSelection = EmbeddedLettaCodeModelSelection(
        modelHandle = "local/gemma-3n",
        modelPath = modelPath,
        runtime = "litert-lm",
        accelerator = "gpu",
        maxTokens = 4096,
    )

    private fun get(url: String, bearerToken: String? = null): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        return connection.readResponse()
    }

    private fun post(url: String, body: String, bearerToken: String? = null): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return connection.readResponse()
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

    private class FakeEngine(
        private val response: String = "ok",
        private val result: Result<String>? = null,
    ) : OnDeviceChatCompletionEngine {
        var lastPrompt: String? = null

        var lastImages: List<OnDeviceImage> = emptyList()

        override fun generate(
            modelSelection: EmbeddedLettaCodeModelSelection,
            prompt: String,
            images: List<OnDeviceImage>,
        ): Result<String> {
            lastPrompt = prompt
            lastImages = images
            return result ?: Result.success(response)
        }
    }
}
