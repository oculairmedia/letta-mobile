package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.api.DurableAssistantBaseline
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

        assertEquals(false, reconciler.reconcileRedialRecovery(DurableAssistantBaseline(setOf("old")), "first"))
        transport.messages = transport.messages + AssistantMessage(id = "new-assistant", contentRaw = JsonPrimitive("answer"))
        assertEquals(true, reconciler.reconcileRedialRecovery(DurableAssistantBaseline(setOf("old")), "second"))
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
