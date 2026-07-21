package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.api.DurableRedialRecoveryIdentity
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
        val reconciler = reconciler(transport)
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.onListEntered = { firstEntered.complete(Unit); release.await() }

        val first = async { reconciler.reconcileRecentMessages(REASON_FIRST, forceRefresh = true) }
        firstEntered.await()
        val second = async { reconciler.reconcileRecentMessages(REASON_SECOND, forceRefresh = true) }
        release.complete(Unit)
        awaitAll(first, second)

        assertEquals(1, transport.listCalls)
    }

    @Test
    fun recoveryIgnoresUnrelatedHistoryAndRequiresCurrentRun() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport().apply {
            messages = listOf(
                UserMessage(id = "current-user", contentRaw = JsonPrimitive("question"), otid = "otid-current"),
                AssistantMessage(id = "old", contentRaw = JsonPrimitive("old"), runId = "run-old"),
            )
        }
        val reconciler = reconciler(transport)

        assertEquals(
            DurableRedialRecoveryResult.Pending,
            reconciler.reconcileRedialRecovery(identity(), "first"),
        )
        transport.messages = listOf(
            AssistantMessage(id = "current-answer", contentRaw = JsonPrimitive("answer"), runId = "run-current"),
        ) + transport.messages
        assertEquals(
            DurableRedialRecoveryResult.Completed,
            reconciler.reconcileRedialRecovery(identity(), "second"),
        )
    }

    @Test
    fun recoveryAcceptsPostOtidAssistantWhenDurableRunIdIsMissing() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport().apply {
            // Transport returns desc; the reconciler reverses to chronological.
            messages = listOf(
                AssistantMessage(id = "current", contentRaw = JsonPrimitive("answer"), runId = null),
                UserMessage(id = "current-user", contentRaw = JsonPrimitive("question"), otid = "otid-current"),
                AssistantMessage(id = "old", contentRaw = JsonPrimitive("old"), runId = null),
            )
        }

        assertEquals(DurableRedialRecoveryResult.Completed, reconciler(transport).reconcileRedialRecovery(identity(), "null-run"))
    }

    @Test
    fun recoveryIgnoresOtherRunAssistantAfterCurrentUser() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport().apply {
            messages = listOf(
                AssistantMessage(id = "other", contentRaw = JsonPrimitive("other"), runId = "run-other"),
                UserMessage(id = "current-user", contentRaw = JsonPrimitive("question"), otid = "otid-current"),
            )
        }

        assertEquals(DurableRedialRecoveryResult.Pending, reconciler(transport).reconcileRedialRecovery(identity(), "other-run"))
    }

    @Test
    fun recoveryDoesNotCompleteFromSameContentOnAnotherRun() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport().apply {
            messages = listOf(
                AssistantMessage(id = "old", contentRaw = JsonPrimitive("OK"), runId = "run-old"),
            )
        }
        val reconciler = reconciler(transport)

        assertEquals(DurableRedialRecoveryResult.Pending, reconciler.reconcileRedialRecovery(identity(), "same-content"))
        transport.messages = transport.messages +
            AssistantMessage(id = "current", contentRaw = JsonPrimitive("OK"), runId = "run-current")
        assertEquals(DurableRedialRecoveryResult.Completed, reconciler.reconcileRedialRecovery(identity(), "same-content-current"))
    }

    @Test
    fun recoveryIgnoresBlankCurrentRunAssistant() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport().apply {
            messages = listOf(AssistantMessage(id = "blank", contentRaw = JsonPrimitive(""), runId = "run-current"))
        }

        assertEquals(DurableRedialRecoveryResult.Pending, reconciler(transport).reconcileRedialRecovery(identity(), "blank"))
    }

    @Test
    fun recoveryRecognizesCurrentRunError() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport().apply {
            messages = listOf(ErrorMessage(id = "error", messageField = "model failed", runId = "run-current"))
        }

        assertEquals(
            DurableRedialRecoveryResult.Failed("model failed"),
            reconciler(transport).reconcileRedialRecovery(identity(), "error"),
        )
    }

    @Test
    fun recoveryUsesCurrentRunWhenDurableUserOtidIsNotYetVisible() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport().apply {
            messages = listOf(AssistantMessage(id = "answer", contentRaw = JsonPrimitive("answer"), runId = "run-current"))
        }

        assertEquals(DurableRedialRecoveryResult.Completed, reconciler(transport).reconcileRedialRecovery(identity(), "run-only"))
    }

    @Test
    fun recoveryFailsClosedWhenOnlyCurrentUserOtidIsDurable() = runTest(UnconfinedTestDispatcher()) {
        val transport = RecordingTimelineTransport().apply {
            messages = listOf(UserMessage(id = "user", contentRaw = JsonPrimitive("question"), otid = "otid-current"))
        }

        assertEquals(DurableRedialRecoveryResult.Pending, reconciler(transport).reconcileRedialRecovery(identity(), "user-only"))
    }

    private fun TestScope.reconciler(transport: RecordingTimelineTransport): TimelineRecentMessagesReconciler {
        val queue = Channel<TimelineGatewayEvent>(Channel.UNLIMITED)
        backgroundScope.launch {
            for (event in queue) if (event is TimelineGatewayEvent.RecentMessagesSnapshot) event.ack.complete(0)
        }
        return TimelineRecentMessagesReconciler(
            conversationId = "conv-1",
            messageApi = transport,
            eventQueue = queue,
            state = MutableStateFlow(Timeline("conv-1")),
            streamSubscriberActive = MutableStateFlow(false),
            writeMutex = Mutex(),
            applyReturnsAndResponsesFromSnapshot = {},
        )
    }

    private fun identity() = DurableRedialRecoveryIdentity(otid = "otid-current", runId = "run-current")

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
        const val REASON_FIRST = "first"
        const val REASON_SECOND = "second"
    }
}
