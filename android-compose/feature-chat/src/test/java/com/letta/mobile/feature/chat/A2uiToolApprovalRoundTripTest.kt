package com.letta.mobile.feature.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.a2ui.A2uiSurfaceManager
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.timeline.ConversationCursorStore
import com.letta.mobile.data.timeline.NoOpConversationCursorStore
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.RunCursorStore
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.ui.a2ui.A2uiSurfaceRenderer
import com.letta.mobile.ui.a2ui.A2uiTestTags
import com.letta.mobile.ui.test.setLettaTestContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class A2uiToolApprovalRoundTripTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val openServers = mutableListOf<A2uiShimServer>()
    private val openTransports = mutableListOf<ChannelTransport>()

    /**
     * letta-mobile-likm4: pays this class's one-time initialisation cost **outside** any
     * `runTest` body.
     *
     * Whichever test JUnit happens to run first absorbs the first-touch cost of the Robolectric
     * sandbox, OkHttp/MockWebServer and kotlinx-serialization. Measured here: the first test in
     * the class takes ~8.5s on an idle host but ~56s under heavy contention, while every
     * subsequent test in the same class takes ~1s. That cost used to be incurred *inside* the
     * test body, where it counts against `runTest`'s wall-clock deadline — so under load the
     * first-executed test blew the deadline and failed as
     * `UncompletedCoroutinesError: After waiting for 1m, the test body did not run to completion`.
     * Nothing was deadlocked; the body was simply still doing real work. This also explains why
     * the reported victim moved between runs (#1031 blamed
     * helloResumeReplayedFramesStayInWireOrderBeforeLiveFrames, #1063 blamed
     * subscribeFrameWithMessageTypeOnlyReplaysAndAdvancesCursor): JUnit's method order decides
     * who runs first, and only the first test is exposed.
     *
     * `@Before` runs outside the `runTest` body, so doing a full connect/disconnect cycle here
     * moves that initialisation off the deadline entirely. It is bounded so a genuine transport
     * regression cannot hang the suite instead.
     */
    @Before
    fun warmUpTransportOutsideTestDeadline() {
        if (warmedUp) return
        warmedUp = true
        val server = A2uiShimServer()
        val transport = ChannelTransport(RunCursorStore.inMemory(), NoOpConversationCursorStore)
        try {
            runBlocking {
                withTimeout(WARM_UP_TIMEOUT) {
                    val connected = collectSubscribed {
                        transport.state.first { it is ChannelTransportState.Connected }
                    }
                    transport.connect(
                        baseShimUrl = server.baseUrl(),
                        token = "token",
                        deviceId = "device",
                        clientVersion = "test",
                    )
                    connected.await()
                    transport.disconnect()
                }
            }
        } finally {
            server.close()
        }
    }

    @After
    fun tearDown() {
        openTransports.forEach { it.disconnectForTest() }
        openServers.forEach(A2uiShimServer::close)
    }

    /**
     * letta-mobile-likm4: an explicit, coherent wall-clock budget for the test body.
     *
     * The per-interaction budget in this class is [TIMEOUT_MS] (15s) and a test can legitimately
     * chain three of them, so `runTest`'s 60s default was *smaller* than this class's own worst
     * case plus setup. That inversion is what turned a slow-but-healthy run into an opaque
     * "test body did not run to completion" instead of a precise
     * `TimeoutCancellationException` pointing at the exact await that stalled.
     *
     * This is deliberately not a way to let a hang pass: the inner [withRealTimeout] budgets are
     * unchanged and still fail fast at 15s. Raising only the outer deadline lets those inner
     * detectors do their job rather than being pre-empted by the harness.
     */
    private fun a2uiRunTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest(timeout = TEST_BODY_TIMEOUT, testBody = body)

    @Test
    fun toolApprovalOnceRoundTripsOverAdminShimWebSocket() = a2uiRunTest {
        val server = openServer()
        val transport = openTransport()
        val bridge = WsChatBridge(transport)
        val manager = A2uiSurfaceManager()
        connect(transport, bridge, server)

        val surfaceArrived = collectSubscribed {
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

        composeRule.onNodeWithTag(A2uiTestTags.TOOL_APPROVAL_CARD).assertIsDisplayed()
        composeRule.onNodeWithText("bash").assertIsDisplayed()
        composeRule.onNodeWithText("rm -rf /tmp/junk").assertIsDisplayed()
        assertTrue("ToolApprovalCard should render inside the 2s budget", elapsedMs(renderedAt) < 2_000)

        val confirmation = collectSubscribed {
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
    fun toolApprovalRequestIdPropagatesFromA2uiFrameForRestRouting() = a2uiRunTest {
        val server = openServer()
        val transport = openTransport()
        val bridge = WsChatBridge(transport)
        val manager = A2uiSurfaceManager()
        connect(transport, bridge, server)

        val surfaceArrived = collectSubscribed {
            bridge.a2uiEvents.first { event ->
                event.requestId == "approval-rest-1" &&
                    event.messages.any { it.surfaceId == SurfaceId }
            }
        }
        server.sendToolApprovalSurface(
            surfaceId = SurfaceId,
            callId = "call-rest",
            affordances = listOf("once"),
            requestId = "approval-rest-1",
        )
        manager.apply(withRealTimeout { surfaceArrived.await() })

        val actions = mutableListOf<com.letta.mobile.ui.a2ui.A2uiAction>()
        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiSurfaceRenderer(
                surface = manager.surface(SurfaceId),
                onAction = actions::add,
            )
        }

        composeRule.onNodeWithText("Once").performClick()

        composeRule.runOnIdle {
            val action = actions.single()
            assertEquals("approval-rest-1", action.context.stringValue("approvalRequestId"))
            assertEquals("approval-rest-1", action.raw.stringValue("approvalRequestId"))
            assertEquals("call-rest", action.context.stringValue("callId"))
        }
        assertNull(server.actions.receiveOrNullWithin())
    }

    @Test
    fun channelTransportTracksInFlightAndCancelPerConversation() = a2uiRunTest {
        val server = openServer()
        val transport = openTransport()
        val bridge = WsChatBridge(transport)
        connect(transport, bridge, server)
        withRealTimeout { server.frames.receiveOfType("hello") }

        assertTrue(bridge.send(agentId = "agent-e2e", conversationId = "conv-a", text = "a"))
        assertEquals(
            "second send for the same conversation must be rejected while the first turn is in flight",
            false,
            bridge.send(agentId = "agent-e2e", conversationId = "conv-a", text = "a again"),
        )
        assertTrue(bridge.send(agentId = "agent-e2e", conversationId = "conv-b", text = "b"))
        withRealTimeout { server.frames.receiveOfType("send_message") }
        withRealTimeout { server.frames.receiveOfType("send_message") }

        val convBStarted = collectSubscribed {
            bridge.events.first { event ->
                (event as? WsTimelineEvent.TurnStarted)?.conversationId == "conv-b"
            }
        }
        server.sendTurnStarted(conversationId = "conv-a", runId = "run-a", turnId = "turn-a")
        server.sendTurnStarted(conversationId = "conv-b", runId = "run-b", turnId = "turn-b")
        withRealTimeout { convBStarted.await() }

        assertTrue(bridge.cancel("conv-b"))
        val cancel = withRealTimeout { server.frames.receiveOfType("cancel") }
        assertEquals("run-b", cancel.stringValue("run_id"))
    }

    @Test
    fun reconnectResumeSendsSubscribeForPersistedRunCursor() = a2uiRunTest {
        val cursorStore = RunCursorStore.inMemory().apply {
            record("conv-resume", "run-resume", 7L)
        }
        val server = openServer()
        val transport = openTransport(cursorStore)
        val bridge = WsChatBridge(transport)

        connect(transport, bridge, server)

        val subscribe = withRealTimeout { server.frames.receiveOfType("subscribe") }
        assertEquals("run-resume", subscribe.stringValue("run_id"))
        assertEquals("7", subscribe["cursor"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun connectSendsHelloResumeForPersistedConversationCursors() = a2uiRunTest {
        val conversationCursorStore = FakeConversationCursorStore(
            "conv-resume-a" to 7L,
            "conv-resume-b" to 11L,
        )
        val server = openServer()
        val transport = openTransport(conversationCursorStore = conversationCursorStore)
        val bridge = WsChatBridge(transport)

        connect(transport, bridge, server)

        val hello = withRealTimeout { server.frames.receiveOfType("hello") }
        val resume = hello["resume"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("conv-resume-a", "conv-resume-b"), resume.map { it.stringValue("conv_id") })
        assertEquals(listOf("7", "11"), resume.map { it["after_seq"]!!.jsonPrimitive.content })
    }

    @Test
    fun connectOmitsHelloResumeForEmptyConversationCursorStore() = a2uiRunTest {
        val server = openServer()
        val transport = openTransport(conversationCursorStore = FakeConversationCursorStore())
        val bridge = WsChatBridge(transport)

        connect(transport, bridge, server)

        val hello = withRealTimeout { server.frames.receiveOfType("hello") }
        assertNull(hello["resume"])
        assertNull(server.frames.receiveOrNullWithin())
    }

    @Test
    fun helloResumeReplayedFramesStayInWireOrderBeforeLiveFrames() = a2uiRunTest {
        val conversationCursorStore = FakeConversationCursorStore("conv-resume" to 10L)
        val server = openServer()
        val transport = openTransport(conversationCursorStore = conversationCursorStore)
        val bridge = WsChatBridge(transport)
        connect(transport, bridge, server)

        // letta-mobile-likm4: supersedes the earlier onStart {} barrier, which was not sufficient —
        // onStart runs *before* merge() attaches its child collectors, so the first sendRaw could
        // still land in the gap and be dropped by the replay = 0 SharedFlow. collectSubscribed
        // makes the entire attach path synchronous instead.
        val observed = collectSubscribed {
            bridge.events
                .mapNotNull { event -> (event as? WsTimelineEvent.MessageDelta)?.message as? AssistantMessage }
                .map { it.content }
                .take(3)
                .toList()
        }

        server.sendRaw(
            """
            {"v":1,"type":"assistant_message","id":"cm-stream-replay-1","ts":"2026-05-17T00:00:04Z",
             "agent_id":"agent-e2e","conversation_id":"conv-resume","turn_id":"turn-resume","run_id":"run-resume",
             "content":"replay 1","seq":11}
            """.trimIndent()
        )
        server.sendRaw(
            """
            {"v":1,"type":"assistant_message","id":"cm-stream-replay-2","ts":"2026-05-17T00:00:05Z",
             "agent_id":"agent-e2e","conversation_id":"conv-resume","turn_id":"turn-resume","run_id":"run-resume",
             "content":"replay 2","seq":12}
            """.trimIndent()
        )
        server.sendRaw(
            """
            {"v":1,"type":"assistant_message","id":"cm-stream-live","ts":"2026-05-17T00:00:06Z",
             "agent_id":"agent-e2e","conversation_id":"conv-resume","turn_id":"turn-resume","run_id":"run-resume",
             "content":"live","seq":13}
            """.trimIndent()
        )

        assertEquals(listOf("replay 1", "replay 2", "live"), withRealTimeout { observed.await() })
    }

    @Test
    fun subscribeFrameWithMessageTypeOnlyReplaysAndAdvancesCursor() = a2uiRunTest {
        val cursorStore = RunCursorStore.inMemory().apply {
            record("conv-resume", "run-resume", 4L)
        }
        val server = openServer()
        val transport = openTransport(cursorStore)
        val bridge = WsChatBridge(transport)
        connect(transport, bridge, server)
        withRealTimeout { server.frames.receiveOfType("subscribe") }

        val replayed = collectSubscribed {
            bridge.events.first { event ->
                val message = (event as? WsTimelineEvent.MessageDelta)?.message as? AssistantMessage
                message?.content == "replayed delta"
            }
        }

        server.sendRaw(
            """
            {"v":1,"type":"subscribe_frame","id":"sub-env-1","ts":"2026-05-17T00:00:04Z",
             "run_id":"run-resume","seq":5,
             "frame":{"v":1,"message_type":"assistant_message","id":"cm-stream-replay","ts":"2026-05-17T00:00:04Z",
                      "agent_id":"agent-e2e","conversation_id":"conv-resume","turn_id":"turn-resume","run_id":"run-resume",
                      "content":"replayed delta"}}
            """.trimIndent()
        )

        withRealTimeout { replayed.await() }
        assertEquals(5L, cursorStore.activeRuns("conv-resume")["run-resume"])
    }

    @Test
    fun scheduleCatalogActionsRoundTripOverAdminShimWebSocket() = a2uiRunTest {
        val server = openServer()
        val transport = openTransport()
        val bridge = WsChatBridge(transport)
        val manager = A2uiSurfaceManager()
        connect(transport, bridge, server)

        val surfaceArrived = collectSubscribed {
            bridge.a2uiEvents.first { event ->
                event.messages.any { it.surfaceId == "schedule-surface" }
            }
        }
        server.sendScheduleSurface(surfaceId = "schedule-surface")
        manager.apply(withRealTimeout { surfaceArrived.await() })

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiSurfaceRenderer(
                surface = manager.surface("schedule-surface"),
                onAction = { bridge.sendA2uiAction(it) },
            )
        }

        composeRule.onNodeWithTag(A2uiTestTags.SCHEDULE_CARD).assertIsDisplayed()
        composeRule.onNodeWithText("Morning check-in").assertIsDisplayed()
        composeRule.onNodeWithTag(A2uiTestTags.SCHEDULE_SELECTOR_INPUT).assertIsDisplayed()

        composeRule.onAllNodes(hasText("Run now") and hasClickAction())[0].performClick()
        withRealTimeout { server.actions.receive() }.assertScheduleAction(
            surfaceId = "schedule-surface",
            name = "schedule.run_now",
            scheduleId = "sched-e2e",
        )
    }

    @Test
    fun allToolApprovalAffordancesEmitExpectedUserActions() = a2uiRunTest {
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
            val surfaceArrived = collectSubscribed {
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
    fun toolApprovalTimeoutEmitsUserAction() = a2uiRunTest {
        composeRule.mainClock.autoAdvance = false
        val server = openServer()
        val transport = openTransport()
        val bridge = WsChatBridge(transport)
        val manager = A2uiSurfaceManager()
        connect(transport, bridge, server)

        try {
            val surfaceArrived = collectSubscribed {
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
    fun queuedApprovalActionTriggersReconnectAndFlushes() = a2uiRunTest {
        val server = openServer()
        val transport = openTransport()
        val bridge = WsChatBridge(transport)
        val manager = A2uiSurfaceManager()
        connect(transport, bridge, server)

        val surfaceArrived = collectSubscribed {
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

        // letta-mobile-likm4: attach the state collector BEFORE closing the socket. The transport
        // auto-reconnects and StateFlow conflates, so a Disconnected -> Connecting transition that
        // completes before this collector attaches would be skipped entirely and first {} would
        // then wait out the full wall-clock budget for a state that has already gone by.
        val disconnected = collectSubscribed {
            bridge.state.first { it is ChannelTransportState.Disconnected }
        }
        server.closeActiveSocket()
        withRealTimeout { disconnected.await() }

        composeRule.onNodeWithText("Once").performClick()
        composeRule.onNodeWithText("Approved once").assertIsDisplayed()

        val action = withRealTimeout { server.actions.receive() }
        action.assertToolApprovalAction(
            surfaceId = "reconnect-surface",
            callId = "call-reconnect",
            decision = "approve",
            scope = "once",
        )
    }

    @Test
    fun toolApprovalWithoutSurfaceRunIdUsesActiveTurnRouting() = a2uiRunTest {
        val server = openServer()
        val transport = openTransport()
        val bridge = WsChatBridge(transport)
        val manager = A2uiSurfaceManager()
        connect(transport, bridge, server)

        server.sendTurnStarted()
        val surfaceArrived = collectSubscribed {
            bridge.a2uiEvents.first { event ->
                event.messages.any { it.surfaceId == "fallback-active-surface" }
            }
        }
        server.sendToolApprovalSurface(
            surfaceId = "fallback-active-surface",
            callId = "call-fallback-active",
            affordances = listOf("once"),
            includeRouting = false,
        )
        manager.apply(withRealTimeout { surfaceArrived.await() })

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiSurfaceRenderer(
                surface = manager.surface("fallback-active-surface"),
                onAction = { bridge.sendA2uiAction(it) },
            )
        }

        composeRule.onNodeWithText("Once").performClick()
        val action = withRealTimeout { server.actions.receive() }
        action.assertToolApprovalAction(
            surfaceId = "fallback-active-surface",
            callId = "call-fallback-active",
            decision = "approve",
            scope = "once",
        )
    }

    @Test
    fun toolApprovalWithoutSurfaceRunIdQueuesUntilTurnRoutingArrives() = a2uiRunTest {
        val server = openServer()
        val transport = openTransport()
        val bridge = WsChatBridge(transport)
        val manager = A2uiSurfaceManager()
        connect(transport, bridge, server)

        val surfaceArrived = collectSubscribed {
            bridge.a2uiEvents.first { event ->
                event.messages.any { it.surfaceId == "fallback-queued-surface" }
            }
        }
        server.sendToolApprovalSurface(
            surfaceId = "fallback-queued-surface",
            callId = "call-fallback-queued",
            affordances = listOf("once"),
            includeRouting = false,
        )
        manager.apply(withRealTimeout { surfaceArrived.await() })

        composeRule.setLettaTestContent(useChatTheme = false) {
            A2uiSurfaceRenderer(
                surface = manager.surface("fallback-queued-surface"),
                onAction = { bridge.sendA2uiAction(it) },
            )
        }

        composeRule.onNodeWithText("Once").performClick()
        composeRule.onNodeWithText("Approved once").assertIsDisplayed()
        assertTrue(server.actions.tryReceive().isFailure)

        server.sendTurnStarted()
        val action = withRealTimeout { server.actions.receive() }
        action.assertToolApprovalAction(
            surfaceId = "fallback-queued-surface",
            callId = "call-fallback-queued",
            decision = "approve",
            scope = "once",
        )
    }

    private fun openServer(): A2uiShimServer =
        A2uiShimServer().also(openServers::add)

    private fun openTransport(
        cursorStore: RunCursorStore = RunCursorStore.inMemory(),
        conversationCursorStore: ConversationCursorStore = NoOpConversationCursorStore,
    ): ChannelTransport =
        // letta-mobile-2rkdj: keep tests deterministic by using the
        // in-memory cursor store, optionally pre-seeded by resume tests.
        ChannelTransport(cursorStore, conversationCursorStore).also(openTransports::add)
}

private class FakeConversationCursorStore(
    vararg cursors: Pair<String, Long>,
) : ConversationCursorStore {
    private val highestByConversation = LinkedHashMap(cursors.toMap())

    override suspend fun recordFrame(conversationId: String, seq: Long) {
        highestByConversation[conversationId] = maxOf(highestByConversation[conversationId] ?: Long.MIN_VALUE, seq)
    }

    override suspend fun getCursor(conversationId: String): Long? =
        highestByConversation[conversationId]

    override suspend fun getAllCursors(): Map<String, Long> =
        highestByConversation.toMap()

    override suspend fun clearCursor(conversationId: String) {
        highestByConversation.remove(conversationId)
    }
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
    val connected = collectSubscribed {
        bridge.state.first { it is ChannelTransportState.Connected }
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
    assertEquals("user_action", stringValue("type"))
    assertEquals("tool_approval_response", stringValue("name"))
    assertEquals(surfaceId, stringValue("surface_id"))
    assertEquals("run-e2e", stringValue("run_id"))
    assertEquals("turn-e2e", stringValue("turn_id"))
    assertEquals(callId, stringValue("action_id"))
    val context = this["context"]!!.jsonObject
    assertEquals(callId, context.stringValue("callId"))
    assertEquals(decision, context.stringValue("decision"))
    assertEquals(scope, context.stringValue("scope"))
}

private fun JsonObject.assertScheduleAction(
    surfaceId: String,
    name: String,
    scheduleId: String?,
) {
    assertEquals("user_action", stringValue("type"))
    assertEquals(name, stringValue("name"))
    assertEquals(surfaceId, stringValue("surface_id"))
    assertEquals("run-e2e", stringValue("run_id"))
    assertEquals("turn-e2e", stringValue("turn_id"))
    val context = this["context"]!!.jsonObject
    if (scheduleId != null) {
        assertEquals(scheduleId, context.stringValue("id"))
    }
}

private fun JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: error("Missing string field $key")

private suspend fun Channel<JsonObject>.receiveOfType(type: String): JsonObject {
    while (true) {
        val frame = receive()
        if (frame.stringValue("type") == type) return frame
    }
}

private suspend fun Channel<JsonObject>.receiveOrNullWithin(timeoutMs: Long = 250L): JsonObject? =
    try {
        withRealTimeout { withTimeout(timeoutMs.milliseconds) { receive() } }
    } catch (_: TimeoutCancellationException) {
        null
    }

private fun elapsedMs(startNanos: Long): Long =
    (System.nanoTime() - startNanos) / 1_000_000

/**
 * letta-mobile-likm4: starts [block] as a collector that is attached to its upstream SharedFlow
 * **by the time this function returns**, so the caller can push server frames immediately
 * afterwards without racing.
 *
 * `ChannelTransport.events` / `.frameEvents` are `MutableSharedFlow(replay = 0)`: a frame emitted
 * before a collector attaches is dropped forever, so `first {}` / `take(n)` never completes and
 * the test burns its full wall-clock budget.
 *
 * `Dispatchers.IO` + `UNDISPATCHED` is NOT sufficient. `UNDISPATCHED` only makes *this* coroutine
 * start on the caller's thread; `WsChatBridge.events` is a cold `merge(...)`, and `merge` attaches
 * each source from an inner coroutine that must be *dispatched*. On an idle host that dispatch
 * wins the race, which is why this only ever failed under load — when the IO pool is saturated the
 * inner attach is queued behind other work, `async` returns un-attached, and the very next
 * `server.sendRaw(...)` is dropped. `onStart {}` is not a barrier either: it runs *before* `merge`
 * attaches its children.
 *
 * `Dispatchers.Unconfined` removes the dependency structurally — it never needs to dispatch, so
 * `merge`'s inner coroutines run eagerly on the caller's thread until they genuinely suspend,
 * which for a `SharedFlow` is *after* `collect` has allocated its slot. Combined with
 * `UNDISPATCHED` the whole attach path is synchronous, with no timing assumption anywhere.
 * (Plain cold chains such as `bridge.a2uiEvents` attach synchronously either way; routing every
 * collector through this helper keeps the guarantee uniform so the race cannot be reintroduced.)
 */
private fun <T> CoroutineScope.collectSubscribed(block: suspend () -> T): Deferred<T> =
    async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { block() }

private suspend fun <T> withRealTimeout(block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        withTimeout(TIMEOUT_MS.milliseconds) { block() }
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
    val frames = Channel<JsonObject>(Channel.UNLIMITED)
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
                            frames.trySend(obj)
                            when (obj.stringValue("type")) {
                                "hello" -> webSocket.send(welcomeFrame())
                                "user_action" -> {
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
        includeRouting: Boolean = true,
        requestId: String? = null,
    ) {
        (activeSocket ?: firstSocket.await()).send(
            toolApprovalFrame(
                surfaceId = surfaceId,
                callId = callId,
                affordances = affordances,
                timeoutSeconds = timeoutSeconds,
                includeRouting = includeRouting,
                requestId = requestId,
            )
        )
    }

    suspend fun sendScheduleSurface(surfaceId: String) {
        (activeSocket ?: firstSocket.await()).send(scheduleFrame(surfaceId))
    }

    suspend fun sendTurnStarted(
        conversationId: String = "conv-e2e",
        runId: String = "run-e2e",
        turnId: String = "turn-e2e",
    ) {
        (activeSocket ?: firstSocket.await()).send(
            """
            {"v":1,"type":"turn_started","id":"turn-started-e2e","ts":"2026-05-17T00:00:00Z",
             "agent_id":"agent-e2e","conversation_id":"$conversationId","turn_id":"$turnId","run_id":"$runId"}
            """.trimIndent()
        )
    }

    suspend fun sendRaw(frame: String) {
        (activeSocket ?: firstSocket.await()).send(frame)
    }

    fun closeActiveSocket() {
        activeSocket?.close(1001, "test reconnect")
    }

    fun close() {
        actions.close()
        frames.close()
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
        includeRouting: Boolean,
        requestId: String?,
    ): String {
        val affordancesJson = affordances.joinToString(prefix = "[", postfix = "]") { """"$it"""" }
        val routingFields = if (includeRouting) {
            "\"agent_id\":\"agent-e2e\",\"conversation_id\":\"conv-e2e\",\"turn_id\":\"turn-e2e\",\"run_id\":\"run-e2e\","
        } else {
            "\"agent_id\":\"agent-e2e\",\"conversation_id\":\"conv-e2e\","
        }
        val requestField = requestId?.let { "\"request_id\":\"$it\"," }.orEmpty()
        return """
            {"v":1,"type":"a2ui_frame","id":"a2ui-$surfaceId","ts":"2026-05-17T00:00:01Z",
             $routingFields
             $requestField
             "ok":true,
             "a2ui":[
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

    private fun scheduleFrame(surfaceId: String): String =
        """
            {"v":1,"type":"a2ui_frame","id":"a2ui-$surfaceId","ts":"2026-05-17T00:00:01Z",
             "agent_id":"agent-e2e","conversation_id":"conv-e2e","turn_id":"turn-e2e","run_id":"run-e2e",
             "ok":true,
             "a2ui":[
               {"version":"v0.9","createSurface":{
                 "surfaceId":"$surfaceId",
                 "catalogId":"com.letta.mobile:schedule/v1"
               }},
               {"version":"v0.9","updateComponents":{"surfaceId":"$surfaceId","root":"scheduleRoot","components":[
                 {"id":"scheduleRoot","component":"Column","children":["scheduleCard","selector"],"spacing":"sm"},
                 {"id":"scheduleCard","component":"ScheduleCard",
                  "scheduleId":"sched-e2e",
                  "name":"Morning check-in",
                  "agentName":"Ada",
                  "status":"active",
                  "summary":"Ask for a standup update",
                  "cronExpression":"0 8 * * *",
                  "nextScheduledTime":"2026-05-20T08:00:00Z"},
                 {"id":"selector","component":"ScheduleSelectorInput",
                  "label":{"literalString":"Schedule cadence"},
                  "value":{"path":"/draftSchedule"},
                  "agentId":{"literalString":"agent-e2e"},
                  "message":{"literalString":"Send me a digest"}}
               ]}},
               {"version":"v0.9","updateDataModel":{"surfaceId":"$surfaceId","path":"/draftSchedule","value":{"mode":"cron","value":"0 8 * * *"}}}
             ]}
        """.trimIndent()
}

private fun ChannelTransport.disconnectForTest() {
    kotlinx.coroutines.runBlocking {
        disconnect()
    }
}

// MockWebServer callbacks share constrained CI workers with the full Compose
// integration suite. Keep the interaction budgets above strict, but allow the
// transport callback enough wall-clock time to be scheduled under contention.
private const val TIMEOUT_MS = 15_000L

// letta-mobile-likm4: guards the one-time warm-up so only the first test in the
// class pays it, and it is paid from @Before rather than inside a runTest body.
private var warmedUp = false

private val WARM_UP_TIMEOUT = 60.seconds

// Must comfortably exceed the class's own worst case: setup plus the three
// chained TIMEOUT_MS (15s) interaction budgets a single test can legitimately
// use. The inner budgets remain the real failure detectors.
private val TEST_BODY_TIMEOUT = 5.minutes
