package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginContract
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginRuntime
import com.letta.mobile.data.plugin.PluginSecretDecl
import com.letta.mobile.data.plugin.PluginSecrets
import com.letta.mobile.data.plugin.PluginSettingField
import com.letta.mobile.data.plugin.PluginSettingType
import com.letta.mobile.data.plugin.wire.LcpPluginSession
import com.letta.mobile.data.plugin.wire.RecordingHost
import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionContext
import com.letta.mobile.plugin.api.ActionOrigin
import com.letta.mobile.plugin.api.HostInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A [ProcessPluginDriver] over [FakeLcpPlugin] in a fresh package directory. */
class ProcessDriverFixture(
    scope: CoroutineScope,
    mode: String = "normal",
    options: (ProcessDriverOptions) -> ProcessDriverOptions = { it },
    confinement: ProcessConfinementSource = ProcessConfinementSource { ProcessConfinement.Unconfined },
    env: Map<String, String> = DEFAULT_ENV,
) {
    val packageDir: Path = Files.createTempDirectory("lcp-fake-plugin")
    val stderr: MutableList<String> = CopyOnWriteArrayList()
    val host = RecordingHost()
    val driver = ProcessPluginDriver(
        spec = ProcessPluginSpec(
            manifest = manifest(mode, env),
            packageDir = packageDir,
            settings = buildJsonObject { put("greeting", "hello from settings") },
            secrets = PluginSecrets().with("apiToken", SECRET),
            consentedCapabilities = setOf(PluginCapability.CANVAS_PLACE),
        ),
        handlers = host,
        scope = scope,
        options = options(
            ProcessDriverOptions(
                hostInfo = HostInfo("letta-test-host", "0"),
                path = "${File(System.getProperty("java.home"), "bin")}${File.pathSeparator}${System.getenv("PATH").orEmpty()}",
                confinement = confinement,
                healthInterval = 1.minutes,
                onStderr = stderr::add,
            ),
        ),
    )

    val starts: Int get() = packageDir.resolve("starts.log").toFile().takeIf(File::exists)?.readLines()?.size ?: 0

    suspend fun awaitState(predicate: (PluginDriverState) -> Boolean): PluginDriverState =
        withTimeout(30.seconds) { driver.state.first(predicate) }

    fun delete() {
        packageDir.toFile().deleteRecursively()
    }

    companion object {
        const val SECRET = "s3cr3t-token-value"

        val DEFAULT_ENV = mapOf("API_TOKEN" to "\${secrets.apiToken}", "GREETING" to "\${settings.greeting}")

        fun call(action: String) = ActionCall(action, JsonObject(emptyMap()), ActionContext(ActionOrigin.Agent("agent-1")))

        /** The test classpath, plus the code sources the fake needs should the worker hide them. */
        val classpath: String by lazy {
            val anchors = listOf(FakeLcpPlugin::class.java, LcpPluginSession::class.java, ActionCall::class.java, Unit::class.java, Job::class.java, Json::class.java, kotlinx.serialization.KSerializer::class.java)
            val sources = anchors.mapNotNull { it.protectionDomain?.codeSource?.location?.toURI()?.let(::File)?.absolutePath }
            (System.getProperty("java.class.path").split(File.pathSeparator) + sources).filter(String::isNotEmpty).distinct().joinToString(File.pathSeparator)
        }

        fun manifest(mode: String, env: Map<String, String> = DEFAULT_ENV) = PluginManifest(
            manifestVersion = 1,
            id = "letta.fake",
            name = "Fake",
            version = "1.0.0",
            publisher = "letta",
            contract = PluginContract(1),
            runtime = PluginRuntime.Process(
                command = "java",
                args = listOf("-Xshare:auto", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xmx64m", "-cp", classpath, FakeLcpPlugin::class.java.name, "--mode=$mode"),
                env = env,
            ),
            settings = mapOf("greeting" to PluginSettingField(PluginSettingType.STRING)),
            secrets = listOf(PluginSecretDecl("apiToken")),
            capabilities = listOf(PluginCapability.CANVAS_PLACE),
        )
    }
}
