package com.letta.mobile.channel

import androidx.work.ListenableWorker
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.channel.NotificationCandidatePhase
import com.letta.mobile.data.channel.NotificationCandidateSource
import com.letta.mobile.data.channel.NotificationDeliveryCandidate
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.repository.ConversationInspectorMessage
import com.letta.mobile.data.repository.MessageRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelHeartbeatSync @Inject constructor(
    private val settingsRepository: ISettingsRepository,
    private val conversationApi: ConversationApi,
    private val messageRepository: MessageRepository,
    private val agentRepository: AgentRepository,
    private val syncStateStore: ChannelSyncStateStore,
    private val notificationDeliveryCoordinator: NotificationDeliveryCoordinator,
) {
    suspend fun run(): ListenableWorker.Result {
        if (settingsRepository.getActiveConfig().first() == null) {
            return ListenableWorker.Result.success()
        }

        val conversations = try {
            conversationApi.listConversations(
                limit = 100,
                order = "desc",
                orderBy = "last_message_at",
            )
        } catch (error: Exception) {
            return ListenableWorker.Result.retry()
        }

        runCatching { agentRepository.refreshAgents() }
        val agentNames = agentRepository.agents.value.associate { it.id.value to it.name }

        conversations.forEach { conversation ->
            processConversation(conversation, agentNames[conversation.agentId].orEmpty())
        }

        return ListenableWorker.Result.success()
    }

    private suspend fun processConversation(
        conversation: Conversation,
        agentName: String,
    ) {
        val lastActivityAt = conversation.lastMessageAt ?: conversation.updatedAt ?: conversation.createdAt ?: return
        val previousProcessedAt = syncStateStore.getProcessedLastActivityAt(conversation.id)
        if (previousProcessedAt == lastActivityAt) {
            return
        }

        val messages = try {
            messageRepository.fetchConversationInspectorMessages(conversation.id)
        } catch (_: Exception) {
            return
        }

        val latestNotifiable = messages.lastOrNull { it.isNotifiable() }
        if (previousProcessedAt == null) {
            syncStateStore.setProcessedLastActivityAt(conversation.id, lastActivityAt)
            latestNotifiable?.id?.let { syncStateStore.setLastNotifiedMessageId(conversation.id, it) }
            return
        }

        if (latestNotifiable != null) {
            notificationDeliveryCoordinator.submit(
                NotificationDeliveryCandidate(
                    conversationId = conversation.id,
                    agentId = conversation.agentId,
                    agentName = agentName,
                    conversationSummary = conversation.summary,
                    messageId = latestNotifiable.id,
                    runId = latestNotifiable.runId,
                    source = NotificationCandidateSource.HeartbeatFallback,
                    phase = NotificationCandidatePhase.Settled,
                    previewText = latestNotifiable.summary,
                    isFinal = true,
                ),
            )
        }

        syncStateStore.setProcessedLastActivityAt(conversation.id, lastActivityAt)
    }

    private fun ConversationInspectorMessage.isNotifiable(): Boolean {
        if (summary.isBlank()) {
            return false
        }

        return messageType in NOTIFIABLE_MESSAGE_TYPES
    }

    companion object {
        private val NOTIFIABLE_MESSAGE_TYPES = setOf(
            "assistant_message",
            "system_message",
            "approval_request_message",
            "approval_response_message",
        )
    }
}
