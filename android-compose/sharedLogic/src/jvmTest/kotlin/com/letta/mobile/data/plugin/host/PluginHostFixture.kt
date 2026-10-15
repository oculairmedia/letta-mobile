package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.storage.InMemoryAssetStore
import com.letta.mobile.plugin.api.HostInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.minutes

/**
 * A [PluginHostRuntime] over a temp plugins dir, an in-memory canvas, and `.lcp` packages built in
 * the test: `letta.fake` runs [FakeLcpPlugin] through a `run.sh` in its package (`sh run.sh`), so
 * install, unpack, consent, the driver and the tool path are all real.
 */
class PluginHostFixture(val pluginsDir: Path = Files.createTempDirectory("plugin-host")) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relayStore = InMemoryCanvasRelayStore()
    private val kinds = LateBoundPluginKinds()
    val backend = HostCanvasBackend(CanvasRelayHost(relayStore, hostId = { "host-1" }), relayStore, InMemoryHostCanvasDirectory(), kinds = kinds)

    fun runtime(): PluginHostRuntime {
        val options = ProcessDriverOptions(
            hostInfo = HostInfo("letta-test-host", "0"),
            path = "/usr/bin:/bin",
            confinement = ProcessConfinementSource { ProcessConfinement.Unconfined },
            healthInterval = 1.minutes,
        )
        return PluginHostRuntime(PluginHostConfig(pluginsDir, backend, InMemoryAssetStore(), options, nowMs = { NOW }), scope)
            .also { kinds.target = it.kinds }
    }

    fun close() {
        scope.cancel()
        pluginsDir.toFile().deleteRecursively()
    }

    companion object {
        const val ID = "letta.fake"
        const val TOOL = "fake_plot"
        const val NOW = 1_760_000_000_000L

        /** The fake's `.lcp` at [version], asking for [capabilities] (wire names), running [FakeLcpPlugin] in [mode] as [id]. */
        fun pkg(version: String = "1.0.0", capabilities: List<String> = listOf("canvas:place"), mode: String = "normal", id: String = ID): ByteArray {
            val manifest = """
                {"manifestVersion":1,"id":"$id","name":"Fake","version":"$version","publisher":"letta","contract":{"version":1},
                 "runtime":{"kind":"process","command":"sh","args":["run.sh"]},
                 "capabilities":[${capabilities.joinToString { "\"$it\"" }}],
                 "elements":{"chart":{"schemaVersion":1,"props":{"type":"object","additionalProperties":false,"properties":{"title":{"type":"string","maxLength":200}}}}},
                 "actions":{"plot":{"description":"Plot a chart","visibility":["agent","view"],"input":{"type":"object"}},
                            "toggle":{"visibility":["view"],"input":{"type":"object"}}}}
            """.trimIndent()
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.entry("letta-plugin.json", manifest)
                zip.entry("run.sh", runScript(mode))
            }
            return out.toByteArray()
        }

        private fun runScript(mode: String): String {
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val flags = "-Xshare:auto -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xmx64m"
            return "exec '$java' $flags -cp '${ProcessDriverFixture.classpath}' ${FakeLcpPlugin::class.java.name} --mode=$mode\n"
        }

        private fun ZipOutputStream.entry(name: String, text: String) {
            putNextEntry(ZipEntry(name))
            write(text.toByteArray())
            closeEntry()
        }

        suspend fun PluginHostRuntime.callTool(name: String = TOOL): ExternalToolResult {
            val tool = toolSource.tools.value.single { it.name == name }
            return tool.invoke(JsonObject(emptyMap()), ExternalToolCaller("agent-1", "conv-1", "call-1"))
        }
    }
}
