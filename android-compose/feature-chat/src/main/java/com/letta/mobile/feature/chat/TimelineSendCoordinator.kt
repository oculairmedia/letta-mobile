package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.ConversationRepository
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Owns the non-client-mode timeline send path and conversation summary setup. */
internal class TimelineSendCoordinator(
    private val scope: CoroutineScope,
    private val agentId: String,
    private val isFreshRoute: Boolean,
    private val explicitConversationId: String?,
    private val conversationRepository: ConversationRepository,
    private val timelineRepository: TimelineRepository,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val clearComposerAfterSend: () -> Unit,
    private val activeConversationId: () -> String?,
    private val setActiveConversationId: (String) -> Unit,
    private val startTimelineObserver: (String) -> Unit,
) {
    private var hasSummary = false

    fun send(
        text: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): Job {
        return scope.launch {
            val enqueueTimer = Telemetry.startTimer("AdminChatVM", "send.enqueue")
            clearComposerAfterSend()
            uiState.value = uiState.value.copy(
                isStreaming = true,
                isAgentTyping = true,
            )
            try {
                var convId: String? = if (isFreshRoute) {
                    explicitConversationId
                } else {
                    explicitConversationId ?: activeConversationId()
                }
                if (convId == null) {
                    val summary = text.take(80).let { if (text.length > 80) "$it\u2026" else it }
                    convId = conversationRepository.createConversation(agentId, summary).id
                    setActiveConversationId(convId)
                    hasSummary = true
                    uiState.value = uiState.value.copy(
                        conversationState = ConversationState.Ready(convId),
                    )
                } else if (!hasSummary) {
                    runCatching {
                        val summary = text.take(80).let { if (text.length > 80) "$it\u2026" else it }
                        conversationRepository.updateConversation(convId, agentId, summary)
                        hasSummary = true
                    }
                }
                startTimelineObserver(convId)
                val otid = if (attachments.isEmpty()) {
                    timelineRepository.sendMessage(convId, text)
                } else {
                    timelineRepository.sendMessage(convId, text, attachments)
                }
                enqueueTimer.stop("otid" to otid, "conversationId" to convId)
            } catch (e: Exception) {
                enqueueTimer.stopError(e)
                uiState.value = uiState.value.copy(
                    error = e.message,
                    isStreaming = false,
                    isAgentTyping = false,
                )
            }
        }
    }
}
