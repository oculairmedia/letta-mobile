package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
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
    fun `TimelineReturnsResponsesProcessor handles identified and blank tool returns`() {
        val identifiedState = toolCallState(
            serverId = "tc-1",
            toolCall = com.letta.mobile.data.model.ToolCall(
                id = "call-id-1",
                name = "test_tool",
                arguments = "",
            ),
        )
        applyReturnsAndResponsesFromSnapshot(
            listOf(
                ToolReturnMessage(
                    id = "tr-1",
                    toolCallId = "call-id-1",
                    toolReturnRaw = JsonPrimitive("success_response"),
                    isErr = false,
                    runId = "run-1",
                ),
            ),
            identifiedState,
        )
        val updated = identifiedState.value.events.single() as TimelineEvent.Confirmed
        assertTrue(updated.approvalDecided)
        assertEquals("success_response", updated.toolReturnContent)
        assertEquals("success_response", updated.toolReturnContentByCallId["call-id-1"])

        val blankState = toolCallState(
            serverId = "tc-blank",
            toolCall = com.letta.mobile.data.model.ToolCall(
                name = "synthetic_tool",
                arguments = "",
            ),
        )
        applyReturnsAndResponsesFromSnapshot(
            listOf(
                ToolReturnMessage(
                    id = "tr-blank",
                    toolCallId = "",
                    toolReturnRaw = JsonPrimitive("should_not_attach"),
                    isErr = true,
                    status = "error",
                ),
            ),
            blankState,
        )
        val unchanged = blankState.value.events.single() as TimelineEvent.Confirmed
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
        val processor = timelineProcessor(state, events, sendQueue, writeMutex)
        val handler = TimelineStateTransitionHandler("conv1", processor)

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
        processor.closeAndJoin()
    }

    @Test
    fun `TimelineExternalTransportAppender appends external messages`() = runTest {
        val state = MutableStateFlow(Timeline("conv1"))
        val events = MutableSharedFlow<TimelineSyncEvent>(extraBufferCapacity = 8)
        val eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED)
        val writeMutex = Mutex()
        val pendingLocalStore = NoOpPendingLocalStore
        val processor = timelineProcessor(state, events, Channel(Channel.UNLIMITED), writeMutex)
        val appender = TimelineExternalTransportAppender(
            conversationId = "conv1",
            messageApi = mockk(),
            eventQueue = eventQueue,
            events = events,
            processor = processor,
            pendingLocalStore = pendingLocalStore,
            submitReconcileAfterSendSnapshot = { _, _ -> mockk() }
        )

        val ack = CompletableDeferred<String>()
        appender.applyExternalTransportLocalAppend(
            TimelineGatewayEvent.ExternalTransportLocalAppend(
                content = "external msg",
                otid = "ext-otid-1",
                attachments = persistentListOf(),
                sentAt = Instant.now(),
                ack = ack
            )
        )
        assertTrue(ack.isCompleted)
        assertEquals("ext-otid-1", ack.await())
        val local = state.value.events.single() as TimelineEvent.Local
        assertEquals("external msg", local.content)
        assertEquals(MessageSource.LETTA_SERVER, local.source)
        processor.closeAndJoin()
    }

    private fun toolCallState(
        serverId: String,
        toolCall: com.letta.mobile.data.model.ToolCall,
    ): MutableStateFlow<Timeline> = MutableStateFlow(
        Timeline("conv1").append(
            TimelineEvent.Confirmed(
                position = 1.0,
                otid = "otid-tc",
                content = "",
                serverId = serverId,
                messageType = TimelineMessageType.TOOL_CALL,
                date = Instant.now(),
                runId = "run-1",
                stepId = "step-1",
                toolCalls = persistentListOf(toolCall),
                approvalRequestId = "req-1",
            ),
        ),
    )

    private fun CoroutineScope.timelineProcessor(
        state: MutableStateFlow<Timeline>,
        events: MutableSharedFlow<TimelineSyncEvent>,
        sendQueue: Channel<PendingSend>,
        writeMutex: Mutex,
    ) = TimelineProcessor(
        initialState = TimelineReducerState(state.value),
        scope = this,
        writeMutex = writeMutex,
        stateBridge = object : TimelineProcessorStateBridge {
            override fun synchronizeSeed(processorState: TimelineReducerState) =
                processorState.copy(timeline = state.value)

            override fun publish(stateValue: TimelineReducerState) {
                state.value = stateValue.timeline
            }
        },
        effectHandler = { effect ->
            when (effect) {
                is TimelineReductionEffect.Send -> sendQueue.send(effect.pending)
                is TimelineReductionEffect.EmitSyncEvent -> events.emit(effect.event)
                else -> Unit
            }
        },
    )

    @Test
    fun `TimelineRecentMessagesReconciler merges snapshot correctly`() = runTest {
        val state = MutableStateFlow(Timeline("conv1"))
        val eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED)
        val processor = TimelineProcessor(
            initialState = TimelineReducerState(state.value),
            scope = this,
            stateBridge = object : TimelineProcessorStateBridge {
                override fun synchronizeSeed(processorState: TimelineReducerState) =
                    processorState.copy(timeline = state.value)

                override fun publish(stateValue: TimelineReducerState) {
                    state.value = stateValue.timeline
                }
            },
        )
        val reconciler = TimelineRecentMessagesReconciler(
            conversationId = "conv1",
            scope = this,
            messageApi = mockk(),
            eventQueue = eventQueue,
            state = state,
            streamSubscriberActive = MutableStateFlow(false),
            processor = processor,
            onSnapshotApplied = {},
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
                telemetryReason = "test",
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
