package com.letta.mobile.runtime.local

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A loopback OpenAI-compatible model for the on-device App Server smoke (letta-mobile-by4vm.110.5),
 * ported from the embedded-runtime prototype's fake-llm.cjs: the first completion calls the shell
 * tool once, the one after the tool result answers [REPLY]. Streams SSE like LM Studio.
 *
 * letta-mobile-by4vm.121.1: it serves the routes [OnDeviceOpenAiBridge] serves (`/v1/models`,
 * `/v1/chat/completions`, 404 otherwise, so letta-code's `/api/v0/models` probe falls back the same
 * way) and advertises the model exactly as the bridge does: [lettaCodeLookupId] of [modelHandle].
 */
internal class FakeOpenAiServer(val modelHandle: String = MODEL_HANDLE) : AutoCloseable {
    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val completions = AtomicInteger()
    private val json = Json { ignoreUnknownKeys = true }
    private val servedModel = lettaCodeLookupId(modelHandle)

    val baseUrl: String = "http://127.0.0.1:${socket.localPort}/v1"

    /** Completion requests served, for the test's failure message. */
    val requests: Int get() = completions.get()

    init {
        thread(name = "fake-openai", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use(::serve) }
            }
        }
    }

    override fun close() = socket.close()

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val head = readHead(input)
        val path = head.firstOrNull()?.split(" ")?.getOrNull(1).orEmpty()
        val headers = head.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val body = readBody(input, headers)
        val out = client.getOutputStream()
        if (path == "/v1/models") {
            val models = buildJsonObject {
                put("object", "list")
                put("data", buildJsonArray { add(buildJsonObject { put("id", servedModel); put("object", "model") }) })
            }.toString()
            out.writeJson(200, models)
            return
        }
        if (path != "/v1/chat/completions") {
            out.writeJson(404, """{"error":{"type":"not_found","message":"Unknown route: $path"}}""")
            return
        }
        completions.incrementAndGet()
        val request = runCatching { json.parseToJsonElement(body).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
        completionChunks(request).forEach { out.write("data: $it\n\n".toByteArray()) }
        out.write("data: [DONE]\n\n".toByteArray())
        out.flush()
    }

    private fun completionChunks(request: JsonObject): List<String> {
        val tools = request["tools"]?.jsonArray.orEmpty().mapNotNull { tool ->
            val obj = tool.jsonObject
            (obj["function"]?.jsonObject?.get("name") ?: obj["name"])?.jsonPrimitive?.contentOrNull
        }
        val sawToolResult = request["messages"]?.jsonArray.orEmpty()
            .any { it.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "tool" }
        val shell = SHELL_TOOLS.firstOrNull { it in tools }.takeUnless { sawToolResult }
        val delta = if (shell != null) {
            buildJsonObject {
                put("role", "assistant")
                put("tool_calls", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("id", "call_device_${completions.get()}")
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", shell)
                            put("arguments", buildJsonObject { put("command", TOOL_COMMAND); put("description", "smoke") }.toString())
                        })
                    })
                })
            }
        } else {
            buildJsonObject { put("role", "assistant"); put("content", REPLY) }
        }
        return listOf(chunk(delta, finish = null), chunk(JsonObject(emptyMap()), finish = if (shell != null) "tool_calls" else "stop"))
    }

    private fun chunk(delta: JsonObject, finish: String?): String = buildJsonObject {
        put("id", "chatcmpl-${completions.get()}")
        put("object", "chat.completion.chunk")
        put("model", servedModel)
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                put("delta", delta)
                put("finish_reason", finish)
            })
        })
    }.toString()

    private fun OutputStream.writeJson(status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        write("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        write(bytes)
    }

    private fun readHead(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            lines += line
        }
        return lines
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): String {
        headers["content-length"]?.toIntOrNull()?.let { length -> return String(readExactly(input, length), Charsets.UTF_8) }
        if (headers["transfer-encoding"]?.contains("chunked") != true) return ""
        val body = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(radix = 16) ?: break
            if (size == 0) break
            body.write(readExactly(input, size))
            readLine(input)
        }
        return body.toString(Charsets.UTF_8.name())
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray =
        ByteArray(size).also { DataInputStream(input).readFully(it) }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8.name())
            if (next == '\n'.code) return bytes.toString(Charsets.UTF_8.name()).removeSuffix("\r")
            bytes.write(next)
        }
    }

    companion object {
        /** Real-style handle: a vendor/model id with a slash, like `lmstudio/google/gemma-3n-E2B-it-litert-lm`. */
        const val MODEL_HANDLE = "lmstudio/google/device-smoke-litert-lm"
        const val REPLY = "DEVICE-SMOKE-OK"
        const val TOOL_COMMAND = "echo DEVICE-TOOL-\$((6*7))"
        private const val BACKLOG = 16
        private val SHELL_TOOLS = listOf("Bash", "bash", "shell", "Shell", "ShellCommand", "run_shell_command", "exec_command")
    }
}
