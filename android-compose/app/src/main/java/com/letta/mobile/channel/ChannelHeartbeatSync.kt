package com.letta.mobile.channel

import androidx.work.ListenableWorker
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.channel.NotificationCandidatePhase
import com.letta.mobile.data.channel.NotificationCandidateSource
import com.letta.mobile.data.channel.NotificationDeliveryCandidate
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.ConversationInspectorMessage
import com.letta.mobile.data.repository.api.IConversationInspectorMessageRepository
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelHeartbeatSync @Inject constructor(
    private val settingsRepository: ISettingsRepository,
    private val conversationApi: ConversationApi,
    private val messageRepository: IConversationInspectorMessageRepository,
    private val agentRepository: IAgentRepository,
    private val syncStateStore: IChannelSyncStateStore,
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

        // Wire diet (letta-mobile-hxxlz): the heartbeat only needs id→name to
        // label notifications, so pull the slim `{id, name, description}`
        // projection instead of refreshAgents()'s full agents payload
        // (~9.6MB every 15 minutes). Full-object consumers keep their own
        // fat refresh paths; on slim failure fall back to whatever full
        // agents are already cached.
        val agentNames = runCatching { agentRepository.listAgentSummaries() }
            .map { summaries -> summaries.associate { it.id.value to it.name } }
            .getOrElse { agentRepository.agents.value.associate { it.id.value to it.name } }

        conversations.forEach { conversation ->
            processConversation(conversation, agentNames[conversation.agentId.value].orEmpty())
        }

        return ListenableWorker.Result.success()
    }

    private suspend fun processConversation(
        conversation: Conversation,
        agentName: String,
    ) {
        val lastActivityAt = conversation.lastMessageAt ?: conversation.updatedAt ?: conversation.createdAt ?: return
        val conversationId = conversation.id.value
        val previousProcessedAt = syncStateStore.getProcessedLastActivityAt(conversationId)
        if (previousProcessedAt == lastActivityAt) {
            return
        }

        val scan = try {
            scanForLatestNotifiable(conversation.id, previousProcessedAt)
        } catch (_: Exception) {
            return
        }

        if (previousProcessedAt == null) {
            syncStateStore.setProcessedLastActivityAt(conversationId, lastActivityAt)
            scan.latestNotifiable?.id?.let { syncStateStore.setLastNotifiedMessageId(conversationId, it) }
            return
        }

        scan.latestNotifiable?.let { latestNotifiable ->
            notificationDeliveryCoordinator.submit(
                NotificationDeliveryCandidate(
                    conversationId = conversationId,
                    agentId = conversation.agentId.value,
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

        // Only advance the baseline once the scan has actually settled the
        // unprocessed gap; a capped scan that found nothing may still have a
        // notifiable buried under deeper chatter, and jumping the baseline
        // over it would skip that message permanently (the next heartbeat
        // retries instead).
        if (scan.latestNotifiable != null || scan.coveredGap) {
            syncStateStore.setProcessedLastActivityAt(conversationId, lastActivityAt)
        }
    }

    /**
     * Newest-first scan for the latest notifiable message. Starts with a
     * small window and, when a full window comes back all chatter
     * (reasoning/tool-call/tool-return noise), widens it until a notifiable
     * turns up, the scan reaches messages at-or-before [previousProcessedAt],
     * the conversation start is reached, or [MAX_SCAN_PAGES] bounds the cost.
     */
    private suspend fun scanForLatestNotifiable(
        conversationId: ConversationId,
        previousProcessedAt: String?,
    ): NotifiableScan {
        for (page in 1..MAX_SCAN_PAGES) {
            val windowSize = LATEST_MESSAGE_WINDOW * page
            val messages = messageRepository.fetchLatestConversationInspectorMessages(
                conversationId = conversationId,
                limit = windowSize,
            )

            // Newest-first window, so the first notifiable hit is the latest one.
            val latestNotifiable = messages.firstOrNull { it.isNotifiable() }
            if (latestNotifiable != null) {
                return NotifiableScan(latestNotifiable, coveredGap = true)
            }
            // A short window means the conversation start was reached.
            if (messages.size < windowSize) {
                return NotifiableScan(latestNotifiable = null, coveredGap = true)
            }
            // Timestamps are ISO-8601 UTC, so string comparison orders them.
            val reachedBaseline = previousProcessedAt != null &&
                messages.any { message -> message.date?.let { it <= previousProcessedAt } == true }
            if (reachedBaseline) {
                return NotifiableScan(latestNotifiable = null, coveredGap = true)
            }
        }
        return NotifiableScan(latestNotifiable = null, coveredGap = false)
    }

    private data class NotifiableScan(
        val latestNotifiable: ConversationInspectorMessage?,
        /** Whether the scan settled the whole gap since the processed baseline. */
        val coveredGap: Boolean,
    )

    private fun ConversationInspectorMessage.isNotifiable(): Boolean {
        if (summary.isBlank()) {
            return false
        }

        return messageType in NOTIFIABLE_MESSAGE_TYPES
    }

    companion object {
        /**
         * Newest-first page size for the notifiable-message scan. The
         * heartbeat only needs the most recent notifiable message; the old
         * oldest-first 200-message fetch could never see past a
         * conversation's first 200 messages and pulled up to 20k records per
         * sync across 100 conversations (letta-mobile-e9vca).
         */
        private const val LATEST_MESSAGE_WINDOW = 25

        /**
         * Bound on how far a scan widens when full windows keep coming back
         * all chatter: window sizes escalate to LATEST_MESSAGE_WINDOW *
         * MAX_SCAN_PAGES before the scan gives up for this heartbeat (and
         * leaves the baseline in place so the next one retries).
         */
        private const val MAX_SCAN_PAGES = 3

        private val NOTIFIABLE_MESSAGE_TYPES = setOf(
            "assistant_message",
            "system_message",
            "approval_request_message",
            "approval_response_message",
        )
    }
}
