package com.letta.mobile.runtime.local

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import ca.oculair.meridian.BuildConfig
import com.letta.mobile.appserver.AppServerSmokeSpec
import com.letta.mobile.appserver.runAppServerSmokeTurn
import com.letta.mobile.data.runtime.host.LocalAppServerLease
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerDiscovery
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import com.letta.mobile.data.transport.appserver.LettaCodeContract
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * letta-mobile-by4vm.110.5: the on-device App Server end to end, through the same pieces the app
 * binds in Hilt: [LibnodeAppServerLauncher] on the in-process libnode, the shared
 * [com.letta.mobile.data.runtime.host.LocalAppServerHost] (lease, `/readyz`, capability token),
 * then the shared App Server client: `app-server-info`, `agent_create`, `conversation_create`, and
 * one `runtime_start` + tool turn through `runAppServerSmokeTurn`.
 *
 * node::Start runs once per process: run this class ALONE, in its own instrumentation run, on a
 * full-fat build (`-PembedLettaCodeNative=true -PembedLettaCodeAssets=true`):
 * ```
 * adb shell am instrument -w -e class com.letta.mobile.runtime.local.LocalAppServerDeviceSmokeTest \
 *   com.letta.mobile.dev.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * The model is an in-process fake that calls the shell tool once. For a real OpenAI-compatible
 * endpoint add `-e appServerSmokeBaseUrl http://192.168.50.90:8082/v1 -e appServerSmokeModel MiniMax-M3`
 * (the tool call is then up to the model; the turn must still complete).
 */
class LocalAppServerDeviceSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun hostLeaseServesAToolTurnThroughTheSharedClient() = runBlocking {
        assumeTrue("needs -PembedLettaCodeNative=true", BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED)
        assumeTrue("needs -PembedLettaCodeAssets=true", BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED)
        val args = InstrumentationRegistry.getArguments()
        val fake = if (args.getString(ARG_BASE_URL) == null) FakeOpenAiServer() else null
        val baseUrl = args.getString(ARG_BASE_URL) ?: requireNotNull(fake).baseUrl
        val model = args.getString(ARG_MODEL) ?: FakeOpenAiServer.MODEL
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = HttpClient(OkHttp) { install(WebSockets) }
        try {
            val extractStart = SystemClock.elapsedRealtime()
            val project = EmbeddedLettaCodeAssetExtractor(context).prepare()
            log("asset extraction ${SystemClock.elapsedRealtime() - extractStart} ms")
            writeSmokeProvider(project.storageDirectory, baseUrl, model)

            val host = androidLocalAppServerHost(
                launcher = LibnodeAppServerLauncher(NativeLettaCodeNodeBridge()),
                launchSource = AndroidLocalAppServerLaunchSource(
                    context,
                    EmbeddedLettaCodeAssetExtractor(context),
                    LocalAndroidNetworkBridge(),
                    onDeviceBridge = object : OnDeviceOpenAiBridge {
                        override fun start(modelSelection: EmbeddedLettaCodeModelSelection) =
                            error("the smoke writes its own provider")
                    },
                ),
                discovery = AppServerDiscovery(http),
                scope = scope,
            )
            val acquireStart = SystemClock.elapsedRealtime()
            val lease = host.acquire()
            log("node::Start -> Listening -> readyz ${SystemClock.elapsedRealtime() - acquireStart} ms at ${lease.serverUrl}")
            assertTrue(lease.serverUrl, lease.serverUrl.matches(Regex("""ws://127\.0\.0\.1:\d+""")))

            val info = AppServerDiscovery(http).appServerInfo(lease.serverUrl, lease.bearerToken)
            log("app-server-info: $info")
            assertTrue(info.error, info.success)
            assertEquals(LettaCodeContract.VERSION, info.lettaCodeVersion)
            assertEquals(1, info.protocolVersion)

            val (agentId, conversationId) = createAgentAndConversation(http, lease, "lmstudio/$model")
            val lines = mutableListOf<String>()
            runAppServerSmokeTurn(
                // CIO: the smoke sets a WebSocket maxFrameSize, which Ktor's OkHttp engine refuses.
                engineFactory = CIO,
                identityPrefix = "device",
                spec = AppServerSmokeSpec(
                    url = lease.serverUrl,
                    token = lease.bearerToken,
                    agentId = agentId,
                    conversationId = conversationId,
                    message = "Run `${FakeOpenAiServer.TOOL_COMMAND}` with your shell tool and tell me what it printed.",
                    timeoutMs = TURN_TIMEOUT_MS,
                ),
                newToken = { UUID.randomUUID().toString() },
                emit = { line -> log(line); lines += line },
            )

            assertTrue(lines.joinToString("\n"), lines.any { it == "[lifecycle] Completed" })
            if (fake != null) {
                assertTrue("tool call; fake served ${fake.requests}: $lines", lines.any { it.startsWith("[tool-call]") })
                assertTrue("tool return: $lines", lines.any { it.startsWith("[tool-return]") })
                assertTrue("the shell tool must succeed: $lines", lines.none { it.startsWith("[tool-return] Failed") })
            }
            assertTrue("the host is still serving", host.health.value.active)
            lease.close()
        } finally {
            http.close()
            fake?.close()
            scope.cancel()
        }
    }

    /** The prototype-proven provider shape: lmstudio, with the model listed so no discovery is needed. */
    private fun writeSmokeProvider(backendDir: File, baseUrl: String, model: String) {
        val auth = File(backendDir, "providers/auth.json")
        auth.parentFile?.mkdirs()
        auth.writeText(
            """
            { "version": 1, "providers": { "lc-lmstudio": {
              "id": "local-provider-lc-lmstudio", "name": "lc-lmstudio", "provider_type": "lmstudio",
              "provider_category": "byok", "auth": { "type": "api", "key": "lm-studio" },
              "base_url": "$baseUrl",
              "models": [ { "id": "$model", "contextWindow": 128000, "maxTokens": 4096,
                "input": ["text"], "cost": { "input": 0, "output": 0 } } ] } } }
            """.trimIndent(),
        )
    }

    private suspend fun createAgentAndConversation(
        http: HttpClient,
        lease: LocalAppServerLease,
        modelHandle: String,
    ): Pair<String, String> = coroutineScope {
        val transport = KtorAppServerWebSocketTransport(http, lease.serverUrl, this, lease.bearerToken)
        try {
            val client = DefaultAppServerClient(transport, requestTimeoutMs = SETUP_TIMEOUT_MS)
            val agent = client.agentCreate(
                AppServerCommand.AgentCreate(
                    requestId = "device-agent-${UUID.randomUUID()}",
                    body = buildJsonObject {
                        put("name", "device-smoke-${System.currentTimeMillis()}")
                        put("model", modelHandle)
                    },
                ),
            )
            assertTrue("agent_create: ${agent.error}", agent.success)
            val agentId = requireNotNull(agent.agent?.get("id")?.jsonPrimitive?.contentOrNull) { "agent id: ${agent.agent}" }
            val conversation = client.conversationCreate(
                AppServerCommand.ConversationCreate(
                    requestId = "device-conversation-${UUID.randomUUID()}",
                    body = buildJsonObject { put("agent_id", agentId) },
                ),
            )
            assertTrue("conversation_create: ${conversation.error}", conversation.success)
            val conversationId = requireNotNull(conversation.conversation?.get("id")?.jsonPrimitive?.contentOrNull) {
                "conversation id: ${conversation.conversation}"
            }
            log("agent=$agentId conversation=$conversationId model=$modelHandle")
            agentId to conversationId
        } finally {
            transport.close()
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "LocalAppServerSmoke"
        const val ARG_BASE_URL = "appServerSmokeBaseUrl"
        const val ARG_MODEL = "appServerSmokeModel"
        const val SETUP_TIMEOUT_MS = 60_000L
        const val TURN_TIMEOUT_MS = 180_000L
    }
}
