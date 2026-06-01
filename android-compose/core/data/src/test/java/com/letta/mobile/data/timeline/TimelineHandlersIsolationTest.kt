package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineHandlersIsolationTest {

    @Test
    fun `TimelineWsSubscription tracks active transport state`() {
        val subscription = TimelineWsSubscription("conv1")
        assertFalse(subscription.isActive())

        subscription.markActive()
        assertTrue(subscription.isActive())

        subscription.clear()
        assertFalse(subscription.isActive())
    }

    @Test
    fun `TimelineReturnsResponsesProcessor updates approval status and tool returns`() {
        val initialTimeline = Timeline("conv1")
            .append(
                TimelineEvent.Confirmed(
                    position = 1.0,
                    otid = "otid-tc",
                    content = "",
                    serverId = "tc-1",
                    messageType = TimelineMessageType.TOOL_CALL,
                    date = Instant.now(),
                    runId = "run-1",
                    stepId = "step-1",
                    toolCalls = listOf(
                        com.letta.mobile.data.model.ToolCall(
                            id = "call-id-1",
                            name = "test_tool",
                            arguments = ""
                        )
                    ),
                    approvalRequestId = "req-1"
                )
            )

        val state = MutableStateFlow(initialTimeline)
        val snapshot = listOf(
            ToolReturnMessage(
                id = "tr-1",
                toolCallId = "call-id-1",
                toolReturnRaw = JsonPrimitive("success_response"),
                isErr = false
            )
        )

        applyReturnsAndResponsesFromSnapshot(snapshot, state)

        val updated = state.value.events.single() as TimelineEvent.Confirmed
        assertTrue(updated.approvalDecided)
        assertEquals("success_response", updated.toolReturnContent)
        assertEquals("success_response", updated.toolReturnContentByCallId["call-id-1"])
    }

    @Test
    fun `TimelineReturnsResponsesProcessor ignores blank tool return ids`() {
        val initialTimeline = Timeline("conv1")
            .append(
                TimelineEvent.Confirmed(
                    position = 1.0,
                    otid = "otid-tc",
                    content = "",
                    serverId = "tc-blank",
                    messageType = TimelineMessageType.TOOL_CALL,
                    date = Instant.now(),
                    runId = "run-1",
                    stepId = "step-1",
                    toolCalls = listOf(
                        com.letta.mobile.data.model.ToolCall(
                            name = "synthetic_tool",
                            arguments = ""
                        )
                    ),
                    approvalRequestId = "req-1"
                )
            )

        val state = MutableStateFlow(initialTimeline)
        val snapshot = listOf(
            ToolReturnMessage(
                id = "tr-blank",
                toolCallId = "",
                toolReturnRaw = JsonPrimitive("should_not_attach"),
                isErr = true,
                status = "error"
            )
        )

        applyReturnsAndResponsesFromSnapshot(snapshot, state)

        val unchanged = state.value.events.single() as TimelineEvent.Confirmed
        assertFalse(unchanged.approvalDecided)
        assertEquals(null, unchanged.toolReturnContent)
        assertTrue(unchanged.toolReturnContentByCallId.isEmpty())
    }

    @Test
    fun `TimelineStateTransitionHandler transitions local event states`() = runTest {
        val state = MutableStateFlow(Timeline("conv1"))
        val events = MutableSharedFlow<TimelineSyncEvent>(extraBufferCapacity = 8)
        val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)
        val writeMutex = Mutex()
        val handler = TimelineStateTransitionHandler("conv1", state, events, sendQueue, writeMutex)

        // 1. Local Append
        val pending = PendingSend("otid-1", "hello")
        val appendAck = CompletableDeferred<Unit>()
        handler.applyLocalSendAppend(
            TimelineGatewayEvent.LocalSendAppend(pending, Instant.now(), appendAck)
        )
        assertTrue(appendAck.isCompleted)
        val local = state.value.events.single() as TimelineEvent.Local
        assertEquals("otid-1", local.otid)
        assertEquals("hello", local.content)
        assertEquals(DeliveryState.SENDING, local.deliveryState)

        // 2. Mark Sent
        val sentAck = CompletableDeferred<Unit>()
        handler.applyMarkSent(TimelineGatewayEvent.MarkSent("otid-1", sentAck))
        assertTrue(sentAck.isCompleted)
        val sent = state.value.events.single() as TimelineEvent.Local
        assertEquals(DeliveryState.SENT, sent.deliveryState)

        // 3. Mark Failed
        val failedAck = CompletableDeferred<Unit>()
        handler.applyMarkFailed(TimelineGatewayEvent.MarkFailed("otid-1", failedAck))
        assertTrue(failedAck.isCompleted)
        val failed = state.value.events.single() as TimelineEvent.Local
        assertEquals(DeliveryState.FAILED, failed.deliveryState)
    }

    @Test
    fun `TimelineExternalTransportAppender appends external messages`() = runTest {
        val state = MutableStateFlow(Timeline("conv1"))
        val events = MutableSharedFlow<TimelineSyncEvent>(extraBufferCapacity = 8)
        val eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED)
        val writeMutex = Mutex()
        val pendingLocalStore = NoOpPendingLocalStore
        val appender = TimelineExternalTransportAppender(
            conversationId = "conv1",
            messageApi = mockk(),
            eventQueue = eventQueue,
            state = state,
            events = events,
            writeMutex = writeMutex,
            pendingLocalStore = pendingLocalStore,
            submitReconcileAfterSendSnapshot = { _, _ -> mockk() }
        )

        val ack = CompletableDeferred<String>()
        appender.applyExternalTransportLocalAppend(
            TimelineGatewayEvent.ExternalTransportLocalAppend(
                content = "external msg",
                otid = "ext-otid-1",
                attachments = emptyList(),
                sentAt = Instant.now(),
                ack = ack
            )
        )
        assertTrue(ack.isCompleted)
        assertEquals("ext-otid-1", ack.await())
        val local = state.value.events.single() as TimelineEvent.Local
        assertEquals("external msg", local.content)
        assertEquals(MessageSource.LETTA_SERVER, local.source)
    }

    @Test
    fun `TimelineRecentMessagesReconciler merges snapshot correctly`() = runTest {
        val state = MutableStateFlow(Timeline("conv1"))
        val eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED)
        val writeMutex = Mutex()
        val reconciler = TimelineRecentMessagesReconciler(
            conversationId = "conv1",
            messageApi = mockk(),
            eventQueue = eventQueue,
            state = state,
            streamSubscriberActive = MutableStateFlow(false),
            writeMutex = writeMutex,
            applyReturnsAndResponsesFromSnapshot = {}
        )

        val serverMsgs = listOf(
            AssistantMessage(
                id = "server-1",
                contentRaw = JsonPrimitive("hi from server"),
                date = "2026-05-31T20:00:00Z"
            )
        )

        val ack = CompletableDeferred<Int>()
        reconciler.applyRecentMessagesSnapshot(
            TimelineGatewayEvent.RecentMessagesSnapshot(
                serverMessages = serverMsgs,
                telemetryName = "test",
                telemetryAttrs = emptyList(),
                ack = ack
            )
        )

        assertTrue(ack.isCompleted)
        assertEquals(1, ack.await())
        val confirmed = state.value.events.single() as TimelineEvent.Confirmed
        assertEquals("server-1", confirmed.serverId)
        assertEquals("hi from server", confirmed.content)
    }
}
