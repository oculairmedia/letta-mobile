package com.letta.mobile.channel

import androidx.work.ListenableWorker
import com.letta.mobile.bot.channel.NotificationReplyHandler
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ConversationInspectorMessage
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.testutil.FakeSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
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
        verify(exactly = 0) { fixture.publisher.publish(any()) }
    }

    @Test
    fun `second sync notifies when a newer assistant message arrives`() = runTest {
        val fixture = createFixture()
        fixture.sync.run()
        fixture.conversations.clear()
        fixture.conversations += listOf(
            Conversation(
                id = "conv-1",
                agentId = "agent-1",
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
        verify(exactly = 1) { fixture.publisher.publish(match { it.messageId == "assistant-2" && it.messagePreview == "A proactive ping" }) }
    }

    @Test
    fun `heartbeat fallback does not duplicate message already notified by realtime coordinator path`() = runTest {
        val fixture = createFixture()
        fixture.sync.run()
        fixture.conversations.clear()
        fixture.conversations += listOf(
            Conversation(
                id = "conv-1",
                agentId = "agent-1",
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
        verify(exactly = 0) { fixture.publisher.publish(any()) }
    }

    @Test
    fun `user-only updates advance baseline without notifying`() = runTest {
        val fixture = createFixture()
        fixture.sync.run()
        fixture.conversations.clear()
        fixture.conversations += listOf(
            Conversation(
                id = "conv-1",
                agentId = "agent-1",
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
        verify(exactly = 0) { fixture.publisher.publish(any()) }
    }

    private fun createFixture(): Fixture {
        val conversationApi = mockk<ConversationApi>()
        val messageRepository = mockk<MessageRepository>()
        val agentRepository = mockk<AgentRepository>()
        val stateStore = mockk<ChannelSyncStateStore>()
        val publisher = mockk<ChannelNotificationPublisher>()
        val replyHandler = mockk<NotificationReplyHandler>()
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
                id = "conv-1",
                agentId = "agent-1",
                summary = "Main thread",
                lastMessageAt = "2026-04-10T10:00:00Z",
            )
        )
        val messagesByConversation = mutableMapOf(
            "conv-1" to listOf(
                ConversationInspectorMessage(id = "assistant-1", messageType = "assistant_message", date = null, runId = null, stepId = null, otid = null, summary = "Earlier")
            )
        )
        val processed = mutableMapOf<String, String>()
        val notified = mutableMapOf<String, String>()

        coEvery {
            conversationApi.listConversations(limit = 100, order = "desc", orderBy = "last_message_at")
        } answers { conversations.toList() }
        coEvery { messageRepository.fetchConversationInspectorMessages(any()) } answers {
            messagesByConversation[firstArg<String>()].orEmpty()
        }
        coEvery { agentRepository.refreshAgents() } just runs
        every { agentRepository.agents } returns MutableStateFlow(emptyList())
        every { stateStore.getProcessedLastActivityAt(any()) } answers { processed[firstArg()] }
        every { stateStore.setProcessedLastActivityAt(any(), any()) } answers {
            processed[firstArg()] = secondArg()
            Unit
        }
        every { stateStore.getLastNotifiedMessageId(any()) } answers { notified[firstArg()] }
        every { stateStore.setLastNotifiedMessageId(any(), any()) } answers {
            notified[firstArg()] = secondArg()
            Unit
        }
        every { replyHandler.activeReplyStreams } returns MutableStateFlow(emptySet())
        every { publisher.publish(any()) } returns true

        val coordinator = NotificationDeliveryCoordinator(
            currentConversationTracker = CurrentConversationTracker(),
            notificationReplyHandler = replyHandler,
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

        return Fixture(sync, stateStore, publisher, conversations, messagesByConversation)
    }

    private data class Fixture(
        val sync: ChannelHeartbeatSync,
        val stateStore: ChannelSyncStateStore,
        val publisher: ChannelNotificationPublisher,
        val conversations: MutableList<Conversation>,
        val messagesByConversation: MutableMap<String, List<ConversationInspectorMessage>>,
    )
}
