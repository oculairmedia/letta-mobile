package com.letta.mobile.runtime.local

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import ca.oculair.meridian.BuildConfig
import com.letta.mobile.appserver.AppServerSmokeSpec
import com.letta.mobile.appserver.runAppServerSmokeTurn
import com.letta.mobile.data.model.MessageContentPart
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
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
 * one `runtime_start` + tool turn through `runAppServerSmokeTurn`, then one turn with an image.
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
 *
 * letta-mobile-by4vm.121.1: the provider file is the app's own ([writeLocalProviderAuth], no
 * `models[]`), so letta-code resolves the agent's `lmstudio/<vendor>/<model>` handle by discovery,
 * exactly as it does against [OnDeviceOpenAiBridge].
 * letta-mobile-by4vm.121.12: letta-code prepares every image in a child node it starts with
 * `process.execPath`, the app process here; the image turn fails ("Image worker exited from signal
 * SIGABRT") unless child-node.cjs relaunches it on the packaged node CLI.
 * letta-mobile-by4vm.121.2: every `device-smoke-*` agent (this run's and any a crashed run left)
 * is deleted through `agent_delete` before the lease closes, and the test fails if one remains:
 * they would otherwise be listed as the user's agents.
 */
class LocalAppServerDeviceSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun hostLeaseServesAToolTurnThroughTheSharedClient() = runBlocking {
        assumeTrue("needs -PembedLettaCodeNative=true", BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED)
        assumeTrue("needs -PembedLettaCodeAssets=true", BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED)
        val args = InstrumentationRegistry.getArguments()
        val modelHandle = args.getString(ARG_MODEL)?.let { LETTA_CODE_LMSTUDIO_HANDLE_PREFIX + it } ?: FakeOpenAiServer.MODEL_HANDLE
        val fake = if (args.getString(ARG_BASE_URL) == null) FakeOpenAiServer(modelHandle) else null
        val baseUrl = args.getString(ARG_BASE_URL) ?: requireNotNull(fake).baseUrl
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = HttpClient(OkHttp) { install(WebSockets) }
        try {
            val extractStart = SystemClock.elapsedRealtime()
            val project = EmbeddedLettaCodeAssetExtractor(context).prepare()
            log("asset extraction ${SystemClock.elapsedRealtime() - extractStart} ms")
            project.storageDirectory.writeLocalProviderAuth(ProviderEndpoint(baseUrl, SMOKE_API_KEY))

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
                    lifecycleLog = RuntimeLifecycleLog(context),
                ),
                discovery = AppServerDiscovery(http),
                scope = scope,
            )
            val acquireStart = SystemClock.elapsedRealtime()
            val lease = host.acquire()
            log("node::Start -> Listening -> readyz ${SystemClock.elapsedRealtime() - acquireStart} ms at ${lease.serverUrl}")
            assertTrue(lease.serverUrl, lease.serverUrl.matches(Regex("""ws://127\.0\.0\.1:\d+""")))

            deletingSmokeAgentsAfter(http, lease) {
                assertServesThisContract(http, lease)
                val (agentId, conversationId) = createAgentAndConversation(http, lease, modelHandle)
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

                assertTrue(lines.joinToString("\n"), lines.any { it.startsWith("[lifecycle] Completed") })
                if (fake != null) {
                    assertTrue("tool call; fake served ${fake.requests}: $lines", lines.any { it.startsWith("[tool-call]") })
                    assertTrue("tool return: $lines", lines.any { it.startsWith("[tool-return]") })
                    assertTrue("the shell tool must succeed: $lines", lines.none { it.startsWith("[tool-return] Failed") })
                }
                assertImageTurnCompletes(lease, agentId, conversationId)
                assertTrue("the host is still serving", host.health.value.active)
            }
            lease.close()
        } finally {
            http.close()
            fake?.close()
            scope.cancel()
        }
    }

    private suspend fun assertImageTurnCompletes(lease: LocalAppServerLease, agentId: String, conversationId: String) {
        val lines = mutableListOf<String>()
        runAppServerSmokeTurn(
            engineFactory = CIO,
            identityPrefix = "device-image",
            spec = AppServerSmokeSpec(
                url = lease.serverUrl,
                token = lease.bearerToken,
                agentId = agentId,
                conversationId = conversationId,
                message = "What is in this picture?",
                timeoutMs = TURN_TIMEOUT_MS,
                images = listOf(MessageContentPart.Image(base64 = SMOKE_PNG_BASE64, mediaType = "image/png")),
            ),
            newToken = { UUID.randomUUID().toString() },
            emit = { line -> log(line); lines += line },
        )
        assertTrue("the image turn must complete: $lines", lines.any { it.startsWith("[lifecycle] Completed") })
        assertTrue("image preparation failed: $lines", lines.none { "Failed to prepare image" in it })
    }

    private suspend fun assertServesThisContract(http: HttpClient, lease: LocalAppServerLease) {
        val info = AppServerDiscovery(http).appServerInfo(lease.serverUrl, lease.bearerToken)
        log("app-server-info: $info")
        assertTrue(info.error, info.success)
        assertEquals(LettaCodeContract.VERSION, info.lettaCodeVersion)
        assertEquals(1, info.protocolVersion)
    }

    /**
     * Runs [block], then deletes every `device-smoke-*` agent (and with it its conversations) even
     * when [block] failed. A cleanup failure fails the test; after a [block] failure it is attached
     * as suppressed so the first cause stays on top.
     */
    private suspend fun deletingSmokeAgentsAfter(http: HttpClient, lease: LocalAppServerLease, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        val cleanupFailure = runCatching { deleteSmokeAgents(http, lease) }.exceptionOrNull()
        if (failure != null) {
            cleanupFailure?.let(failure::addSuppressed)
            throw failure
        }
        if (cleanupFailure != null) {
            throw AssertionError("device-smoke cleanup failed: the leftover agents stay in the app's agent list", cleanupFailure)
        }
    }

    private suspend fun deleteSmokeAgents(http: HttpClient, lease: LocalAppServerLease) = coroutineScope {
        val transport = KtorAppServerWebSocketTransport(http, lease.serverUrl, this, lease.bearerToken)
        try {
            val client = DefaultAppServerClient(transport, requestTimeoutMs = SETUP_TIMEOUT_MS)
            smokeAgentIds(client).forEach { agent ->
                val deleted = client.agentDelete(
                    AppServerCommand.AgentDelete(requestId = "device-agent-delete-${UUID.randomUUID()}", agentId = agent),
                )
                assertTrue("agent_delete $agent: ${deleted.error}", deleted.success)
                log("deleted smoke agent $agent")
            }
            val left = smokeAgentIds(client)
            assertTrue("device-smoke agents left after agent_delete: $left", left.isEmpty())
        } finally {
            transport.close()
        }
    }

    private suspend fun smokeAgentIds(client: DefaultAppServerClient): List<String> {
        val page = client.agentList(
            AppServerCommand.AgentList(
                requestId = "device-agent-list-${UUID.randomUUID()}",
                query = buildJsonObject {
                    put("query_text", SMOKE_AGENT_PREFIX)
                    put("limit", AGENT_LIST_LIMIT)
                },
            ),
        )
        assertTrue("agent_list: ${page.error}", page.success)
        return page.agents.orEmpty()
            .map { it.jsonObject }
            .filter { it["name"]?.jsonPrimitive?.contentOrNull?.startsWith(SMOKE_AGENT_PREFIX) == true }
            .mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }
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
                        put("name", "$SMOKE_AGENT_PREFIX${System.currentTimeMillis()}")
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
        const val SMOKE_AGENT_PREFIX = "device-smoke-"
        const val SMOKE_API_KEY = "lm-studio"
        const val AGENT_LIST_LIMIT = 1_000

        /** A 4x4 PNG, sent the way the app sends a photo. */
        const val SMOKE_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEElEQVR4nGP4z8AARwzEcQCukw/x0F8jngAAAABJRU5ErkJggg=="
    }
}
