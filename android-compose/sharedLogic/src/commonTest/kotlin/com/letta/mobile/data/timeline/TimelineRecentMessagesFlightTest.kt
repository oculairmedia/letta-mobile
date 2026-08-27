package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome.Applied
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineRecentMessagesFlightTest {
    @Test
    fun openAndResumedOverlapShareSingleNetworkFlightAndResultApplication() = runTest(UnconfinedTestDispatcher()) {
        val fixture = ReconcileFixture(backgroundScope)
        val openEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.transport.onListEntered = {
            openEntered.complete(Unit)
            release.await()
        }

        val openFlight = async { fixture.reconciler.reconcileRecentMessages("open", connectionGeneration = 1L) }
        openEntered.await()
        val resumeFlight = async {
            fixture.reconciler.reconcileRecentMessages("screen_resumed", connectionGeneration = 1L)
        }
        release.complete(Unit)

        val results = awaitAll(openFlight, resumeFlight)
        assertEquals(1, fixture.transport.listCalls)
        assertEquals(listOf(Applied(1), Applied(1)), results)
    }

    @Test
    fun cancellingFirstCallerDoesNotCancelSharedFlight() = runTest(UnconfinedTestDispatcher()) {
        val fixture = ReconcileFixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.transport.onListEntered = {
            entered.complete(Unit)
            release.await()
        }

        val firstCaller = async { fixture.reconciler.reconcileRecentMessages("open") }
        entered.await()
        val coalescedCaller = async { fixture.reconciler.reconcileRecentMessages("screen_resumed") }
        firstCaller.cancelAndJoin()
        release.complete(Unit)

        assertEquals(Applied(1), coalescedCaller.await())
        assertEquals(1, fixture.transport.listCalls)
    }

    @Test
    fun screenResumedAfterSuccessfulOpenWithinFreshnessWindowIsCoalesced() = runTest(UnconfinedTestDispatcher()) {
        var now = 1_000L
        val fixture = ReconcileFixture(backgroundScope, nowMillis = { now })

        assertEquals(Applied(1), fixture.reconciler.reconcileRecentMessages("open", connectionGeneration = 1L))
        now += 240L
        assertEquals(
            Applied(1),
            fixture.reconciler.reconcileRecentMessages("screen_resumed", connectionGeneration = 1L),
        )
        assertEquals(1, fixture.transport.listCalls)

        fixture.reconciler.invalidateFreshness()
        assertEquals(
            Applied(0),
            fixture.reconciler.reconcileRecentMessages("screen_resumed", connectionGeneration = 1L),
        )
        assertEquals(2, fixture.transport.listCalls)
    }

    @Test
    fun reconnectGenerationChangeSerializesBeforeNewGenerationApplies() = runTest(UnconfinedTestDispatcher()) {
        val fixture = ReconcileFixture(backgroundScope)
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.transport.onListEntered = {
            firstEntered.complete(Unit)
            release.await()
        }

        val generationOne = async {
            fixture.reconciler.reconcileRecentMessages("open", connectionGeneration = 1L)
        }
        firstEntered.await()
        val generationTwo = async {
            fixture.reconciler.reconcileRecentMessages("reconnect", connectionGeneration = 2L)
        }
        assertEquals(1, fixture.transport.listCalls)
        release.complete(Unit)

        val outcomes = awaitAll(generationOne, generationTwo)
        assertTrue(outcomes[0] is RecentMessagesReconcileOutcome.Skipped)
        assertEquals(Applied(1), outcomes[1])
        assertEquals(2, fixture.transport.listCalls)
        assertEquals(listOf(2L), fixture.appliedGenerations)
    }

    @Test
    fun strongerRequestWaitsForItsOwnTrailingFlight() = runTest(UnconfinedTestDispatcher()) {
        val fixture = ReconcileFixture(backgroundScope)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        fixture.transport.onListEntered = {
            when (fixture.transport.listCalls) {
                1 -> {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
                2 -> {
                    secondEntered.complete(Unit)
                    releaseSecond.await()
                }
            }
        }

        val weak = async { fixture.reconciler.reconcileRecentMessages("open") }
        firstEntered.await()
        val strong = async { fixture.reconciler.reconcileRecentMessages("pull-to-refresh", forceRefresh = true) }
        releaseFirst.complete(Unit)
        secondEntered.await()

        assertTrue(weak.isCompleted)
        assertFalse(strong.isCompleted)
        releaseSecond.complete(Unit)
        assertEquals(Applied(0), strong.await())
        assertEquals(2, fixture.transport.listCalls)
    }

    @Test
    fun mutationDuringReconcileInvalidatesItsFreshnessClaim() = runTest(UnconfinedTestDispatcher()) {
        val fixture = ReconcileFixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.transport.onListEntered = {
            entered.complete(Unit)
            release.await()
        }

        val open = async { fixture.reconciler.reconcileRecentMessages("open", connectionGeneration = 1L) }
        entered.await()
        fixture.reconciler.invalidateFreshness()
        release.complete(Unit)
        open.await()
        fixture.transport.onListEntered = {}

        assertEquals(
            Applied(0),
            fixture.reconciler.reconcileRecentMessages("screen_resumed", connectionGeneration = 1L),
        )
        assertEquals(2, fixture.transport.listCalls)
    }

    @Test
    fun identicalResponseDoesNotMarkSnapshotDirtyTwice() = runTest(UnconfinedTestDispatcher()) {
        val fixture = ReconcileFixture(backgroundScope)
        fixture.transport.messageIdProvider = { "stable-message" }

        fixture.reconciler.reconcileRecentMessages("open")
        fixture.reconciler.invalidateFreshness()
        fixture.reconciler.reconcileRecentMessages("manual-refresh", forceRefresh = true)

        assertEquals(2, fixture.transport.listCalls)
        assertEquals(1, fixture.snapshotAppliedCount)
    }

    @Test
    fun generationlessExternalReconcileUsesHighestAppliedGeneration() = runTest(UnconfinedTestDispatcher()) {
        val fixture = ReconcileFixture(backgroundScope)

        assertEquals(Applied(1), fixture.reconciler.reconcileRecentMessages("open", connectionGeneration = 1L))
        fixture.transport.messageIdProvider = { "external-message" }
        fixture.transport.contentProvider = { "external hello" }
        fixture.reconciler.invalidateFreshness()

        assertEquals(
            Applied(1),
            fixture.reconciler.reconcileRecentMessages("external-run", forceRefresh = true),
        )
        assertEquals(
            "external-message",
            (fixture.processor.state.value.timeline.events.last() as TimelineEvent.Confirmed).serverId,
        )
    }

    @Test
    fun openFailureReleasesClaimAndAllowsSubsequentRetry() = runTest(UnconfinedTestDispatcher()) {
        val fixture = ReconcileFixture(backgroundScope)
        var shouldFail = true
        fixture.transport.onListEntered = {
            if (shouldFail) error("Network drop")
        }

        val first = fixture.reconciler.reconcileRecentMessages("open", connectionGeneration = 1L)
        assertTrue(first is RecentMessagesReconcileOutcome.Failed)

        shouldFail = false
        assertEquals(
            Applied(1),
            fixture.reconciler.reconcileRecentMessages("screen_resumed", connectionGeneration = 1L),
        )
    }

    private class ReconcileFixture(
        eventScope: CoroutineScope,
        nowMillis: () -> Long = { timelineCurrentTimeMillis() },
    ) {
        val transport = RecordingTimelineTransport()
        val appliedGenerations = mutableListOf<Long>()
        var snapshotAppliedCount = 0
        private var requestedGeneration = 0L
        private val eventQueue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED)
        val processor = TimelineProcessor(
            initialState = TimelineReducerState(Timeline("conv-1")),
            scope = eventScope,
        )
        val reconciler = TimelineRecentMessagesReconciler(
            conversationId = "conv-1",
            scope = eventScope,
            messageApi = transport,
            eventQueue = eventQueue,
            state = MutableStateFlow(Timeline("conv-1")),
            streamSubscriberActive = MutableStateFlow(false),
            processor = processor,
            onSnapshotApplied = { snapshotAppliedCount++ },
            nowMillis = nowMillis,
        )

        init {
            transport.onGenerationRequested = { requestedGeneration = it }
            eventScope.launch {
                for (event in eventQueue) {
                    if (event is TimelineGatewayEvent.RecentMessagesSnapshot) {
                        appliedGenerations += requestedGeneration
                        reconciler.applyRecentMessagesSnapshot(event)
                    }
                }
            }
        }
    }

    private class RecordingTimelineTransport : TimelineTransport {
        var listCalls = 0
        var onListEntered: suspend () -> Unit = {}
        var onGenerationRequested: (Long) -> Unit = {}
        var messageIdProvider: () -> String = { "m-$listCalls" }
        var contentProvider: () -> String = { "hello" }

        override suspend fun sendConversationMessage(
            conversationId: String,
            request: MessageCreateRequest,
        ): Flow<LettaMessage> = emptyFlow()

        override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = emptyFlow()

        override suspend fun listConversationMessages(
            conversationId: String,
            limit: Int?,
            after: String?,
            order: String?,
        ): List<LettaMessage> {
            listCalls += 1
            onGenerationRequested(listCalls.toLong())
            onListEntered()
            return listOf(UserMessage(id = messageIdProvider(), contentRaw = JsonPrimitive(contentProvider())))
        }

        override suspend fun listAgentMessages(
            agentId: String,
            limit: Int?,
            order: String?,
            conversationId: String?,
        ): List<LettaMessage> = emptyList()
    }
}
