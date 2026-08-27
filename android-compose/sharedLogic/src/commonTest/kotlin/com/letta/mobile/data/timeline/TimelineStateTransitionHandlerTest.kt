package com.letta.mobile.data.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest

class TimelineStateTransitionHandlerTest {
    @Test
    fun testApplyLocalSendAppend() = runTest {
        val harness = harness(Timeline("c1"), backgroundScope)
        val pending = PendingSend("otid-1", "hello")
        val ack = CompletableDeferred<Unit>()

        harness.handler.applyLocalSendAppend(
            TimelineGatewayEvent.LocalSendAppend(pending, timelineNow(), ack),
        )

        assertTrue(ack.isCompleted)
        val local = assertIs<TimelineEvent.Local>(harness.state.value.events.single())
        assertEquals("otid-1", local.otid)
        assertEquals("hello", local.content)
        assertEquals(DeliveryState.SENDING, local.deliveryState)
        assertEquals(pending, harness.sendQueue.receive())
    }

    @Test
    fun testApplyRetrySend_success() = runTest {
        val local = TimelineEvent.Local(
            1.0,
            "otid-2",
            "retry-me",
            Role.USER,
            timelineNow(),
            DeliveryState.FAILED,
        )
        val harness = harness(Timeline("c1", listOf(local).toTimelinePersistentList()), backgroundScope)
        val ack = CompletableDeferred<Unit>()

        harness.handler.applyRetrySend(TimelineGatewayEvent.RetrySend("otid-2", ack))

        assertTrue(ack.isCompleted)
        assertEquals(
            DeliveryState.SENDING,
            (harness.state.value.events.single() as TimelineEvent.Local).deliveryState,
        )
        assertEquals(PendingSend("otid-2", "retry-me"), harness.sendQueue.receive())
    }

    @Test
    fun retryNoOpsDoNotQueueSend() = runTest {
        val sending = TimelineEvent.Local(
            1.0,
            "sending",
            "body",
            Role.USER,
            timelineNow(),
            DeliveryState.SENDING,
        )
        val harness = harness(Timeline("c1", listOf(sending).toTimelinePersistentList()), backgroundScope)

        harness.handler.applyRetrySend(
            TimelineGatewayEvent.RetrySend("sending", CompletableDeferred()),
        )
        harness.handler.applyRetrySend(
            TimelineGatewayEvent.RetrySend("missing", CompletableDeferred()),
        )

        assertTrue(harness.sendQueue.isEmpty)
        assertEquals(DeliveryState.SENDING, (harness.state.value.events.single() as TimelineEvent.Local).deliveryState)
    }

    @Test
    fun markSentAndFailedApplyInPlaceAndUnknownIsNoOp() = runTest {
        val local = TimelineEvent.Local(
            1.0,
            "otid",
            "body",
            Role.USER,
            timelineNow(),
            DeliveryState.SENDING,
        )
        val harness = harness(Timeline("c1", listOf(local).toTimelinePersistentList()), backgroundScope)

        harness.handler.applyMarkSent(
            TimelineGatewayEvent.MarkSent("otid", CompletableDeferred()),
        )
        assertEquals(DeliveryState.SENT, (harness.state.value.events.single() as TimelineEvent.Local).deliveryState)

        harness.handler.applyMarkFailed(
            TimelineGatewayEvent.MarkFailed("otid", CompletableDeferred()),
        )
        assertEquals(DeliveryState.FAILED, (harness.state.value.events.single() as TimelineEvent.Local).deliveryState)

        harness.handler.applyMarkSent(
            TimelineGatewayEvent.MarkSent("missing", CompletableDeferred()),
        )
        assertEquals(1, harness.state.value.events.size)
    }

    private fun harness(initial: Timeline, scope: CoroutineScope): HandlerHarness {
        val state = MutableStateFlow(initial)
        val events = MutableSharedFlow<TimelineSyncEvent>(replay = 10)
        val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)
        val mutex = Mutex()
        val processor = TimelineProcessor(
            initialState = TimelineReducerState(initial),
            scope = scope,
            writeMutex = mutex,
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
        return HandlerHarness(state, sendQueue, TimelineStateTransitionHandler("c1", processor))
    }

    private data class HandlerHarness(
        val state: MutableStateFlow<Timeline>,
        val sendQueue: Channel<PendingSend>,
        val handler: TimelineStateTransitionHandler,
    )
}
