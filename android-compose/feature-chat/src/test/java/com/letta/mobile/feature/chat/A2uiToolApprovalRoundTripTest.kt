package com.letta.mobile.feature.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.ui.a2ui.A2uiSurfaceRenderer
import com.letta.mobile.ui.a2ui.A2uiTestTags
import com.letta.mobile.ui.test.setLettaTestContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class A2uiToolApprovalRoundTripTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val openServers = mutableListOf<A2uiShimServer>()
    private val openTransports = mutableListOf<ChannelTransport>()

    @After
    fun tearDown() {
        openTransports.forEach { it.disconnectForTest() }
        openServers.forEach(A2uiShimServer::close)
    }

    @Test
    fun toolApprovalOnceRoundTripsOverAdminShimWebSocket() = runTest {
        val server = openServer()
        val transport = openTransport()
        val bridge = WsChatBridge(transport)
        val manager = A2uiSurfaceManager()
        connect(transport, bridge, server)

        val surfaceArrived = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            bridge.a2uiEvents.first { event ->
                event.messages.any { it.surfaceId == SurfaceId }
            }
        }
        server.sendToolApprovalSurface(
            surfaceId = SurfaceId,
            callId = "call-once",
            affordances = listOf("once", "session", "forever", "deny"),
        )
        val renderedAt = System.nanoTime()
        manager.apply(withRealTimeout { surfaceArrived.await() })

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiSurfaceRenderer(
                surface = manager.surface(SurfaceId),
                onAction = { bridge.sendA2uiAction(it) },
            )
        }

        composeRule.onNodeWithTag(A2uiTestTags.ToolApprovalCard).assertIsDisplayed()
        composeRule.onNodeWithText("bash").assertIsDisplayed()
        composeRule.onNodeWithText("rm -rf /tmp/junk").assertIsDisplayed()
        assertTrue("ToolApprovalCard should render inside the 2s budget", elapsedMs(renderedAt) < 2_000)

        val confirmation = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            bridge.events.first { event ->
                val message = (event as? WsTimelineEvent.MessageDelta)?.message as? AssistantMessage
                message?.content == "Executed rm -rf /tmp/junk"
            }
        }
        val tapAt = System.nanoTime()
        composeRule.onNodeWithText("Once").performClick()
        composeRule.onNodeWithText("Approved once").assertIsDisplayed()

        val action = withRealTimeout { server.actions.receive() }
        action.assertToolApprovalAction(
            surfaceId = SurfaceId,
            callId = "call-once",
            decision = "approve",
            scope = "once",
        )
        assertTrue("Approval tap should emit promptly", elapsedMs(tapAt) < 1_000)
        withRealTimeout { confirmation.await() }
    }

    @Test
    fun allToolApprovalAffordancesEmitExpectedUserActions() = runTest {
        val server = openServer()
        val transport = openTransport()
        val bridge = WsChatBridge(transport)
        connect(transport, bridge, server)
        val currentSurface = mutableStateOf<A2uiSurfaceState?>(null)

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiSurfaceRenderer(
                surface = currentSurface.value,
                onAction = { bridge.sendA2uiAction(it) },
            )
        }

        val scenarios = listOf(
            AffordanceScenario("once", "Once", "approve", "once"),
            AffordanceScenario("session", "This chat", "approve", "session"),
            AffordanceScenario("forever", "Always", "approve", "forever"),
            AffordanceScenario("deny", "Deny", "deny", "deny"),
        )

        scenarios.forEachIndexed { index, scenario ->
            val surfaceId = "$SurfaceId-${scenario.wireValue}"
            val callId = "call-${scenario.wireValue}"
            val manager = A2uiSurfaceManager()
            val surfaceArrived = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                bridge.a2uiEvents.first { event ->
                    event.messages.any { it.surfaceId == surfaceId }
                }
            }
            server.sendToolApprovalSurface(
                surfaceId = surfaceId,
                callId = callId,
                affordances = listOf(scenario.wireValue),
            )
            manager.apply(withRealTimeout { surfaceArrived.await() })
            composeRule.runOnIdle {
                currentSurface.value = manager.surface(surfaceId)
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithText(scenario.label).performClick()
            val action = withRealTimeout { server.actions.receive() }
            action.assertToolApprovalAction(
                surfaceId = surfaceId,
                callId = callId,
                decision = scenario.decision,
                scope = scenario.scope,
            )
            assertEquals(index + 1, server.actionCount)
        }
    }

    @Test
    fun toolApprovalTimeoutEmitsUserAction() = runTest {
        composeRule.mainClock.autoAdvance = false
        val server = openServer()
        val transport = openTransport()
        val bridge = WsChatBridge(transport)
        val manager = A2uiSurfaceManager()
        connect(transport, bridge, server)

        try {
            val surfaceArrived = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                bridge.a2uiEvents.first { event ->
                    event.messages.any { it.surfaceId == "timeout-surface" }
                }
            }
            server.sendToolApprovalSurface(
                surfaceId = "timeout-surface",
                callId = "call-timeout",
                affordances = listOf("once"),
                timeoutSeconds = 1,
            )
            manager.apply(withRealTimeout { surfaceArrived.await() })

            composeRule.setLettaTestContent(useChatTheme = false) {
                A2uiSurfaceRenderer(
                    surface = manager.surface("timeout-surface"),
                    onAction = { bridge.sendA2uiAction(it) },
                )
            }

            composeRule.onNodeWithText("Auto-denies in 1s").assertIsDisplayed()
            composeRule.mainClock.advanceTimeBy(1_100)
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Timed out").assertIsDisplayed()
            val action = withRealTimeout { server.actions.receive() }
            action.assertToolApprovalAction(
                surfaceId = "timeout-surface",
                callId = "call-timeout",
                decision = "timeout",
                scope = "timeout",
            )
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun queuedApprovalActionFlushesAfterReconnect() = runTest {
        val server = openServer()
        val transport = openTransport()
        val bridge = WsChatBridge(transport)
        val manager = A2uiSurfaceManager()
        connect(transport, bridge, server)

        val surfaceArrived = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            bridge.a2uiEvents.first { event ->
                event.messages.any { it.surfaceId == "reconnect-surface" }
            }
        }
        server.sendToolApprovalSurface(
            surfaceId = "reconnect-surface",
            callId = "call-reconnect",
            affordances = listOf("once"),
        )
        manager.apply(withRealTimeout { surfaceArrived.await() })

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiSurfaceRenderer(
                surface = manager.surface("reconnect-surface"),
                onAction = { bridge.sendA2uiAction(it) },
            )
        }

        server.closeActiveSocket()
        withRealTimeout {
            bridge.state.first { it is ChannelTransport.State.Disconnected }
        }

        composeRule.onNodeWithText("Once").performClick()
        composeRule.onNodeWithText("Approved once").assertIsDisplayed()
        assertTrue(server.actions.tryReceive().isFailure)

        connect(transport, bridge, server)
        val action = withRealTimeout { server.actions.receive() }
        action.assertToolApprovalAction(
            surfaceId = "reconnect-surface",
            callId = "call-reconnect",
            decision = "approve",
            scope = "once",
        )
    }

    private fun openServer(): A2uiShimServer =
        A2uiShimServer().also(openServers::add)

    private fun openTransport(): ChannelTransport =
        ChannelTransport().also(openTransports::add)
}

private data class AffordanceScenario(
    val wireValue: String,
    val label: String,
    val decision: String,
    val scope: String,
)

private suspend fun connect(
    transport: ChannelTransport,
    bridge: WsChatBridge,
    server: A2uiShimServer,
) = coroutineScope {
    val connected = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        bridge.state.first { it is ChannelTransport.State.Connected }
    }
    transport.connect(
        baseShimUrl = server.baseUrl(),
        token = "token",
        deviceId = "device",
        clientVersion = "test",
    )
    withRealTimeout { connected.await() }
}

private fun JsonObject.assertToolApprovalAction(
    surfaceId: String,
    callId: String,
    decision: String,
    scope: String,
) {
    assertEquals("userAction", stringValue("type"))
    assertEquals("tool_approval_response", stringValue("actionName"))
    assertEquals(surfaceId, stringValue("surfaceId"))
    val context = this["context"]!!.jsonObject
    assertEquals(callId, context.stringValue("callId"))
    assertEquals(decision, context.stringValue("decision"))
    assertEquals(scope, context.stringValue("scope"))
}

private fun JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: error("Missing string field $key")

private fun elapsedMs(startNanos: Long): Long =
    (System.nanoTime() - startNanos) / 1_000_000

private suspend fun <T> withRealTimeout(block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        withTimeout(TIMEOUT_MS) { block() }
    }

private class A2uiShimServer {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val server = MockWebServer()
    private val firstSocket = CompletableDeferred<WebSocket>()
    @Volatile private var activeSocket: WebSocket? = null
    private var actionCounter = 0

    val actions = Channel<JsonObject>(Channel.UNLIMITED)
    val actionCount: Int
        get() = actionCounter

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            activeSocket = webSocket
                            firstSocket.complete(webSocket)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = json.parseToJsonElement(text).jsonObject
                            when (obj.stringValue("type")) {
                                "hello" -> webSocket.send(welcomeFrame())
                                "userAction" -> {
                                    actionCounter += 1
                                    actions.trySend(obj)
                                    webSocket.send(confirmationFrame())
                                }
                            }
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
                    }
                )
        }
        server.start()
    }

    fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    suspend fun sendToolApprovalSurface(
        surfaceId: String,
        callId: String,
        affordances: List<String>,
        timeoutSeconds: Int = 30,
    ) {
        (activeSocket ?: firstSocket.await()).send(toolApprovalFrame(surfaceId, callId, affordances, timeoutSeconds))
    }

    fun closeActiveSocket() {
        activeSocket?.close(1001, "test reconnect")
    }

    fun close() {
        actions.close()
        server.shutdown()
    }

    private fun welcomeFrame(): String =
        """
        {"v":1,"type":"welcome","id":"welcome-1","ts":"2026-05-17T00:00:00Z",
         "server_id":"server","session_id":"session"}
        """.trimIndent()

    private fun confirmationFrame(): String =
        """
        {"v":1,"type":"assistant_message","id":"cm-stream-confirmed","ts":"2026-05-17T00:00:03Z",
         "agent_id":"agent-e2e","conversation_id":"conv-e2e","turn_id":"turn-e2e","run_id":"run-e2e",
         "content":"Executed rm -rf /tmp/junk"}
        """.trimIndent()

    private fun toolApprovalFrame(
        surfaceId: String,
        callId: String,
        affordances: List<String>,
        timeoutSeconds: Int,
    ): String {
        val affordancesJson = affordances.joinToString(prefix = "[", postfix = "]") { """"$it"""" }
        return """
            {"v":1,"type":"a2ui","id":"a2ui-$surfaceId","ts":"2026-05-17T00:00:01Z",
             "agent_id":"agent-e2e","conversation_id":"conv-e2e","turn_id":"turn-e2e","run_id":"run-e2e",
             "messages":[
               {"version":"v0.9","createSurface":{
                 "surfaceId":"$surfaceId",
                 "catalogId":"com.letta.mobile:tool-approval/v1"
               }},
               {"version":"v0.9","updateComponents":{"surfaceId":"$surfaceId","root":"approval","components":[
                 {"id":"approval","component":"ToolApprovalCard",
                  "toolName":"bash",
                  "toolDescription":"Run a shell command",
                  "arguments":[{"key":"cmd","value":"rm -rf /tmp/junk","isSensitive":false}],
                  "riskLevel":"destructive",
                  "rationale":"The agent needs approval before deleting a path.",
                  "affordances":$affordancesJson,
                  "timeoutSeconds":$timeoutSeconds,
                  "callId":"$callId"}
               ]}}
             ]}
        """.trimIndent()
    }
}

private fun ChannelTransport.disconnectForTest() {
    kotlinx.coroutines.runBlocking {
        disconnect()
    }
}

private const val TIMEOUT_MS = 5_000L
