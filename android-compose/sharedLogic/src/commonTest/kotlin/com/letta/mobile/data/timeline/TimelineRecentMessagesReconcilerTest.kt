package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome.Applied
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome.Skipped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineRecentMessagesReconcilerTest {
    @Test
    fun concurrentRecentReconcilesShareSingleMessageListCall() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        val reconciler = TimelineRecentMessagesReconciler(
            conversationId = "conv-1",
            messageApi = transport,
            eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED).also { queue ->
                backgroundScope.launch {
                    for (event in queue) {
                        if (event is TimelineGatewayEvent.RecentMessagesSnapshot) {
                            event.ack.complete(event.serverMessages.size)
                        }
                    }
                }
            },
            state = MutableStateFlow(Timeline("conv-1")),
            streamSubscriberActive = MutableStateFlow(false),
            writeMutex = Mutex(),
            applyReturnsAndResponsesFromSnapshot = {},
        )
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.onListEntered = { firstEntered.complete(Unit); release.await() }

        val first = async { reconciler.reconcileRecentMessages("first", forceRefresh = true) }
        firstEntered.await()
        val second = async { reconciler.reconcileRecentMessages("second", forceRefresh = true) }
        release.complete(Unit)
        awaitAll(first, second)

        assertEquals(1, transport.listCalls)
    }

    @Test
    fun forcedReconcileWithinDebounceWindowIsSkipped() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        var now = 0L
        val reconciler = TimelineRecentMessagesReconciler(
            conversationId = "conv-1",
            messageApi = transport,
            eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED).also { queue ->
                backgroundScope.launch {
                    for (event in queue) {
                        if (event is TimelineGatewayEvent.RecentMessagesSnapshot) {
                            event.ack.complete(event.serverMessages.size)
                        }
                    }
                }
            },
            state = MutableStateFlow(Timeline("conv-1")),
            streamSubscriberActive = MutableStateFlow(true),
            writeMutex = Mutex(),
            applyReturnsAndResponsesFromSnapshot = {},
            nowMillis = { now },
            minForcedReconcileIntervalMs = 4_000L,
        )

        assertEquals(Applied(1), reconciler.reconcileRecentMessages("post-send-750", forceRefresh = true))
        now += 2_500L
        assertEquals(Skipped("forcedReconcileDebounced"), reconciler.reconcileRecentMessages("post-send-2500", forceRefresh = true))
        now += 3_500L
        assertEquals(Applied(1), reconciler.reconcileRecentMessages("post-send-6000", forceRefresh = true))

        // Only the first and third calls fall outside the 4s debounce window from
        // the previous completed forced reconcile; the middle one (2.5s later) is
        // redundant while the stream is already active and gets skipped.
        assertEquals(2, transport.listCalls)
    }

    @Test
    fun newConnectionGenerationBypassesPriorForcedReconcileDebounce() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        var now = 0L
        val reconciler = TimelineRecentMessagesReconciler(
            conversationId = "conv-1",
            messageApi = transport,
            eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED).also { queue ->
                backgroundScope.launch {
                    for (event in queue) {
                        if (event is TimelineGatewayEvent.RecentMessagesSnapshot) {
                            event.ack.complete(event.serverMessages.size)
                        }
                    }
                }
            },
            state = MutableStateFlow(Timeline("conv-1")),
            streamSubscriberActive = MutableStateFlow(true),
            writeMutex = Mutex(),
            applyReturnsAndResponsesFromSnapshot = {},
            nowMillis = { now },
            minForcedReconcileIntervalMs = 4_000L,
        )

        assertEquals(Applied(1), reconciler.reconcileRecentMessages("post-send", forceRefresh = true, connectionGeneration = 1L))
        now += 1_000L
        assertEquals(Applied(1), reconciler.reconcileRecentMessages("redial-recovery", forceRefresh = true, connectionGeneration = 2L))
        assertEquals(Skipped("forcedReconcileDebounced"), reconciler.reconcileRecentMessages("duplicate", forceRefresh = true, connectionGeneration = 2L))

        assertEquals(2, transport.listCalls)
    }

    @Test
    fun forcedReconcileSlowerThanTheDebounceWindowStillDebouncesTheNextCall() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        var now = 0L
        val reconciler = TimelineRecentMessagesReconciler(
            conversationId = "conv-1",
            messageApi = transport,
            eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED).also { queue ->
                backgroundScope.launch {
                    for (event in queue) {
                        if (event is TimelineGatewayEvent.RecentMessagesSnapshot) {
                            event.ack.complete(event.serverMessages.size)
                        }
                    }
                }
            },
            state = MutableStateFlow(Timeline("conv-1")),
            streamSubscriberActive = MutableStateFlow(true),
            writeMutex = Mutex(),
            applyReturnsAndResponsesFromSnapshot = {},
            nowMillis = { now },
            minForcedReconcileIntervalMs = 4_000L,
        )
        // Simulate a reconcile round trip that itself takes longer than the
        // debounce window (5s network call vs a 4s window).
        transport.onListEntered = { now += 5_000L }

        reconciler.reconcileRecentMessages("post-send-750", forceRefresh = true)
        // No additional time has passed since the slow call completed.
        reconciler.reconcileRecentMessages("post-send-2500", forceRefresh = true)

        // The debounce timestamp must be stamped with the clock AFTER the round
        // trip completes, not before it started — otherwise a reconcile slower
        // than the window makes the debounce a no-op for exactly the calls it
        // matters most for (letta-mobile fix/debounce-forced-reconcile review).
        assertEquals(1, transport.listCalls)
    }

    @Test
    fun forcedReconcileStillRunsWhenStreamIsNotActive() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        var now = 0L
        val reconciler = TimelineRecentMessagesReconciler(
            conversationId = "conv-1",
            messageApi = transport,
            eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED).also { queue ->
                backgroundScope.launch {
                    for (event in queue) {
                        if (event is TimelineGatewayEvent.RecentMessagesSnapshot) {
                            event.ack.complete(event.serverMessages.size)
                        }
                    }
                }
            },
            state = MutableStateFlow(Timeline("conv-1")),
            streamSubscriberActive = MutableStateFlow(false),
            writeMutex = Mutex(),
            applyReturnsAndResponsesFromSnapshot = {},
            nowMillis = { now },
            minForcedReconcileIntervalMs = 4_000L,
        )

        reconciler.reconcileRecentMessages("redial-recovery", forceRefresh = true)
        now += 100L
        reconciler.reconcileRecentMessages("redial-recovery", forceRefresh = true)

        // The debounce only guards the "bypassing an active stream" path; when
        // there's no live stream subscriber every call is already load-bearing.
        assertEquals(2, transport.listCalls)
    }

    @Test
    fun openAndResumedOverlapShareSingleNetworkFlightAndResultApplication() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        val reconciler = TimelineRecentMessagesReconciler(
            conversationId = "conv-1",
            messageApi = transport,
            eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED).also { queue ->
                backgroundScope.launch {
                    for (event in queue) {
                        if (event is TimelineGatewayEvent.RecentMessagesSnapshot) {
                            event.ack.complete(event.serverMessages.size)
                        }
                    }
                }
            },
            state = MutableStateFlow(Timeline("conv-1")),
            streamSubscriberActive = MutableStateFlow(false),
            writeMutex = Mutex(),
            applyReturnsAndResponsesFromSnapshot = {},
        )
        val openEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.onListEntered = { openEntered.complete(Unit); release.await() }

        val openFlight = async { reconciler.reconcileRecentMessages("open", forceRefresh = false, connectionGeneration = 1L) }
        openEntered.await()
        val resumeFlight = async { reconciler.reconcileRecentMessages("screen_resumed", forceRefresh = false, connectionGeneration = 1L) }
        release.complete(Unit)

        val results = awaitAll(openFlight, resumeFlight)
        assertEquals(1, transport.listCalls)
        assertEquals(Applied(1), results[0])
        assertEquals(Applied(1), results[1])
    }

    @Test
    fun screenResumedAfterSuccessfulOpenWithinFreshnessWindowIsCoalesced() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        var now = 1_000L
        val reconciler = TimelineRecentMessagesReconciler(
            conversationId = "conv-1",
            messageApi = transport,
            eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED).also { queue ->
                backgroundScope.launch {
                    for (event in queue) {
                        if (event is TimelineGatewayEvent.RecentMessagesSnapshot) {
                            event.ack.complete(event.serverMessages.size)
                        }
                    }
                }
            },
            state = MutableStateFlow(Timeline("conv-1")),
            streamSubscriberActive = MutableStateFlow(false),
            writeMutex = Mutex(),
            applyReturnsAndResponsesFromSnapshot = {},
            nowMillis = { now },
        )

        // 1. Open completes
        assertEquals(Applied(1), reconciler.reconcileRecentMessages("open", forceRefresh = false, connectionGeneration = 1L))
        assertEquals(1, transport.listCalls)

        // 2. screen_resumed arrives 240ms later with no invalidation -> coalesced without network call
        now += 240L
        assertEquals(Applied(1), reconciler.reconcileRecentMessages("screen_resumed", forceRefresh = false, connectionGeneration = 1L))
        assertEquals(1, transport.listCalls)

        // 3. Invalidation occurs -> next screen_resumed fetches fresh
        reconciler.invalidateFreshness()
        assertEquals(Applied(1), reconciler.reconcileRecentMessages("screen_resumed", forceRefresh = false, connectionGeneration = 1L))
        assertEquals(2, transport.listCalls)
    }

    @Test
    fun reconnectGenerationChangeSupersedesPriorFlight() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        val reconciler = TimelineRecentMessagesReconciler(
            conversationId = "conv-1",
            messageApi = transport,
            eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED).also { queue ->
                backgroundScope.launch {
                    for (event in queue) {
                        if (event is TimelineGatewayEvent.RecentMessagesSnapshot) {
                            event.ack.complete(event.serverMessages.size)
                        }
                    }
                }
            },
            state = MutableStateFlow(Timeline("conv-1")),
            streamSubscriberActive = MutableStateFlow(false),
            writeMutex = Mutex(),
            applyReturnsAndResponsesFromSnapshot = {},
        )
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.onListEntered = {
            if (!firstEntered.isCompleted) firstEntered.complete(Unit)
            release.await()
        }

        val gen1Flight = async { reconciler.reconcileRecentMessages("open", forceRefresh = false, connectionGeneration = 1L) }
        firstEntered.await()

        // Reconnect with gen 2
        val gen2Flight = async { reconciler.reconcileRecentMessages("reconnect", forceRefresh = false, connectionGeneration = 2L) }
        release.complete(Unit)

        awaitAll(gen1Flight, gen2Flight)
        assertEquals(2, transport.listCalls)
    }

    @Test
    fun openFailureReleasesClaimAndAllowsSubsequentRetry() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        var shouldFail = true
        transport.onListEntered = {
            if (shouldFail) throw IllegalStateException("Network drop")
        }
        val reconciler = TimelineRecentMessagesReconciler(
            conversationId = "conv-1",
            messageApi = transport,
            eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED).also { queue ->
                backgroundScope.launch {
                    for (event in queue) {
                        if (event is TimelineGatewayEvent.RecentMessagesSnapshot) {
                            event.ack.complete(event.serverMessages.size)
                        }
                    }
                }
            },
            state = MutableStateFlow(Timeline("conv-1")),
            streamSubscriberActive = MutableStateFlow(false),
            writeMutex = Mutex(),
            applyReturnsAndResponsesFromSnapshot = {},
        )

        val outcome1 = reconciler.reconcileRecentMessages("open", forceRefresh = false, connectionGeneration = 1L)
        assertTrue(outcome1 is RecentMessagesReconcileOutcome.Failed)

        // Retry succeeds and is not poisoned
        shouldFail = false
        val outcome2 = reconciler.reconcileRecentMessages("screen_resumed", forceRefresh = false, connectionGeneration = 1L)
        assertEquals(Applied(1), outcome2)
    }

    private class RecordingTimelineTransport : TimelineTransport {
        var listCalls = 0
        var onListEntered: suspend () -> Unit = {}
        override suspend fun sendConversationMessage(conversationId: String, request: MessageCreateRequest): Flow<LettaMessage> = emptyFlow()
        override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = emptyFlow()
        override suspend fun listConversationMessages(conversationId: String, limit: Int?, after: String?, order: String?): List<LettaMessage> {
            listCalls += 1
            onListEntered()
            return listOf(UserMessage(id = "m-1", contentRaw = JsonPrimitive("hello")))
        }
        override suspend fun listAgentMessages(agentId: String, limit: Int?, order: String?, conversationId: String?): List<LettaMessage> = emptyList()
    }
}
