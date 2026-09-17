package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.transport.WsTimelineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendLeafComponentsTest {
    @Test
    fun runtimeEventBatcherPreservesGlobalFifoOrderInOneDrain() = runTest {
        val workerJob = Job()
        val persisted = mutableListOf<List<ScopedRuntimeEvent>>()
        val batcher = RuntimeEventBatcher(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + workerJob),
            persist = persisted::add,
        )
        val first = WsTimelineEvent.MessageDelta(assistantMessage("leaf-a"), conversationId = "conv-a")
        val second = WsTimelineEvent.MessageDelta(assistantMessage("leaf-b"), conversationId = "conv-b")

        batcher.enqueue(first, "conv-a")
        batcher.enqueue(second, "conv-b")
        runCurrent()

        assertEquals(1, persisted.size)
        assertEquals(listOf("conv-a", "conv-b"), persisted.single().map(ScopedRuntimeEvent::conversationId))
        assertEquals(listOf(first, second), persisted.single().map(ScopedRuntimeEvent::event))
        workerJob.cancel()
    }

    @Test
    fun bridgeEventDeduplicatorKeepsLifecycleEventsInstanceScoped() {
        val firstCoordinator = BridgeEventDeduplicator()
        val secondCoordinator = BridgeEventDeduplicator()
        val event = WsTimelineEvent.TurnStarted(
            turnId = "leaf-turn",
            agentId = "leaf-agent",
            conversationId = "leaf-conversation",
            runId = "leaf-run",
        )

        assertFalse(firstCoordinator.isDuplicate(event, fallbackConversationId = null))
        assertTrue(firstCoordinator.isDuplicate(event, fallbackConversationId = null))
        assertFalse(secondCoordinator.isDuplicate(event, fallbackConversationId = null))
    }

    @Test
    fun bridgeEventDeduplicatorDropsReplayedTurnStartedAlreadySeenLive() {
        val deduplicator = BridgeEventDeduplicator()
        val live = WsTimelineEvent.TurnStarted(
            turnId = "resume-turn",
            agentId = "resume-agent",
            conversationId = "resume-conversation",
            runId = "resume-run",
        )
        val replayed = live.copy(isReplay = true)

        assertFalse(deduplicator.isDuplicate(live, fallbackConversationId = null))
        assertTrue(deduplicator.isDuplicate(replayed, fallbackConversationId = null))
    }

    @Test
    fun bridgeEventDeduplicatorDropsLiveTurnStartedAlreadySeenAsReplay() {
        val deduplicator = BridgeEventDeduplicator()
        val replayed = WsTimelineEvent.TurnStarted(
            turnId = "resume-turn",
            agentId = "resume-agent",
            conversationId = "resume-conversation",
            runId = "resume-run",
            isReplay = true,
        )

        assertFalse(deduplicator.isDuplicate(replayed, fallbackConversationId = null))
        assertTrue(deduplicator.isDuplicate(replayed.copy(isReplay = false), fallbackConversationId = null))
    }

    @Test
    fun bridgeEventDeduplicatorSeparatesDistinctTurns() {
        val deduplicator = BridgeEventDeduplicator()
        val first = WsTimelineEvent.TurnStarted(
            turnId = "turn-one",
            agentId = "resume-agent",
            conversationId = "resume-conversation",
            runId = "run-one",
        )

        assertFalse(deduplicator.isDuplicate(first, fallbackConversationId = null))
        assertFalse(deduplicator.isDuplicate(first.copy(turnId = "turn-two", runId = "run-two"), fallbackConversationId = null))
    }

    @Test
    fun bridgeEventDeduplicatorKeepsSharedMessageFanoutProcessWide() {
        val firstCoordinator = BridgeEventDeduplicator()
        val secondCoordinator = BridgeEventDeduplicator()
        val event = WsTimelineEvent.MessageDelta(
            message = assistantMessage("leaf-shared-message"),
            conversationId = null,
        )

        assertFalse(firstCoordinator.isDuplicate(event, fallbackConversationId = "leaf-owner"))
        assertTrue(secondCoordinator.isDuplicate(event, fallbackConversationId = "leaf-owner"))
        assertFalse(secondCoordinator.isDuplicate(event, fallbackConversationId = "different-owner"))
    }

    private fun assistantMessage(id: String): AssistantMessage = AssistantMessage(
        id = id,
        contentRaw = JsonPrimitive("content-$id"),
        runId = "run-$id",
    )
}
