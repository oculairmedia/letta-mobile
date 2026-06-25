package com.letta.mobile.data.timeline

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class TimelineStateTransitionHandlerTest {

    @Test
    fun testApplyLocalSendAppend() = runTest {
        val state = MutableStateFlow(Timeline(conversationId = "c1"))
        val events = MutableSharedFlow<TimelineSyncEvent>(replay = 10)
        val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)
        val writeMutex = Mutex()

        val handler = TimelineStateTransitionHandler("c1", state, events, sendQueue, writeMutex)

        val pending = PendingSend("otid-1", "hello")
        val ack = CompletableDeferred<Unit>()
        val event = TimelineGatewayEvent.LocalSendAppend(pending, timelineNow(), ack)

        handler.applyLocalSendAppend(event)

        assertTrue(ack.isCompleted)
        assertEquals(1, state.value.events.size)
        val localEvent = state.value.events.first()
        assertIs<TimelineEvent.Local>(localEvent)
        assertEquals("otid-1", localEvent.otid)
        assertEquals("hello", localEvent.content)
        assertEquals(DeliveryState.SENDING, localEvent.deliveryState)

        val queued = sendQueue.receive()
        assertEquals(pending, queued)
    }

    @Test
    fun testApplyRetrySend_success() = runTest {
        val initialLocal = TimelineEvent.Local(1.0, "otid-2", "retry-me", Role.USER, timelineNow(), DeliveryState.FAILED)
        val state = MutableStateFlow(Timeline(conversationId = "c1", events = listOf(initialLocal).toTimelinePersistentList()))
        val events = MutableSharedFlow<TimelineSyncEvent>(replay = 10)
        val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)
        val writeMutex = Mutex()

        val handler = TimelineStateTransitionHandler("c1", state, events, sendQueue, writeMutex)

        val ack = CompletableDeferred<Unit>()
        val event = TimelineGatewayEvent.RetrySend("otid-2", ack)

        handler.applyRetrySend(event)

        assertTrue(ack.isCompleted)
        val localEvent = state.value.events.first() as TimelineEvent.Local
        assertEquals(DeliveryState.SENDING, localEvent.deliveryState)

        val queued = sendQueue.receive()
        assertEquals("otid-2", queued.otid)
        assertEquals("retry-me", queued.content)
    }

    @Test
    fun testApplyRetrySend_notFailed() = runTest {
        val initialLocal = TimelineEvent.Local(1.0, "otid-3", "sending", Role.USER, timelineNow(), DeliveryState.SENDING)
        val state = MutableStateFlow(Timeline(conversationId = "c1", events = listOf(initialLocal).toTimelinePersistentList()))
        val events = MutableSharedFlow<TimelineSyncEvent>(replay = 10)
        val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)
        val writeMutex = Mutex()

        val handler = TimelineStateTransitionHandler("c1", state, events, sendQueue, writeMutex)

        val ack = CompletableDeferred<Unit>()
        val event = TimelineGatewayEvent.RetrySend("otid-3", ack)

        handler.applyRetrySend(event)

        assertTrue(ack.isCompleted)
        val localEvent = state.value.events.first() as TimelineEvent.Local
        assertEquals(DeliveryState.SENDING, localEvent.deliveryState) // Unchanged
        
        assertTrue(sendQueue.isEmpty)
    }
    
    @Test
    fun testApplyRetrySend_notFound() = runTest {
        val state = MutableStateFlow(Timeline(conversationId = "c1"))
        val events = MutableSharedFlow<TimelineSyncEvent>(replay = 10)
        val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)
        val writeMutex = Mutex()

        val handler = TimelineStateTransitionHandler("c1", state, events, sendQueue, writeMutex)

        val ack = CompletableDeferred<Unit>()
        val event = TimelineGatewayEvent.RetrySend("otid-notfound", ack)

        handler.applyRetrySend(event)

        assertTrue(ack.isCompleted)
        assertEquals(0, state.value.events.size)
        assertTrue(sendQueue.isEmpty)
    }

    @Test
    fun testApplyMarkSent() = runTest {
        val initialLocal = TimelineEvent.Local(1.0, "otid-4", "msg", Role.USER, timelineNow(), DeliveryState.SENDING)
        val state = MutableStateFlow(Timeline(conversationId = "c1", events = listOf(initialLocal).toTimelinePersistentList()))
        val events = MutableSharedFlow<TimelineSyncEvent>(replay = 10)
        val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)
        val writeMutex = Mutex()

        val handler = TimelineStateTransitionHandler("c1", state, events, sendQueue, writeMutex)

        val ack = CompletableDeferred<Unit>()
        val event = TimelineGatewayEvent.MarkSent("otid-4", ack)

        handler.applyMarkSent(event)

        assertTrue(ack.isCompleted)
        val localEvent = state.value.events.first() as TimelineEvent.Local
        assertEquals(DeliveryState.SENT, localEvent.deliveryState)
    }
    
    @Test
    fun testApplyMarkSent_notFound() = runTest {
        val state = MutableStateFlow(Timeline(conversationId = "c1"))
        val events = MutableSharedFlow<TimelineSyncEvent>(replay = 10)
        val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)
        val writeMutex = Mutex()

        val handler = TimelineStateTransitionHandler("c1", state, events, sendQueue, writeMutex)

        val ack = CompletableDeferred<Unit>()
        val event = TimelineGatewayEvent.MarkSent("otid-notfound", ack)

        handler.applyMarkSent(event)

        assertTrue(ack.isCompleted)
    }

    @Test
    fun testApplyMarkFailed() = runTest {
        val initialLocal = TimelineEvent.Local(1.0, "otid-5", "msg", Role.USER, timelineNow(), DeliveryState.SENDING)
        val state = MutableStateFlow(Timeline(conversationId = "c1", events = listOf(initialLocal).toTimelinePersistentList()))
        val events = MutableSharedFlow<TimelineSyncEvent>(replay = 10)
        val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)
        val writeMutex = Mutex()

        val handler = TimelineStateTransitionHandler("c1", state, events, sendQueue, writeMutex)

        val ack = CompletableDeferred<Unit>()
        val event = TimelineGatewayEvent.MarkFailed("otid-5", ack)

        handler.applyMarkFailed(event)

        assertTrue(ack.isCompleted)
        val localEvent = state.value.events.first() as TimelineEvent.Local
        assertEquals(DeliveryState.FAILED, localEvent.deliveryState)
    }
    
    @Test
    fun testApplyMarkFailed_notFound() = runTest {
        val state = MutableStateFlow(Timeline(conversationId = "c1"))
        val events = MutableSharedFlow<TimelineSyncEvent>(replay = 10)
        val sendQueue = Channel<PendingSend>(Channel.UNLIMITED)
        val writeMutex = Mutex()

        val handler = TimelineStateTransitionHandler("c1", state, events, sendQueue, writeMutex)

        val ack = CompletableDeferred<Unit>()
        val event = TimelineGatewayEvent.MarkFailed("otid-notfound", ack)

        handler.applyMarkFailed(event)

        assertTrue(ack.isCompleted)
    }
}
