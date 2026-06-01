package com.letta.mobile.feature.chat

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.api.IConversationRepository
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
    private val conversationRepository: IConversationRepository,
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
            val summary = text.conversationSummary()
            try {
                val convId = resolveConversationId(summary)
                startTimelineObserver(convId)
                var sentConversationId = convId
                val otid = try {
                    sendToConversation(convId, text, attachments)
                } catch (e: ApiException) {
                    if (!e.isMissingConversation()) throw e
                    val replacementId = createReplacementConversation(summary, convId)
                    startTimelineObserver(replacementId)
                    sentConversationId = replacementId
                    sendToConversation(replacementId, text, attachments)
                }
                enqueueTimer.stop("otid" to otid, "conversationId" to sentConversationId)
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

    private suspend fun resolveConversationId(summary: String): String {
        val existingConversationId = if (isFreshRoute) {
            explicitConversationId
        } else {
            explicitConversationId ?: activeConversationId()
        }
        if (existingConversationId == null) {
            return createReplacementConversation(summary, staleConversationId = null)
        }
        if (!hasSummary) {
            runCatching {
                conversationRepository.updateConversation(ConversationId(existingConversationId), AgentId(agentId), summary)
                hasSummary = true
            }
        }
        return existingConversationId
    }

    private suspend fun createReplacementConversation(
        summary: String,
        staleConversationId: String?,
    ): String {
        val replacementId = conversationRepository.createConversation(AgentId(agentId), summary).id.value
        setActiveConversationId(replacementId)
        hasSummary = true
        uiState.value = uiState.value.copy(
            conversationState = ConversationState.Ready(replacementId),
            error = null,
        )
        staleConversationId?.let { staleId ->
            Telemetry.event(
                "AdminChatVM", "send.replacedMissingConversation",
                "staleConversationId" to staleId,
                "replacementConversationId" to replacementId,
            )
        }
        return replacementId
    }

    private suspend fun sendToConversation(
        conversationId: String,
        text: String,
        attachments: List<MessageContentPart.Image>,
    ): String = if (attachments.isEmpty()) {
        timelineRepository.sendMessage(conversationId, text)
    } else {
        timelineRepository.sendMessage(conversationId, text, attachments)
    }

    private fun String.conversationSummary(): String = take(SUMMARY_MAX_LENGTH).let { summary ->
        if (length > SUMMARY_MAX_LENGTH) "$summary…" else summary
    }

    private fun ApiException.isMissingConversation(): Boolean = code == 404 &&
        message.orEmpty().contains("Conversation not found", ignoreCase = true)

    private companion object {
        const val SUMMARY_MAX_LENGTH = 80
    }
}
