package com.letta.mobile.bot.channel

import android.util.Log
import com.letta.mobile.bot.chat.IClientModeChatSender
import com.letta.mobile.bot.protocol.BotStreamEvent
import com.letta.mobile.data.channel.NotificationCandidatePhase
import com.letta.mobile.data.channel.NotificationCandidateSource
import com.letta.mobile.data.channel.NotificationDelivery
import com.letta.mobile.data.channel.NotificationDeliveryCandidate
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.MessageSource
import com.letta.mobile.data.timeline.Role
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.api.TimelineClientModeWriter
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Singleton
class NotificationReplyHandler @Inject constructor(
    private val clientModeChatSender: IClientModeChatSender,
    private val timelineRepository: TimelineClientModeWriter,
    private val agentRepository: IAgentRepository,
    private val notificationDeliveryProvider: Provider<NotificationDelivery>,
) : NotificationReplyStreamTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Set of conversation IDs with active notification-reply streams.
    // Observed by AdminChatViewModel to keep isStreaming=true during
    // handler-driven streaming so ChatMessageComponents uses plain Text
    // instead of re-parsing MarkdownText on every chunk (prevents flicker).
    private val _activeReplyStreams = MutableStateFlow<Set<String>>(emptySet())
    override val activeReplyStreams: StateFlow<Set<String>> = _activeReplyStreams.asStateFlow()

    fun sendReply(agentId: String, conversationId: String, text: String): Job {
        return scope.launch {
            try {
                _activeReplyStreams.update { it + conversationId }
                timelineRepository.appendClientModeLocal(
                    conversationId = conversationId,
                    content = text,
                )
                Log.w(TAG, "user reply written to timeline for $conversationId")

                val assistantMessageId = "cm-assist-notif-${UUID.randomUUID()}"
                var accumulatedAssistantPreview = ""

                clientModeChatSender.streamMessage(agentId, text, conversationId).collect { chunk ->
                    val convId = chunk.conversationId ?: conversationId
                    // Match AdminChatViewModel's Client Mode timeline path:
                    // stamp assistant/reasoning locals at chunk arrival time so
                    // fuzzy collapse compares against the relevant server event
                    // time instead of the notification reply start time.
                    val chunkReceivedAt = Instant.now()

                    when (chunk.event) {
                        BotStreamEvent.REASONING -> {
                            val localId = "cm-reason-${chunk.uuid ?: assistantMessageId}"
                            val delta = chunk.text.orEmpty()
                            timelineRepository.upsertClientModeLocalAssistantChunk(
                                conversationId = convId,
                                localId = localId,
                                build = {
                                    TimelineEvent.Local(
                                        position = 0.0,
                                        otid = localId,
                                        content = "",
                                        role = Role.ASSISTANT,
                                        sentAt = chunkReceivedAt,
                                        deliveryState = DeliveryState.SENT,
                                        source = MessageSource.CLIENT_MODE_HARNESS,
                                        messageType = TimelineMessageType.REASONING,
                                        reasoningContent = delta,
                                    )
                                },
                                transform = { existing ->
                                    val merged = (existing.reasoningContent.orEmpty()) + delta
                                    existing.copy(reasoningContent = merged, messageType = TimelineMessageType.REASONING)
                                },
                            )
                        }

                        else -> {
                            val delta = chunk.text?.takeIf { it.isNotEmpty() }
                            if (chunk.done) {
                                if (accumulatedAssistantPreview.isNotBlank()) {
                                    notificationDeliveryProvider.get().submit(
                                        NotificationDeliveryCandidate(
                                            conversationId = convId,
                                            agentId = agentId,
                                            agentName = agentRepository.getCachedAgent(agentId)?.name.orEmpty(),
                                            conversationSummary = null,
                                            messageId = assistantMessageId,
                                            runId = null,
                                            source = NotificationCandidateSource.NotificationReplyStream,
                                            phase = NotificationCandidatePhase.Final,
                                            previewText = accumulatedAssistantPreview,
                                            isFinal = true,
                                        ),
                                    )
                                }
                                return@collect
                            }
                            if (delta == null) return@collect
                            accumulatedAssistantPreview += delta

                            val localId = "cm-assist-${chunk.uuid ?: assistantMessageId}"
                            timelineRepository.upsertClientModeLocalAssistantChunk(
                                conversationId = convId,
                                localId = localId,
                                build = {
                                    TimelineEvent.Local(
                                        position = 0.0,
                                        otid = localId,
                                        content = delta,
                                        role = Role.ASSISTANT,
                                        sentAt = chunkReceivedAt,
                                        deliveryState = DeliveryState.SENT,
                                        source = MessageSource.CLIENT_MODE_HARNESS,
                                        messageType = TimelineMessageType.ASSISTANT,
                                    )
                                },
                                transform = { existing -> existing.copy(content = existing.content + delta) },
                            )
                        }
                    }
                }
                Log.w(TAG, "WS reply stream completed for $conversationId")
                // letta-mobile-iuh6: re-run fuzzy collapse to absorb any
                // Locals that were written after the initial SSE reconcile
                // already ran (race condition).
                timelineRepository.postHandlerCollapse(conversationId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send reply to $conversationId", e)
            } finally {
                _activeReplyStreams.update { it - conversationId }
            }
        }
    }

    companion object {
        private const val TAG = "NotificationReply"
    }
}
