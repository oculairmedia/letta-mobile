package com.letta.mobile.channel

import androidx.work.ListenableWorker
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.ConversationInspectorMessage
import com.letta.mobile.testutil.FakeAgentRepository
import com.letta.mobile.testutil.FakeChannelNotificationPublisher
import com.letta.mobile.testutil.FakeChannelSyncStateStore
import com.letta.mobile.testutil.FakeConversationInspectorMessageRepository
import com.letta.mobile.testutil.FakeSettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
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

    private fun createFixture(): Fixture {
        val conversationApi = mockk<ConversationApi>()
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

        val conversations = mutableListOf(
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
        coEvery {
            conversationApi.listConversations(limit = 100, order = "desc", orderBy = "last_message_at")
        } answers { conversations.toList() }
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

        return Fixture(sync, stateStore, publisher, conversations, messageRepository.messagesByConversation)
    }

    private data class Fixture(
        val sync: ChannelHeartbeatSync,
        val stateStore: FakeChannelSyncStateStore,
        val publisher: FakeChannelNotificationPublisher,
        val conversations: MutableList<Conversation>,
        val messagesByConversation: MutableMap<String, List<ConversationInspectorMessage>>,
    )
}
