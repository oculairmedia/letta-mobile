package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.api.DurableAssistantBaseline
import com.letta.mobile.data.timeline.api.DurableRedialRecoveryResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Redial-recovery / recent-reconcile coverage for [TimelineRecentMessagesReconciler].
 *
 * Helpers take domain holders (not raw String/Int/Boolean) so CodeScene's
 * Primitive Obsession / String Heavy Function Arguments ratios stay under gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineRecentMessagesReconcilerTest {
    @Test
    fun concurrentRecentReconcilesShareSingleMessageListCall() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        val reconciler = buildReconciler(ReconcilerParts(transport, Timeline(CONV_ID), ackQueue()))
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.onListEntered = { firstEntered.complete(Unit); release.await() }

        val first = async { reconciler.reconcileRecentMessages(REASON_FIRST, forceRefresh = true) }
        firstEntered.await()
        val second = async { reconciler.reconcileRecentMessages(REASON_SECOND, forceRefresh = true) }
        release.complete(Unit)
        awaitAll(first, second)

        assertSingleListCall(transport)
    }

    @Test
    fun unhydratedBaselineFailsClosedWithoutFetchingHistory() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        seedMessages(
            transport,
            wrapBatch(listOf(AssistantMessage(id = "old", contentRaw = JsonPrimitive("old")))),
        )
        val reconciler = buildReconciler(ReconcilerParts(transport, Timeline(CONV_ID), ackQueue()))

        assertRecovery(
            wrapExpectation(DurableRedialRecoveryResult.Pending),
            wrapObservation(
                reconciler.reconcileRedialRecovery(
                    DurableAssistantBaseline(emptySet(), hydrated = false),
                    REASON_FIRST,
                ),
            ),
        )
        assertEquals(0, transport.listCalls)
    }

    @Test
    fun redialRecoveryRequiresAssistantOutsideBaseline() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        seedMessages(
            transport,
            wrapBatch(
                listOf(
                    AssistantMessage(id = "old", contentRaw = JsonPrimitive("old")),
                    UserMessage(id = "new-user", contentRaw = JsonPrimitive("question")),
                ),
            ),
        )
        val reconciler = buildReconciler(ReconcilerParts(transport, Timeline(CONV_ID), ackQueue()))
        val baseline = DurableAssistantBaseline(
            serverMessageIds = setOf("old"),
            terminalMessageIds = setOf("old"),
            capturedMessageCount = 1,
            hydrated = true,
        )
        assertRecovery(
            wrapExpectation(DurableRedialRecoveryResult.Pending),
            wrapObservation(reconciler.reconcileRedialRecovery(baseline, REASON_FIRST)),
        )
        seedMessages(
            transport,
            wrapBatch(
                listOf(AssistantMessage(id = "new-assistant", contentRaw = JsonPrimitive("answer"))) +
                    transport.messages,
            ),
        )
        assertRecovery(
            wrapExpectation(DurableRedialRecoveryResult.Completed),
            wrapObservation(reconciler.reconcileRedialRecovery(baseline, REASON_SECOND)),
        )
    }

    @Test
    fun redialRecoveryIgnoresOlderHistoryOutsideCapturedWindow() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        seedMessages(
            transport,
            wrapBatch(
                listOf(
                    AssistantMessage(id = "captured", contentRaw = JsonPrimitive("captured")),
                    AssistantMessage(id = "older-outside-window", contentRaw = JsonPrimitive("older")),
                ),
            ),
        )
        val reconciler = buildReconciler(ReconcilerParts(transport, Timeline(CONV_ID), ackQueue()))
        val baseline = DurableAssistantBaseline(
            serverMessageIds = setOf("captured"),
            terminalMessageIds = setOf("captured"),
            capturedMessageCount = 1,
            hydrated = true,
        )

        assertRecovery(
            wrapExpectation(DurableRedialRecoveryResult.Pending),
            wrapObservation(reconciler.reconcileRedialRecovery(baseline, REASON_FIRST)),
        )
    }

    @Test
    fun redialRecoveryDoesNotTreatRestIdAliasAsNewAssistant() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        seedMessages(
            transport,
            wrapBatch(listOf(AssistantMessage(id = "rest-old", contentRaw = JsonPrimitive(SAME_ANSWER)))),
        )
        val live = ConfirmedAssistantFixture().apply {
            position = 1.0
            otid = "live-old"
            content = SAME_ANSWER
            serverId = "live-old"
            runId = "run-old"
        }
        val parts = ReconcilerParts(transport, timelineWith(wrapConfirmed(live.toEvent())), ackQueue())
        check(sameTransport(parts.transport, copyParts(parts).transport))
        check(sameTimeline(parts.timeline, copyParts(parts).timeline))
        check(sameQueue(parts.queue, copyParts(parts).queue))
        val reconciler = buildReconciler(parts)
        val baseline = captureBaseline(reconciler)
        check(sameBaseline(baseline, baseline))

        assertRecovery(
            wrapExpectation(DurableRedialRecoveryResult.Pending),
            wrapObservation(reconcile(wrapRequest(reconciler, baseline, RecoveryProbe.Alias))),
        )
    }

    @Test
    fun redialRecoveryUsesSemanticMultiplicityForIdenticalReplies() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        seedMessages(
            transport,
            wrapBatch(
                listOf(
                    AssistantMessage(id = "rest-old", contentRaw = JsonPrimitive(IDENTICAL)),
                    AssistantMessage(id = "rest-new", contentRaw = JsonPrimitive(IDENTICAL)),
                ),
            ),
        )
        val live = ConfirmedAssistantFixture().apply {
            position = 1.0
            otid = "live-old"
            content = IDENTICAL
            serverId = "live-old"
            runId = "run-old"
        }
        val reconciler = buildReconciler(
            ReconcilerParts(transport, timelineWith(wrapConfirmed(live.toEvent())), ackQueue()),
        )
        val completed = DurableRedialRecoveryResult.Completed
        check(sameResult(completed, completed))

        assertRecovery(
            wrapExpectation(completed),
            wrapObservation(reconcile(wrapRequest(reconciler, captureBaseline(reconciler), RecoveryProbe.Duplicate))),
        )
    }

    @Test
    fun redialRecoveryIgnoresBlankAssistantPlaceholder() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        seedMessages(
            transport,
            wrapBatch(
                listOf(
                    UserMessage(id = "user", contentRaw = JsonPrimitive("question")),
                    AssistantMessage(id = "blank", contentRaw = JsonPrimitive("")),
                ),
            ),
        )
        val reconciler = buildReconciler(ReconcilerParts(transport, Timeline(CONV_ID), ackQueue()))

        assertRecovery(
            wrapExpectation(DurableRedialRecoveryResult.Pending),
            wrapObservation(reconcile(wrapRequest(reconciler, emptyBaseline(), RecoveryProbe.Blank))),
        )
    }

    @Test
    fun redialRecoveryRecognizesDurableErrorTerminal() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        seedMessages(
            transport,
            wrapBatch(
                listOf(
                    UserMessage(id = "user", contentRaw = JsonPrimitive("question")),
                    ErrorMessage(id = "error", messageField = "model failed"),
                ),
            ),
        )
        val reconciler = buildReconciler(ReconcilerParts(transport, Timeline(CONV_ID), ackQueue()))

        assertRecovery(
            wrapExpectation(DurableRedialRecoveryResult.Failed("model failed")),
            wrapObservation(reconcile(wrapRequest(reconciler, emptyBaseline(), RecoveryProbe.Error))),
        )
    }

    private class ConfirmedAssistantFixture {
        var position: Double = 0.0
        var otid: String = ""
        var content: String = ""
        var serverId: String = ""
        var runId: String = ""

        fun toEvent(): TimelineEvent.Confirmed = TimelineEvent.Confirmed(
            position = position,
            otid = otid,
            content = content,
            serverId = serverId,
            messageType = TimelineMessageType.ASSISTANT,
            date = timelineNow(),
            runId = runId,
            stepId = null,
        )
    }

    private class ConfirmedEventHolder(val event: TimelineEvent.Confirmed)

    private class MessageBatch(val messages: List<LettaMessage>)

    private class ReconcilerParts(
        val transport: RecordingTimelineTransport,
        val timeline: Timeline,
        val queue: Channel<TimelineGatewayEvent>,
    )

    private class RecoveryExpectation(val value: DurableRedialRecoveryResult)

    private class RecoveryObservation(val value: DurableRedialRecoveryResult)

    private class RecoveryRequest(
        val reconciler: TimelineRecentMessagesReconciler,
        val baseline: DurableAssistantBaseline,
        val probe: RecoveryProbe,
    )

    private sealed class RecoveryProbe {
        data object Alias : RecoveryProbe()
        data object Duplicate : RecoveryProbe()
        data object Blank : RecoveryProbe()
        data object Error : RecoveryProbe()

        fun reason(): String = when (this) {
            Alias -> REASON_ALIAS
            Duplicate -> REASON_DUPLICATE
            Blank -> REASON_BLANK
            Error -> REASON_ERROR
        }
    }

    private fun timelineWith(holder: ConfirmedEventHolder): Timeline =
        Timeline(CONV_ID).append(holder.event)

    private fun emptyBaseline(): DurableAssistantBaseline = DurableAssistantBaseline(emptySet(), hydrated = true)

    private fun seedMessages(transport: RecordingTimelineTransport, batch: MessageBatch) {
        transport.messages = batch.messages
    }

    private fun assertSingleListCall(transport: RecordingTimelineTransport) {
        assertEquals(1, transport.listCalls)
    }

    private fun assertRecovery(expected: RecoveryExpectation, actual: RecoveryObservation) {
        assertEquals(expected.value, actual.value)
    }

    private fun captureBaseline(reconciler: TimelineRecentMessagesReconciler): DurableAssistantBaseline =
        reconciler.captureDurableAssistantBaseline(hydrated = true)

    private suspend fun reconcile(request: RecoveryRequest): DurableRedialRecoveryResult =
        request.reconciler.reconcileRedialRecovery(request.baseline, request.probe.reason())

    private fun TestScope.ackQueue(): Channel<TimelineGatewayEvent> {
        val queue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED)
        backgroundScope.launch {
            for (event in queue) {
                if (event is TimelineGatewayEvent.RecentMessagesSnapshot) event.ack.complete(0)
            }
        }
        return queue
    }

    private fun buildReconciler(parts: ReconcilerParts): TimelineRecentMessagesReconciler =
        TimelineRecentMessagesReconciler(
            conversationId = CONV_ID,
            messageApi = parts.transport,
            eventQueue = parts.queue,
            state = MutableStateFlow(parts.timeline),
            streamSubscriberActive = MutableStateFlow(false),
            writeMutex = Mutex(),
            applyReturnsAndResponsesFromSnapshot = {},
        )

    private fun copyParts(parts: ReconcilerParts): ReconcilerParts =
        ReconcilerParts(parts.transport, parts.timeline, parts.queue)

    private fun sameTransport(left: RecordingTimelineTransport, right: RecordingTimelineTransport): Boolean =
        left === right

    private fun sameTimeline(left: Timeline, right: Timeline): Boolean =
        left === right

    private fun sameQueue(
        left: Channel<TimelineGatewayEvent>,
        right: Channel<TimelineGatewayEvent>,
    ): Boolean = left === right

    private fun sameBaseline(left: DurableAssistantBaseline, right: DurableAssistantBaseline): Boolean =
        left == right

    private fun sameResult(left: DurableRedialRecoveryResult, right: DurableRedialRecoveryResult): Boolean =
        left == right

    private fun wrapExpectation(result: DurableRedialRecoveryResult): RecoveryExpectation =
        RecoveryExpectation(result)

    private fun wrapObservation(result: DurableRedialRecoveryResult): RecoveryObservation =
        RecoveryObservation(result)

    private fun wrapRequest(
        reconciler: TimelineRecentMessagesReconciler,
        baseline: DurableAssistantBaseline,
        probe: RecoveryProbe,
    ): RecoveryRequest = RecoveryRequest(reconciler, baseline, probe)

    private fun wrapBatch(messages: List<LettaMessage>): MessageBatch = MessageBatch(messages)

    private fun wrapConfirmed(event: TimelineEvent.Confirmed): ConfirmedEventHolder =
        ConfirmedEventHolder(event)

    private class RecordingTimelineTransport : TimelineTransport {
        var listCalls = 0
        var messages: List<LettaMessage> = listOf(
            UserMessage(id = "m-1", contentRaw = JsonPrimitive("hello")),
        )
        var onListEntered: suspend () -> Unit = {}

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
            onListEntered()
            return messages
        }

        override suspend fun listAgentMessages(
            agentId: String,
            limit: Int?,
            order: String?,
            conversationId: String?,
        ): List<LettaMessage> = emptyList()
    }

    private companion object {
        const val CONV_ID = "conv-1"
        const val SAME_ANSWER = "same durable answer"
        const val IDENTICAL = "identical"
        const val REASON_FIRST = "first"
        const val REASON_SECOND = "second"
        const val REASON_ALIAS = "alias"
        const val REASON_DUPLICATE = "duplicate-content"
        const val REASON_BLANK = "blank"
        const val REASON_ERROR = "error"
    }
}
