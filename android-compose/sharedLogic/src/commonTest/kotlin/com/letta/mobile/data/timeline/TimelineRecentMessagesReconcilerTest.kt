package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.api.DurableAssistantBaseline
import com.letta.mobile.data.timeline.api.DurableRedialRecoveryResult
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.UserMessage
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
    fun redialRecoveryRequiresAssistantOutsideBaseline() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport().apply {
            messages = listOf(
                AssistantMessage(id = "old", contentRaw = JsonPrimitive("old")),
                UserMessage(id = "new-user", contentRaw = JsonPrimitive("question")),
            )
        }
        val queue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED).also { events ->
            backgroundScope.launch {
                for (event in events) if (event is TimelineGatewayEvent.RecentMessagesSnapshot) event.ack.complete(0)
            }
        }
        val reconciler = TimelineRecentMessagesReconciler(
            conversationId = "conv-1",
            messageApi = transport,
            eventQueue = queue,
            state = MutableStateFlow(Timeline("conv-1")),
            streamSubscriberActive = MutableStateFlow(false),
            writeMutex = Mutex(),
            applyReturnsAndResponsesFromSnapshot = {},
        )

        val baseline = DurableAssistantBaseline(
            serverMessageIds = setOf("old"),
            terminalMessageIds = setOf("old"),
            capturedMessageCount = 1,
        )
        assertEquals(DurableRedialRecoveryResult.Pending, reconciler.reconcileRedialRecovery(baseline, "first"))
        transport.messages = listOf(AssistantMessage(id = "new-assistant", contentRaw = JsonPrimitive("answer"))) + transport.messages
        assertEquals(DurableRedialRecoveryResult.Completed, reconciler.reconcileRedialRecovery(baseline, "second"))
    }

    @Test
    fun redialRecoveryDoesNotTreatRestIdAliasAsNewAssistant() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport().apply {
            messages = listOf(AssistantMessage(id = "rest-old", contentRaw = JsonPrimitive("same durable answer")))
        }
        val reconciler = reconciler(
            transport = transport,
            timeline = Timeline("conv-1").append(
                TimelineEvent.Confirmed(
                    position = 1.0,
                    otid = "live-old",
                    content = "same durable answer",
                    serverId = "live-old",
                    messageType = TimelineMessageType.ASSISTANT,
                    date = timelineNow(),
                    runId = "run-old",
                    stepId = null,
                )
            ),
        )

        val baseline = reconciler.captureDurableAssistantBaseline()

        assertEquals(DurableRedialRecoveryResult.Pending, reconciler.reconcileRedialRecovery(baseline, "alias"))
    }

    @Test
    fun redialRecoveryUsesSemanticMultiplicityForIdenticalReplies() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport().apply {
            messages = listOf(
                AssistantMessage(id = "rest-old", contentRaw = JsonPrimitive("identical")),
                AssistantMessage(id = "rest-new", contentRaw = JsonPrimitive("identical")),
            )
        }
        val reconciler = reconciler(
            transport = transport,
            timeline = Timeline("conv-1").append(
                TimelineEvent.Confirmed(
                    position = 1.0,
                    otid = "live-old",
                    content = "identical",
                    serverId = "live-old",
                    messageType = TimelineMessageType.ASSISTANT,
                    date = timelineNow(),
                    runId = "run-old",
                    stepId = null,
                )
            ),
        )

        assertEquals(
            DurableRedialRecoveryResult.Completed,
            reconciler.reconcileRedialRecovery(reconciler.captureDurableAssistantBaseline(), "duplicate-content"),
        )
    }

    @Test
    fun redialRecoveryIgnoresBlankAssistantPlaceholder() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport().apply {
            messages = listOf(
                UserMessage(id = "user", contentRaw = JsonPrimitive("question")),
                AssistantMessage(id = "blank", contentRaw = JsonPrimitive("")),
            )
        }
        val reconciler = reconciler(transport, Timeline("conv-1"))

        assertEquals(
            DurableRedialRecoveryResult.Pending,
            reconciler.reconcileRedialRecovery(DurableAssistantBaseline(emptySet()), "blank"),
        )
    }

    @Test
    fun redialRecoveryRecognizesDurableErrorTerminal() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport().apply {
            messages = listOf(
                UserMessage(id = "user", contentRaw = JsonPrimitive("question")),
                ErrorMessage(id = "error", messageField = "model failed"),
            )
        }
        val reconciler = reconciler(transport, Timeline("conv-1"))

        assertEquals(
            DurableRedialRecoveryResult.Failed("model failed"),
            reconciler.reconcileRedialRecovery(DurableAssistantBaseline(emptySet()), "error"),
        )
    }

    private fun TestScope.reconciler(
        transport: RecordingTimelineTransport,
        timeline: Timeline,
    ): TimelineRecentMessagesReconciler {
        val queue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED)
        backgroundScope.launch {
            for (event in queue) if (event is TimelineGatewayEvent.RecentMessagesSnapshot) event.ack.complete(0)
        }
        return TimelineRecentMessagesReconciler(
            conversationId = "conv-1",
            messageApi = transport,
            eventQueue = queue,
            state = MutableStateFlow(timeline),
            streamSubscriberActive = MutableStateFlow(false),
            writeMutex = Mutex(),
            applyReturnsAndResponsesFromSnapshot = {},
        )
    }

    private class RecordingTimelineTransport : TimelineTransport {
        var listCalls = 0
        var messages: List<LettaMessage> = listOf(UserMessage(id = "m-1", contentRaw = JsonPrimitive("hello")))
        var onListEntered: suspend () -> Unit = {}
        override suspend fun sendConversationMessage(conversationId: String, request: MessageCreateRequest): Flow<LettaMessage> = emptyFlow()
        override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> = emptyFlow()
        override suspend fun listConversationMessages(conversationId: String, limit: Int?, after: String?, order: String?): List<LettaMessage> {
            listCalls += 1
            onListEntered()
            return messages
        }
        override suspend fun listAgentMessages(agentId: String, limit: Int?, order: String?, conversationId: String?): List<LettaMessage> = emptyList()
    }
}
