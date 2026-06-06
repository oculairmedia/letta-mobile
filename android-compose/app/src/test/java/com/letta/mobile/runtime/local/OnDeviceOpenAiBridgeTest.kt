package com.letta.mobile.runtime.local

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceOpenAiBridgeTest {
    @Test
    fun `models endpoint exposes selected model id`() {
        val bridge = LocalOpenAiOnDeviceBridge(FakeEngine())
        bridge.start(selection()).use { session ->
            val response = get("${session.baseUrl}/models")

            assertEquals(200, response.code)
            assertTrue(response.body.contains("gemma-3n"))
            assertTrue(response.body.contains("letta-mobile"))
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
            )

            assertEquals(200, response.code)
            assertTrue(response.body.contains("Hello from device"))
            assertEquals("system: Be direct.\nuser: Say hello", engine.lastPrompt)
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
            )

            assertEquals(503, response.code)
            assertTrue(response.body.contains("on_device_runtime_unavailable"))
            assertTrue(response.body.contains("runtime missing"))
        }
    }

    private fun selection(): EmbeddedLettaCodeModelSelection = EmbeddedLettaCodeModelSelection(
        modelHandle = "local/gemma-3n",
        modelPath = "/data/model/gemma.litertlm",
        runtime = "litert-lm",
        accelerator = "gpu",
        maxTokens = 4096,
    )

    private fun get(url: String): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        return connection.readResponse()
    }

    private fun post(url: String, body: String): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return connection.readResponse()
    }

    private fun HttpURLConnection.readResponse(): Response {
        val code = responseCode
        val stream = if (code < 400) inputStream else errorStream
        return Response(code = code, body = stream.bufferedReader().use { it.readText() })
    }

    private data class Response(val code: Int, val body: String)

    private class FakeEngine(
        private val response: String = "ok",
        private val result: Result<String>? = null,
    ) : OnDeviceChatCompletionEngine {
        var lastPrompt: String? = null

        override fun generate(modelSelection: EmbeddedLettaCodeModelSelection, prompt: String): Result<String> {
            lastPrompt = prompt
            return result ?: Result.success(response)
        }
    }
}
