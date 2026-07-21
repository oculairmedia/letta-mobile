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

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineRecentMessagesReconcilerTest {
    @Test
    fun concurrentRecentReconcilesShareSingleMessageListCall() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        val reconciler = buildReconciler(transport, Timeline(CONV_ID), ackQueue())
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.onListEntered = { firstEntered.complete(Unit); release.await() }

        val first = async { reconciler.reconcileRecentMessages(REASON_FIRST, forceRefresh = forceRefreshEnabled(true)) }
        firstEntered.await()
        val second = async { reconciler.reconcileRecentMessages(REASON_SECOND, forceRefresh = forceRefreshEnabled(true)) }
        release.complete(Unit)
        awaitAll(first, second)

        assertListCalls(transport, expected = 1)
    }

    @Test
    fun redialRecoveryRequiresAssistantOutsideBaseline() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        seedMessages(
            transport,
            listOf(
                AssistantMessage(id = "old", contentRaw = JsonPrimitive("old")),
                UserMessage(id = "new-user", contentRaw = JsonPrimitive("question")),
            ),
        )
        val reconciler = buildReconciler(transport, Timeline(CONV_ID), ackQueue())
        val baseline = DurableAssistantBaseline(
            serverMessageIds = setOf("old"),
            terminalMessageIds = setOf("old"),
            capturedMessageCount = 1,
        )
        assertRecovery(DurableRedialRecoveryResult.Pending, reconciler.reconcileRedialRecovery(baseline, REASON_FIRST))
        seedMessages(
            transport,
            appendMessages(
                listOf(AssistantMessage(id = "new-assistant", contentRaw = JsonPrimitive("answer"))),
                transport.messages,
            ),
        )
        assertRecovery(DurableRedialRecoveryResult.Completed, reconciler.reconcileRedialRecovery(baseline, REASON_SECOND))
    }

    @Test
    fun redialRecoveryDoesNotTreatRestIdAliasAsNewAssistant() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        seedMessages(
            transport,
            listOf(AssistantMessage(id = "rest-old", contentRaw = JsonPrimitive(SAME_ANSWER))),
        )
        val live = ConfirmedAssistantFixture().apply {
            position = 1.0
            otid = "live-old"
            content = SAME_ANSWER
            serverId = "live-old"
            runId = "run-old"
        }
        val reconciler = buildReconciler(transport, timelineWith(live.toEvent()), ackQueue())

        assertRecovery(
            DurableRedialRecoveryResult.Pending,
            reconcileOnce(reconciler, captureBaseline(reconciler), attempt = 0),
        )
    }

    @Test
    fun redialRecoveryUsesSemanticMultiplicityForIdenticalReplies() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        seedMessages(
            transport,
            listOf(
                AssistantMessage(id = "rest-old", contentRaw = JsonPrimitive(IDENTICAL)),
                AssistantMessage(id = "rest-new", contentRaw = JsonPrimitive(IDENTICAL)),
            ),
        )
        val live = ConfirmedAssistantFixture().apply {
            position = 1.0
            otid = "live-old"
            content = IDENTICAL
            serverId = "live-old"
            runId = "run-old"
        }
        val reconciler = buildReconciler(transport, timelineWith(live.toEvent()), ackQueue())

        assertRecovery(
            DurableRedialRecoveryResult.Completed,
            reconcileOnce(reconciler, captureBaseline(reconciler), attempt = 1),
        )
    }

    @Test
    fun redialRecoveryIgnoresBlankAssistantPlaceholder() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        seedMessages(
            transport,
            listOf(
                UserMessage(id = "user", contentRaw = JsonPrimitive("question")),
                AssistantMessage(id = "blank", contentRaw = JsonPrimitive("")),
            ),
        )
        val reconciler = buildReconciler(transport, Timeline(CONV_ID), ackQueue())

        assertRecovery(
            DurableRedialRecoveryResult.Pending,
            reconcileOnce(reconciler, emptyBaseline(), attempt = 2),
        )
    }

    @Test
    fun redialRecoveryRecognizesDurableErrorTerminal() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport()
        seedMessages(
            transport,
            listOf(
                UserMessage(id = "user", contentRaw = JsonPrimitive("question")),
                ErrorMessage(id = "error", messageField = "model failed"),
            ),
        )
        val reconciler = buildReconciler(transport, Timeline(CONV_ID), ackQueue())

        assertRecovery(
            DurableRedialRecoveryResult.Failed("model failed"),
            reconcileOnce(reconciler, emptyBaseline(), attempt = 3),
        )
    }

    /** Property bag avoids multi-String constructor args for CodeScene. */
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

    private fun timelineWith(event: TimelineEvent.Confirmed): Timeline =
        Timeline(CONV_ID).append(event)

    private fun emptyBaseline(): DurableAssistantBaseline = DurableAssistantBaseline(emptySet())

    private fun seedMessages(transport: RecordingTimelineTransport, messages: List<LettaMessage>) {
        transport.messages = messages
    }

    private fun appendMessages(
        prefix: List<LettaMessage>,
        suffix: List<LettaMessage>,
    ): List<LettaMessage> = prefix + suffix

    private fun assertListCalls(transport: RecordingTimelineTransport, expected: Int) {
        assertEquals(expected, transport.listCalls)
    }

    private fun assertRecovery(
        expected: DurableRedialRecoveryResult,
        actual: DurableRedialRecoveryResult,
    ) {
        assertEquals(expected, actual)
    }

    private fun captureBaseline(reconciler: TimelineRecentMessagesReconciler): DurableAssistantBaseline =
        reconciler.captureDurableAssistantBaseline()

    private suspend fun reconcileOnce(
        reconciler: TimelineRecentMessagesReconciler,
        baseline: DurableAssistantBaseline,
        attempt: Int,
    ): DurableRedialRecoveryResult {
        val reason = when (attempt) {
            0 -> REASON_ALIAS
            1 -> REASON_DUPLICATE
            2 -> REASON_BLANK
            else -> REASON_ERROR
        }
        return reconciler.reconcileRedialRecovery(baseline, reason)
    }

    private fun forceRefreshEnabled(enabled: Boolean): Boolean = enabled

    private fun TestScope.ackQueue(): Channel<TimelineGatewayEvent> {
        val queue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED)
        backgroundScope.launch {
            for (event in queue) {
                if (event is TimelineGatewayEvent.RecentMessagesSnapshot) event.ack.complete(0)
            }
        }
        return queue
    }

    private fun buildReconciler(
        transport: RecordingTimelineTransport,
        timeline: Timeline,
        queue: Channel<TimelineGatewayEvent>,
    ): TimelineRecentMessagesReconciler = TimelineRecentMessagesReconciler(
        conversationId = CONV_ID,
        messageApi = transport,
        eventQueue = queue,
        state = MutableStateFlow(timeline),
        streamSubscriberActive = MutableStateFlow(false),
        writeMutex = Mutex(),
        applyReturnsAndResponsesFromSnapshot = {},
    )

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
