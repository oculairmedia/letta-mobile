package com.letta.mobile.data.plugin.wire

import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginManifestParser
import com.letta.mobile.data.plugin.PluginManifestResult
import com.letta.mobile.plugin.api.LcpDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.assertIs

/**
 * The reference plugin `plugins/chart` (letta-mobile-cdgpr.2) run as the `process` runtime runs it:
 * `node server.mjs` in the package directory, LCP over its stdin/stdout as NDJSON. [transport]
 * records every message both ways so a test can hold the session to a golden transcript.
 */
internal class ChartPluginProcess private constructor(private val process: Process) : AutoCloseable {
    private val recorder = RecordingLcpTransport(NdjsonLcpTransport(source(), sink()))

    val transport: LcpTransport get() = recorder

    /** Every message so far as transcript lines `{"dir", "message"}`, in the order the host saw them. */
    fun transcript(): List<JsonObject> = recorder.lines()

    /** The exit code once the process ends within [seconds], or null while it still runs. */
    fun awaitExit(seconds: Long): Int? = if (process.waitFor(seconds, TimeUnit.SECONDS)) process.exitValue() else null

    override fun close() {
        recorder.close()
        if (process.isAlive) process.destroyForcibly()
    }

    private fun source() = LcpByteSource {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_BYTES)
            val read = process.inputStream.read(buffer)
            if (read < 0) null else buffer.copyOf(read)
        }
    }

    private fun sink() = object : LcpByteSink {
        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
            process.outputStream.write(bytes)
            process.outputStream.flush()
        }

        override fun close() {
            runCatching { process.outputStream.close() }
        }
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024

        /** From the sharedLogic module directory, where Gradle runs its tests. */
        val DIR: File = File("../../plugins/chart")

        /** Whether `node` runs here; the process tests skip without it. */
        val nodeAvailable: Boolean by lazy {
            try {
                val probe = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
                probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0
            } catch (_: IOException) {
                false
            }
        }

        fun start(): ChartPluginProcess =
            ChartPluginProcess(ProcessBuilder("node", "server.mjs").directory(DIR).redirectError(ProcessBuilder.Redirect.INHERIT).start())

        fun manifest(): PluginManifest =
            assertIs<PluginManifestResult.Parsed>(PluginManifestParser.parse(DIR.resolve("letta-plugin.json").readText())).manifest
    }
}

/** Records each message as it is sent (before it can be answered) and as it is received. */
private class RecordingLcpTransport(private val delegate: LcpTransport) : LcpTransport {
    private val recorded = Collections.synchronizedList(mutableListOf<JsonObject>())

    override suspend fun send(text: String) {
        record(LcpDirection.HOST_TO_PLUGIN, text)
        delegate.send(text)
    }

    override suspend fun receive(): LcpInboundFrame? = delegate.receive().also { if (it is LcpInboundFrame.Text) record(LcpDirection.PLUGIN_TO_HOST, it.text) }

    override fun close() = delegate.close()

    fun lines(): List<JsonObject> = synchronized(recorded) { recorded.toList() }

    private fun record(dir: LcpDirection, text: String) {
        recorded += JsonObject(mapOf("dir" to JsonPrimitive(dir.label), "message" to Json.parseToJsonElement(text)))
    }
}

/** The golden wire transcripts under `commonTest/resources/canvas/plugin/v1/wire/`. */
internal object WireTranscripts {
    private const val DIR = "/canvas/plugin/v1/wire"

    fun raw(name: String): List<JsonObject> =
        checkNotNull(javaClass.getResource("$DIR/$name")) { "missing fixture $name" }.readText()
            .lines().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }

    fun lines(name: String): List<WireLine> = raw(name).map { entry ->
        val dir = LcpDirection.entries.single { it.label == entry.getValue("dir").jsonPrimitive.content }
        WireLine(dir, assertIs<JsonRpcDecoding.Decoded>(JsonRpcCodec.decode(entry.getValue("message"))).message)
    }

    /** The values that depend on the PNG bytes (zlib's output may differ between builds) replaced, numbers normalised. */
    fun masked(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) -> if (key in BYTE_DEPENDENT) JsonPrimitive("<masked>") else masked(value) })
        is JsonArray -> JsonArray(element.map(::masked))
        is JsonPrimitive -> normalized(element)
    }

    private val BYTE_DEPENDENT = setOf("base64", "sha256", "ref", "byteSize")
}
