package com.letta.mobile.runtime.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceOpenAiBridgeInstrumentedTest {
    @Test
    fun bridgeRespondsWithImportedLiteRtLmModel() {
        val arguments = InstrumentationRegistry.getArguments()
        val modelPath = arguments.getString(MODEL_PATH_ARG).orEmpty()
        assumeTrue("Pass -e $MODEL_PATH_ARG /path/to/model.litertlm to run this smoke.", modelPath.isNotBlank())
        assumeTrue("Model file does not exist at $modelPath.", File(modelPath).isFile)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LiteRtLmOnDeviceChatCompletionEngine(context)
        val bridge = LocalOpenAiOnDeviceBridge(engine)

        bridge.start(
            EmbeddedLettaCodeModelSelection(
                modelHandle = "local/instrumented-smoke",
                modelPath = modelPath,
                runtime = "litert-lm",
                accelerator = "cpu",
                maxTokens = 128,
            )
        ).use { session ->
            val response = post(
                url = "${session.baseUrl}/chat/completions",
                body = """
                    {
                      "model": "instrumented-smoke",
                      "messages": [
                        { "role": "user", "content": "Reply with exactly: OK" }
                      ]
                    }
                """.trimIndent(),
            )

            assertEquals(response.body, 200, response.code)
            assertTrue(response.body, response.body.contains("\"chat.completion\""))
            assertTrue(response.body, response.body.contains("\"assistant\""))
        }
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

    private companion object {
        private const val MODEL_PATH_ARG = "litertModelPath"
    }
}
