package com.letta.mobile.runtime.local

import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-by4vm.121: the bridge never lets one generation outlive its request: a client that
 * goes away cancels it and frees the engine for the next request; a timeout, the output cap and the
 * repetition guard end it with what was produced. Plus the catalog-driven vision surface.
 */
class OnDeviceBridgeGenerationGuardTest {
    @Test
    fun `client disconnect cancels the generation and the next request runs at once`() {
        val engine = StreamingEngine(chunkDelayMs = 20)
        LocalOpenAiOnDeviceBridge(engine).start(selection()).use { session ->
            val uri = URI("${session.baseUrl}/chat/completions")
            // letta-code aborts its request on a Stop: the socket closes mid-generation.
            Socket(uri.host, uri.port).use { socket ->
                val body = """{"stream":true,"messages":[{"role":"user","content":"loop forever"}]}""".toByteArray()
                socket.getOutputStream().apply {
                    write(
                        ("POST ${uri.path} HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer ${session.authToken}\r\n" +
                            "Content-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray(),
                    )
                    write(body)
                    flush()
                }
                assertTrue("generation started", engine.started.await(5, TimeUnit.SECONDS))
            }
            assertTrue("the engine saw the cancel", engine.cancelled.await(5, TimeUnit.SECONDS))
            assertEquals(OnDeviceStopReason.ClientDisconnected, engine.generations.first().stopReason)

            engine.chunkLimit = 3
            val startedAt = System.currentTimeMillis()
            val next = post(session, """{"messages":[{"role":"user","content":"hi"}]}""")
            assertEquals(200, next.first)
            assertTrue("the queued request was not held behind the cancelled one", System.currentTimeMillis() - startedAt < 3_000)
        }
    }

    @Test
    fun `a timeout ends the generation with what was produced`() {
        val engine = StreamingEngine(chunkDelayMs = 20)
        LocalOpenAiOnDeviceBridge(engine, OnDeviceModelCapabilities.Unknown, generationTimeoutMs = 300).start(selection()).use { session ->
            val (code, body) = post(session, """{"messages":[{"role":"user","content":"go"}]}""")

            assertEquals(200, code)
            assertTrue(body.contains("t0 "))
            assertEquals(OnDeviceStopReason.Timeout, engine.generations.single().stopReason)
        }
    }

    @Test
    fun `a timeout before any output is a non retryable error`() {
        val engine = StreamingEngine(chunkDelayMs = 2_000)
        LocalOpenAiOnDeviceBridge(engine, OnDeviceModelCapabilities.Unknown, generationTimeoutMs = 200).start(selection()).use { session ->
            val (code, body) = post(session, """{"messages":[{"role":"user","content":"go"}]}""")

            assertEquals(422, code)
            assertTrue(body.contains("on_device_timeout"))
        }
    }

    @Test
    fun `the request max_tokens caps the output`() {
        val engine = StreamingEngine()
        LocalOpenAiOnDeviceBridge(engine).start(selection()).use { session ->
            val (code, body) = post(session, """{"max_tokens":40,"messages":[{"role":"user","content":"go"}]}""")

            assertEquals(200, code)
            val generation = engine.generations.single()
            assertEquals(OnDeviceStopReason.MaxTokens, generation.stopReason)
            assertEquals(40, generation.limits.maxOutputTokens)
            assertTrue(engine.chunksSent <= 41)
            assertTrue("the client's own limit is reported as length", body.contains("\"finish_reason\":\"length\""))
        }
    }

    @Test
    fun `without max_tokens the on device default cap applies`() {
        val engine = StreamingEngine()
        LocalOpenAiOnDeviceBridge(engine).start(selection()).use { session ->
            val (code, body) = post(session, """{"max_tokens":32000,"messages":[{"role":"user","content":"go"}]}""")

            assertEquals(200, code)
            assertEquals(OnDeviceGenerationLimits.DEFAULT_MAX_OUTPUT_TOKENS, engine.generations.single().limits.maxOutputTokens)
            assertEquals(OnDeviceStopReason.MaxTokens, engine.generations.single().stopReason)
            // letta-code fails a `length` turn; the bridge's own ceiling delivers the reply as `stop`.
            assertTrue(body.contains("\"finish_reason\":\"stop\""))
        }
    }

    @Test
    fun `a repetition loop ends early and returns the text before it`() {
        val engine = StreamingEngine(chunk = { i -> if (i < 3) "Hello there. " else "the " })
        LocalOpenAiOnDeviceBridge(engine).start(selection()).use { session ->
            val (code, body) = post(session, """{"messages":[{"role":"user","content":"go"}]}""")

            assertEquals(200, code)
            assertEquals(OnDeviceStopReason.Repetition, engine.generations.single().stopReason)
            assertTrue(engine.chunksSent < 100)
            val content = Json.parseToJsonElement(body).jsonObject.getValue("choices").jsonArray[0]
                .jsonObject.getValue("message").jsonObject.getValue("content").jsonPrimitive.content
            assertEquals("Hello there. Hello there. Hello there. the", content)
        }
    }

    @Test
    fun `lm studio model list advertises vision from the catalog without a context length`() {
        val vision = OnDeviceModelCapabilities { OnDeviceModelCapability(supportsImages = true) }
        LocalOpenAiOnDeviceBridge(StreamingEngine(), vision).start(selection()).use { session ->
            val model = getModels(session)

            assertEquals(selection().openAiModelId, model.getValue("id").jsonPrimitive.content)
            assertEquals("vlm", model.getValue("type").jsonPrimitive.content)
            assertEquals(listOf("vision"), model.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content })
            // letta-code's pi-ai keeps 4,096 tokens of margin: a small advertised window clamps output to 1 token.
            assertFalse(model.containsKey("max_context_length"))
            assertFalse(model.containsKey("loaded_context_length"))
        }
    }

    @Test
    fun `lm studio model list says text only for a model the catalog does not know`() {
        LocalOpenAiOnDeviceBridge(StreamingEngine()).start(selection()).use { session ->
            val model = getModels(session)

            assertEquals("llm", model.getValue("type").jsonPrimitive.content)
            assertTrue(model.getValue("capabilities").jsonArray.isEmpty())
        }
    }

    @Test
    fun `an image reaches the engine of a vision model`() {
        val engine = StreamingEngine(chunkLimit = 2)
        val vision = OnDeviceModelCapabilities { OnDeviceModelCapability(supportsImages = true) }
        LocalOpenAiOnDeviceBridge(engine, vision).start(selection()).use { session ->
            val (code, _) = post(session, IMAGE_REQUEST)

            assertEquals(200, code)
            assertEquals(1, engine.lastImages.size)
        }
    }

    @Test
    fun `an image for a text only catalog model fails clearly`() {
        val engine = StreamingEngine()
        val textOnly = OnDeviceModelCapabilities { OnDeviceModelCapability(supportsImages = false) }
        LocalOpenAiOnDeviceBridge(engine, textOnly).start(selection()).use { session ->
            val (code, body) = post(session, IMAGE_REQUEST)

            assertEquals(422, code)
            assertTrue(body.contains("on_device_images_unsupported"))
            assertTrue(engine.generations.isEmpty())
        }
    }

    private fun getModels(session: OnDeviceOpenAiBridgeSession) =
        (URL(session.baseUrl.removeSuffix("/v1") + "/api/v0/models").openConnection() as HttpURLConnection).run {
            setRequestProperty("Authorization", "Bearer ${session.authToken}")
            assertEquals(200, responseCode)
            Json.parseToJsonElement(inputStream.bufferedReader().readText()).jsonObject
                .getValue("data").jsonArray.single().jsonObject
        }

    private fun post(session: OnDeviceOpenAiBridgeSession, body: String): Pair<Int, String> =
        (URL("${session.baseUrl}/chat/completions").openConnection() as HttpURLConnection).run {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${session.authToken}")
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body.toByteArray()) }
            val code = responseCode
            code to (if (code < 400) inputStream else errorStream).bufferedReader().readText()
        }

    private fun selection() = EmbeddedLettaCodeModelSelection(
        modelHandle = "local/gemma-3n",
        modelPath = "/data/model/gemma.litertlm",
        runtime = "litert-lm",
        accelerator = "gpu",
        maxTokens = 4096,
    )

    /** Streams distinct chunks (no loop) through the guard until it says stop, like the LiteRT engine. */
    private class StreamingEngine(
        private val chunkDelayMs: Long = 0,
        @Volatile var chunkLimit: Int = Int.MAX_VALUE,
        private val chunk: (Int) -> String = { i -> "t$i " },
    ) : OnDeviceChatCompletionEngine {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val generations = mutableListOf<OnDeviceGeneration>()
        @Volatile var chunksSent = 0
        @Volatile var lastImages: List<OnDeviceImage> = emptyList()

        @Synchronized
        override fun generate(
            modelSelection: EmbeddedLettaCodeModelSelection,
            request: OnDeviceChatRequest,
            generation: OnDeviceGeneration,
        ): Result<OnDeviceReply> {
            generations += generation
            lastImages = request.images
            generation.onCancel { cancelled.countDown() }
            started.countDown()
            var streaming = true
            while (streaming && chunksSent < chunkLimit) streaming = streamOne(generation)
            return Result.success(OnDeviceReply(generation.text))
        }

        /** Sends the next chunk after the delay; false once the generation stopped. */
        private fun streamOne(generation: OnDeviceGeneration): Boolean {
            if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
            if (generation.isStopped) return false
            return generation.accept(chunk(chunksSent++))
        }
    }

    private companion object {
        const val IMAGE_REQUEST = """{"messages":[{"role":"user","content":[
            {"type":"image_url","image_url":{"url":"data:image/png;base64,AQIDBA=="}},
            {"type":"text","text":"Describe this"}]}]}"""
    }
}
