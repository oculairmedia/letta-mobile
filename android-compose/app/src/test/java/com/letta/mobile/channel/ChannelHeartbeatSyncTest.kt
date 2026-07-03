package com.letta.mobile.channel

import androidx.work.ListenableWorker
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.ConversationInspectorMessage
import com.letta.mobile.testutil.FakeAgentRepository
import com.letta.mobile.testutil.FakeChannelNotificationPublisher
import com.letta.mobile.testutil.FakeChannelSyncStateStore
import com.letta.mobile.testutil.FakeConversationApi
import com.letta.mobile.testutil.FakeConversationInspectorMessageRepository
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("integration")
class ChannelHeartbeatSyncTest {
    @Test
    fun `first sync seeds baseline without notifying`() = runTest {
        val fixture = createFixture()

        val result = fixture.sync.run()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("2026-04-10T10:00:00Z", fixture.stateStore.getProcessedLastActivityAt("conv-1"))
        assertEquals("assistant-1", fixture.stateStore.getLastNotifiedMessageId("conv-1"))
        assertTrue(fixture.publisher.published.isEmpty())
    }

    @Test
    fun `second sync notifies when a newer assistant message arrives`() = runTest {
        val fixture = createFixture()
        fixture.sync.run()
        fixture.conversations.clear()
        fixture.conversations += listOf(
            Conversation(
                id = ConversationId("conv-1"),
                agentId = AgentId("agent-1"),
                summary = "Main thread",
                lastMessageAt = "2026-04-10T10:05:00Z",
            )
        )
        fixture.messagesByConversation["conv-1"] = listOf(
            ConversationInspectorMessage(id = "assistant-1", messageType = "assistant_message", date = null, runId = null, stepId = null, otid = null, summary = "Earlier"),
            ConversationInspectorMessage(id = "assistant-2", messageType = "assistant_message", date = null, runId = null, stepId = null, otid = null, summary = "A proactive ping"),
        )

        val result = fixture.sync.run()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("assistant-2", fixture.stateStore.getLastNotifiedMessageId("conv-1"))
        assertEquals(1, fixture.publisher.published.size)
        assertEquals("assistant-2", fixture.publisher.published.single().messageId)
        assertEquals("A proactive ping", fixture.publisher.published.single().messagePreview)
    }

    @Test
    fun `heartbeat fallback does not duplicate message already notified by realtime coordinator path`() = runTest {
        val fixture = createFixture()
        fixture.sync.run()
        fixture.conversations.clear()
        fixture.conversations += listOf(
            Conversation(
                id = ConversationId("conv-1"),
                agentId = AgentId("agent-1"),
                summary = "Main thread",
                lastMessageAt = "2026-04-10T10:05:00Z",
            )
        )
        fixture.messagesByConversation["conv-1"] = listOf(
            ConversationInspectorMessage(id = "assistant-2", messageType = "assistant_message", date = null, runId = null, stepId = null, otid = null, summary = "Already delivered"),
        )
        fixture.stateStore.setLastNotifiedMessageId("conv-1", "assistant-2")

        val result = fixture.sync.run()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("assistant-2", fixture.stateStore.getLastNotifiedMessageId("conv-1"))
        assertTrue(fixture.publisher.published.isEmpty())
    }

    @Test
    fun `user-only updates advance baseline without notifying`() = runTest {
        val fixture = createFixture()
        fixture.sync.run()
        fixture.conversations.clear()
        fixture.conversations += listOf(
            Conversation(
                id = ConversationId("conv-1"),
                agentId = AgentId("agent-1"),
                summary = "Main thread",
                lastMessageAt = "2026-04-10T10:06:00Z",
            )
        )
        fixture.messagesByConversation["conv-1"] = listOf(
            ConversationInspectorMessage(id = "assistant-1", messageType = "assistant_message", date = null, runId = null, stepId = null, otid = null, summary = "Earlier"),
            ConversationInspectorMessage(id = "user-2", messageType = "user_message", date = null, runId = null, stepId = null, otid = null, summary = "Hey there"),
        )

        val result = fixture.sync.run()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("2026-04-10T10:06:00Z", fixture.stateStore.getProcessedLastActivityAt("conv-1"))
        assertEquals("assistant-1", fixture.stateStore.getLastNotifiedMessageId("conv-1"))
        assertTrue(fixture.publisher.published.isEmpty())
    }

    @Test
    fun `heartbeat labels notifications from slim agent summaries without a full refresh`() = runTest {
        val fixture = createFixture()
        fixture.agentRepository.agentsState.value = listOf(TestData.agent(id = "agent-1", name = "Meridian"))
        fixture.sync.run()
        fixture.conversations.clear()
        fixture.conversations += listOf(
            Conversation(
                id = ConversationId("conv-1"),
                agentId = AgentId("agent-1"),
                summary = "Main thread",
                lastMessageAt = "2026-04-10T10:05:00Z",
            )
        )
        fixture.messagesByConversation["conv-1"] = listOf(
            ConversationInspectorMessage(id = "assistant-2", messageType = "assistant_message", date = null, runId = null, stepId = null, otid = null, summary = "A proactive ping"),
        )

        val result = fixture.sync.run()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("Meridian", fixture.publisher.published.single().agentName)
        // Wire diet (letta-mobile-hxxlz): the heartbeat pulls the slim
        // projection, never the full agents payload.
        assertTrue(fixture.agentRepository.calls.contains("listAgentSummaries"))
        assertTrue(fixture.agentRepository.calls.none { it == "refreshAgents" })
    }

    @Test
    fun `notifiable scan reads a small newest-first window and finds the latest message`() = runTest {
        val fixture = createFixture()
        fixture.sync.run()
        fixture.conversations.clear()
        fixture.conversations += listOf(
            Conversation(
                id = ConversationId("conv-1"),
                agentId = AgentId("agent-1"),
                summary = "Main thread",
                lastMessageAt = "2026-04-10T10:05:00Z",
            )
        )
        // An old notifiable message buried under a flood of newer chatter:
        // the heartbeat must find the newest notifiable message from a small
        // newest-first window instead of scanning the oldest 200 messages
        // (letta-mobile-e9vca).
        fixture.messagesByConversation["conv-1"] = buildList {
            add(ConversationInspectorMessage(id = "assistant-old", messageType = "assistant_message", date = null, runId = null, stepId = null, otid = null, summary = "Old ping"))
            repeat(30) { index ->
                add(ConversationInspectorMessage(id = "user-$index", messageType = "user_message", date = null, runId = null, stepId = null, otid = null, summary = "chatter $index"))
            }
            add(ConversationInspectorMessage(id = "assistant-new", messageType = "assistant_message", date = null, runId = null, stepId = null, otid = null, summary = "Newest ping"))
        }

        val result = fixture.sync.run()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf("assistant-new"), fixture.publisher.published.map { it.messageId })
        // The newest message is notifiable, so each sync settles with a
        // single page far below the old 200-message fetch (letta-mobile-e9vca).
        assertEquals(2, fixture.messageRepository.latestFetchLimits.size)
        assertTrue(fixture.messageRepository.latestFetchLimits.all { it < 200 })
    }

    @Test
    fun `notifiable buried under a full window of chatter is still notified`() = runTest {
        val fixture = createFixture()
        fixture.sync.run()
        fixture.messageRepository.latestFetchLimits.clear()
        fixture.conversations.clear()
        fixture.conversations += listOf(
            Conversation(
                id = ConversationId("conv-1"),
                agentId = AgentId("agent-1"),
                summary = "Main thread",
                lastMessageAt = "2026-04-10T10:05:00Z",
            )
        )
        // The newest notifiable message sits below a full first window of
        // reasoning/tool chatter: the scan must page deeper instead of
        // silently advancing the baseline past it.
        fixture.messagesByConversation["conv-1"] = buildList {
            add(ConversationInspectorMessage(id = "assistant-buried", messageType = "assistant_message", date = null, runId = null, stepId = null, otid = null, summary = "Buried ping"))
            repeat(30) { index ->
                add(ConversationInspectorMessage(id = "reasoning-$index", messageType = "reasoning_message", date = null, runId = null, stepId = null, otid = null, summary = "thinking $index"))
            }
        }

        val result = fixture.sync.run()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf("assistant-buried"), fixture.publisher.published.map { it.messageId })
        assertEquals("assistant-buried", fixture.stateStore.getLastNotifiedMessageId("conv-1"))
        assertEquals("2026-04-10T10:05:00Z", fixture.stateStore.getProcessedLastActivityAt("conv-1"))
        // The scan widened past the first full window to reach the message.
        val limits = fixture.messageRepository.latestFetchLimits
        assertTrue(limits.size >= 2)
        assertTrue(limits.zipWithNext().all { (first, second) -> first < second })
    }

    @Test
    fun `capped scan keeps the baseline so a deeply buried notifiable is not skipped permanently`() = runTest {
        val fixture = createFixture()
        fixture.sync.run()
        fixture.conversations.clear()
        fixture.conversations += listOf(
            Conversation(
                id = ConversationId("conv-1"),
                agentId = AgentId("agent-1"),
                summary = "Main thread",
                lastMessageAt = "2026-04-10T10:05:00Z",
            )
        )
        // More chatter than the widest scan window: this heartbeat cannot see
        // the notifiable, so it must leave the baseline where it was instead
        // of jumping over the message.
        fixture.messagesByConversation["conv-1"] = buildList {
            add(ConversationInspectorMessage(id = "assistant-deep", messageType = "assistant_message", date = null, runId = null, stepId = null, otid = null, summary = "Deep ping"))
            repeat(80) { index ->
                add(ConversationInspectorMessage(id = "tool-$index", messageType = "tool_call_message", date = null, runId = null, stepId = null, otid = null, summary = "tool $index"))
            }
        }

        val result = fixture.sync.run()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(fixture.publisher.published.isEmpty())
        assertEquals("2026-04-10T10:00:00Z", fixture.stateStore.getProcessedLastActivityAt("conv-1"))
    }

    private fun createFixture(): Fixture {
        val conversationApi = FakeConversationApi()
        val messageRepository = FakeConversationInspectorMessageRepository()
        val agentRepository = FakeAgentRepository()
        val stateStore = FakeChannelSyncStateStore()
        val publisher = FakeChannelNotificationPublisher()
        val settingsRepository = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test-config",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "http://localhost:8291",
                accessToken = "tok",
            ),
        )

        conversationApi.conversations += listOf(
            Conversation(
                id = ConversationId("conv-1"),
                agentId = AgentId("agent-1"),
                summary = "Main thread",
                lastMessageAt = "2026-04-10T10:00:00Z",
            )
        )
        val messagesByConversation = mutableMapOf(
            "conv-1" to listOf(
                ConversationInspectorMessage(id = "assistant-1", messageType = "assistant_message", date = null, runId = null, stepId = null, otid = null, summary = "Earlier")
            )
        )
        messageRepository.messagesByConversation += messagesByConversation

        val coordinator = NotificationDeliveryCoordinator(
            currentConversationTracker = CurrentConversationTracker(),
            syncStateStore = stateStore,
            publisher = publisher,
        )

        val sync = ChannelHeartbeatSync(
            settingsRepository = settingsRepository,
            conversationApi = conversationApi,
            messageRepository = messageRepository,
            agentRepository = agentRepository,
            syncStateStore = stateStore,
            notificationDeliveryCoordinator = coordinator,
        )

        return Fixture(
            sync,
            stateStore,
            publisher,
            conversationApi.conversations,
            messageRepository.messagesByConversation,
            agentRepository,
            messageRepository,
        )
    }

    private data class Fixture(
        val sync: ChannelHeartbeatSync,
        val stateStore: FakeChannelSyncStateStore,
        val publisher: FakeChannelNotificationPublisher,
        val conversations: MutableList<Conversation>,
        val messagesByConversation: MutableMap<String, List<ConversationInspectorMessage>>,
        val agentRepository: FakeAgentRepository,
        val messageRepository: FakeConversationInspectorMessageRepository,
    )
}
