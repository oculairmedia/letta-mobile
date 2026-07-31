package com.letta.mobile.appservercli

import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputMessage
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartCreateAgentOptions
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartCreateConversationOptions
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Executable restart/replay probe for letta-mobile-lgns8.15.
 *
 * Launches a real local Letta Code App Server (`node letta.js app-server`) against
 * an isolated `LETTA_LOCAL_BACKEND_DIR`, drives it over the production
 * [KtorAppServerWebSocketTransport], kills and restarts the process against the
 * same backend root, and observes the on-disk transcript to establish — not
 * assume — what survives a process restart and whether the server deduplicates a
 * replayed `client_message_id`.
 *
 * JVM-only by construction (ProcessBuilder). No credentials are hardcoded: the
 * provider key, letta-code paths, and the provider HOME come from the environment
 * via [Config.fromEnv]. See `appserver-cli/README.md` ("Regenerating the
 * restart/replay evidence") for the exact regeneration recipe.
 */
class AppServerRestartReplayProbe(private val config: Config) {

    data class Config(
        val node: String,
        val lettaJs: Path,
        val openRouterApiKey: String,
        val model: String,
        val backendDir: Path,
        val port: Int,
        /**
         * HOME handed to the spawned app-server. letta-code resolves its persisted
         * provider config (`letta connect …`) relative to HOME, so pointing this at a
         * throwaway dir keeps a regeneration run from reading — or writing — the
         * operator's live `~/.letta`. Defaults to the real home for back-compat.
         */
        val home: Path,
        /**
         * Optional `providers/auth.json` copied into the fresh backend dir before the
         * first launch. With the local backend, `letta connect` persists provider auth
         * under the BACKEND dir (not HOME), so a probe run against a throwaway backend
         * has no provider at all unless it is seeded — every turn then fails and commits
         * no assistant message.
         */
        val providerAuthSeed: Path?,
    ) {
        companion object {
            /** Resolves config from env, or null when prerequisites are absent (test should skip). */
            fun fromEnv(backendDir: Path, port: Int = 4623): Config? {
                val key = System.getenv("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() } ?: return null
                val node = System.getenv("LETTA_CODE_NODE")?.takeIf { it.isNotBlank() } ?: "node"
                val lettaJs = System.getenv("LETTA_CODE_JS")?.takeIf { it.isNotBlank() }
                    ?.let { Path.of(it) }
                    ?: Path.of(System.getProperty("user.home"), "letta-code-install/node_modules/@letta-ai/letta-code/letta.js")
                if (!lettaJs.exists()) return null
                val model = System.getenv("LETTA_CODE_PROBE_MODEL")?.takeIf { it.isNotBlank() }
                    ?: "openrouter/nvidia/nemotron-nano-9b-v2:free"
                val home = System.getenv("LETTA_CODE_PROBE_HOME")?.takeIf { it.isNotBlank() }
                    ?.let { Path.of(it) }
                    ?: Path.of(System.getProperty("user.home"))
                val providerAuthSeed = System.getenv("LETTA_CODE_PROBE_PROVIDER_AUTH")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Path.of(it) }
                return Config(node, lettaJs, key, model, backendDir, port, home, providerAuthSeed)
            }
        }
    }

    /** Observations captured from a real run; compared against the committed evidence. */
    data class Observation(
        val reattachRecreatedAgentAfterRestart: Boolean,
        val reattachRecreatedConversationAfterRestart: Boolean,
        val clientMessageIdCountAfterResend: Int,
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(): Observation {
        val cmid = "probe-${UUID.randomUUID()}"
        seedProviderAuth()
        // ---- Phase 1: fresh process, create a local agent + conversation, run one turn.
        var process = launchServer()
        val scope: AppServerRuntimeScope = try {
            withClient { client ->
                val created = runtimeStartCreate(client)
                sendInputAwaitTerminal(client, created, cmid, "Reply with exactly one word: PONG")
                created
            }
        } finally {
            stopServer(process)
        }
        check(transcriptUserOtidCount(cmid) == 1) { "expected the created turn to commit exactly one user message" }
        // A turn that ends on an error stop_reason still commits the user message and still
        // reports a terminal frame, so without this check the whole probe passes green while
        // no model ever answered — the evidence would then be regenerated from a dead run.
        check(transcriptAssistantCount() >= 1) {
            "no assistant message committed: the model turn never completed (check the provider " +
                "config in LETTA_CODE_PROBE_HOME and that LETTA_CODE_PROBE_MODEL is reachable)"
        }

        // ---- Phase 2: restart the process against the SAME backend dir, reattach, resend the SAME cmid.
        process = launchServer()
        val restart: RestartOutcome
        try {
            restart = withClient { client ->
                val reattach = runtimeStartReattach(client, scope)
                sendInputAwaitTerminal(client, reattach.runtime, cmid, "Reply with exactly one word: PONG")
                reattach
            }
        } finally {
            stopServer(process)
        }

        return Observation(
            reattachRecreatedAgentAfterRestart = restart.recreatedAgent,
            reattachRecreatedConversationAfterRestart = restart.recreatedConversation,
            clientMessageIdCountAfterResend = transcriptUserOtidCount(cmid),
        )
    }

    private data class RestartOutcome(
        val runtime: AppServerRuntimeScope,
        val recreatedAgent: Boolean,
        val recreatedConversation: Boolean,
    )

    // A tiny helper that provides a connected client bound to a fresh scope, closing it after.
    private suspend fun <T> withClient(block: suspend (DefaultAppServerClient) -> T): T = coroutineScope {
        // Independent scope for the transport I/O jobs so cancelling it never
        // propagates to the caller's job hierarchy.
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = HttpClient(CIO) {
            install(WebSockets)
            install(HttpTimeout) {
                requestTimeoutMillis = TURN_TIMEOUT_MS
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = TURN_TIMEOUT_MS
            }
        }
        val transport = KtorAppServerWebSocketTransport(
            httpClient = http,
            baseUrl = "ws://127.0.0.1:${config.port}",
            scope = ioScope,
        )
        try {
            block(DefaultAppServerClient(transport, requestTimeoutMs = TURN_TIMEOUT_MS))
        } finally {
            runCatching { transport.close() }
            ioScope.cancel()
            http.close()
        }
    }

    private suspend fun runtimeStartCreate(client: DefaultAppServerClient): AppServerRuntimeScope {
        val response = client.runtimeStart(
            AppServerCommand.RuntimeStart(
                requestId = "probe-create-${UUID.randomUUID()}",
                createAgent = AppServerRuntimeStartCreateAgentOptions(
                    body = buildJsonObject {
                        put("name", "lgns815-probe")
                        put("model", config.model)
                    },
                ),
                createConversation = AppServerRuntimeStartCreateConversationOptions(body = buildJsonObject {}),
                clientInfo = AppServerRuntimeStartClientInfo(name = "lgns815-probe", version = "0"),
            ),
        )
        check(response.success) { "runtime_start(create) failed: ${response.error}" }
        return requireNotNull(response.runtime) { "runtime_start(create) returned no runtime scope" }
    }

    private suspend fun runtimeStartReattach(
        client: DefaultAppServerClient,
        scope: AppServerRuntimeScope,
    ): RestartOutcome {
        val response = client.runtimeStart(
            AppServerCommand.RuntimeStart(
                requestId = "probe-reattach-${UUID.randomUUID()}",
                agentId = scope.agentId,
                conversationId = scope.conversationId,
                clientInfo = AppServerRuntimeStartClientInfo(name = "lgns815-probe", version = "0"),
            ),
        )
        check(response.success) { "runtime_start(reattach) failed: ${response.error}" }
        val runtime = requireNotNull(response.runtime) { "runtime_start(reattach) returned no runtime scope" }
        return RestartOutcome(
            runtime = runtime,
            recreatedAgent = response.created?.agent ?: false,
            recreatedConversation = response.created?.conversation ?: false,
        )
    }

    private suspend fun sendInputAwaitTerminal(
        client: DefaultAppServerClient,
        runtime: AppServerRuntimeScope,
        clientMessageId: String,
        text: String,
    ) = coroutineScope {
        val terminal = CompletableDeferred<Unit>()
        val collector: Job = launch {
            client.events.collect { received ->
                val delta = (received.frame as? AppServerInboundFrame.StreamDelta)?.delta as? JsonObject
                if (delta?.get("message_type")?.jsonPrimitive?.contentOrNull == "stop_reason") {
                    terminal.complete(Unit)
                }
            }
        }
        // Let the shared-flow collector subscribe before the (multi-second) turn starts.
        delay(SUBSCRIBE_GRACE_MS)
        client.input(
            AppServerCommand.Input(
                runtime = runtime,
                payload = AppServerInputPayload.CreateMessage(
                    messages = listOf(AppServerInputMessage.userText(text, clientMessageId = clientMessageId)),
                ),
            ),
        )
        try {
            withTimeout(TURN_TIMEOUT_MS.milliseconds) { terminal.await() }
        } finally {
            collector.cancel()
        }
    }

    /** Count committed USER messages whose otid == [clientMessageId] across the on-disk transcript. */
    private fun transcriptUserOtidCount(clientMessageId: String): Int =
        countCommittedMessages { role, otid -> role == "user" && otid == clientMessageId }

    /** Count committed ASSISTANT messages across the on-disk transcript. */
    private fun transcriptAssistantCount(): Int =
        countCommittedMessages { role, _ -> role == "assistant" }

    private fun countCommittedMessages(predicate: (role: String?, otid: String?) -> Boolean): Int {
        val conversationsDir = config.backendDir.resolve("conversations")
        if (!conversationsDir.exists()) return 0
        var count = 0
        Files.list(conversationsDir).use { dirs ->
            dirs.forEach { conv ->
                val messages = conv.resolve("messages.jsonl")
                if (messages.exists()) {
                    Files.newBufferedReader(messages).use { reader ->
                        count += countInStream(reader, predicate)
                    }
                }
            }
        }
        return count
    }

    private fun countInStream(reader: BufferedReader, predicate: (role: String?, otid: String?) -> Boolean): Int {
        var count = 0
        reader.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val message = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
                ?.get("message") as? JsonObject ?: return@forEach
            val role = message["role"]?.jsonPrimitive?.contentOrNull
            val otid = message["otid"]?.jsonPrimitive?.contentOrNull
            if (predicate(role, otid)) count++
        }
        return count
    }

    /** Copy the operator-supplied provider auth into the throwaway backend dir, if configured. */
    private fun seedProviderAuth() {
        val seed = config.providerAuthSeed ?: return
        check(seed.exists()) { "LETTA_CODE_PROBE_PROVIDER_AUTH does not exist: $seed" }
        val target = config.backendDir.resolve("providers").resolve("auth.json")
        Files.createDirectories(target.parent)
        Files.copy(seed, target)
    }

    private fun launchServer(): Process {
        val builder = ProcessBuilder(
            config.node,
            config.lettaJs.toString(),
            "app-server",
            "--listen",
            "ws://127.0.0.1:${config.port}",
        )
        builder.redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", config.home.toString())
            put("LETTA_LOCAL_BACKEND_EXPERIMENTAL", "1")
            put("LETTA_LOCAL_BACKEND_DIR", config.backendDir.toString())
            put("OPENROUTER_API_KEY", config.openRouterApiKey)
        }
        val process = builder.start()
        awaitListening(process)
        return process
    }

    private fun awaitListening(process: Process) {
        val deadline = System.currentTimeMillis() + SERVER_START_TIMEOUT_MS
        val reader = process.inputStream.bufferedReader()
        // Drain output on a daemon thread so the process never blocks on a full pipe;
        // signal readiness when the listen banner appears.
        val ready = java.util.concurrent.CountDownLatch(1)
        val drain = Thread {
            // Reading may throw once the process exits and closes the pipe; that is benign.
            runCatching {
                reader.lineSequence().forEach { line ->
                    if (line.contains("Listening on")) ready.countDown()
                }
            }
        }
        drain.isDaemon = true
        drain.start()
        val started = ready.await(SERVER_START_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        check(started && System.currentTimeMillis() < deadline + 1_000) {
            "local App Server did not report 'Listening on' within ${SERVER_START_TIMEOUT_MS}ms"
        }
    }

    private fun stopServer(process: Process) {
        process.destroy()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val TURN_TIMEOUT_MS = 120_000L
        const val SERVER_START_TIMEOUT_MS = 30_000L
        const val SUBSCRIBE_GRACE_MS = 400L
    }
}
