package com.letta.mobile.feature.chat.coordination

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
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState

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
            // letta-mobile-mxwtn: optimistic insert. The user bubble reaches
            // the timeline state in the same frame as the composer clear so
            // the visible latency is the projection + frame-pacing delay
            // (and not the transport round-trip). The otid is minted up front
            // and threaded into both the optimistic insert and the actual
            // transport call so the two paths see the same identity and the
            // existing replaceByOtid reconcile on the server echo collapses
            // the Local into the Confirmed.
            val otid = newOptimisticOtid()
            clearComposerAfterSend()
            uiState.value = uiState.value.copy(
                isStreaming = true,
                isAgentTyping = true,
            )
            val summary = text.conversationSummary()
            try {
                val convId = resolveConversationId(summary)
                // Optimistic insert BEFORE the observer is rebound so the
                // initial projection the observer renders already includes
                // the Local user bubble. If the conversation resolves to a
                // replacement after a 404, the insert is repeated for the
                // replacement id (the original otid never reached the
                // transport so no state diverges).
                appendOptimisticLocalSafely(convId, otid, text, attachments)
                startTimelineObserver(convId)
                var sentConversationId = convId
                try {
                    sendToConversation(convId, otid, text, attachments)
                } catch (e: ApiException) {
                    if (!e.isMissingConversation()) throw e
                    val replacementId = createReplacementConversation(summary, convId)
                    appendOptimisticLocalSafely(replacementId, otid, text, attachments)
                    startTimelineObserver(replacementId)
                    sentConversationId = replacementId
                    sendToConversation(replacementId, otid, text, attachments)
                }
                enqueueTimer.stop("otid" to otid, "conversationId" to sentConversationId)
            } catch (e: Exception) {
                // letta-mobile-mxwtn: send rejected. Flip the optimistic
                // Local bubble to FAILED so the user sees a retry affordance
                // instead of a permanent spinner. The otid we minted is the
                // one that was inserted optimistically — mark that one
                // failed, regardless of which conversation id the transport
                // call ended up targeting. If the optimistic insert itself
                // errored (shouldn't, but defensive) the catch above is
                // where the failure is surfaced.
                markOptimisticLocalFailedSafely(otid)
                enqueueTimer.stopError(e)
                uiState.value = uiState.value.copy(
                    error = e.message,
                    isStreaming = false,
                    isAgentTyping = false,
                )
            }
        }
    }

    /**
     * letta-mobile-mxwtn: mint a fresh client otid for the upcoming send.
     * Kept as a virtual seam so a future test can substitute a deterministic
     * generator without rewriting the call site.
     */
    internal fun newOptimisticOtid(): String = java.util.UUID.randomUUID().toString()

    /**
     * letta-mobile-mxwtn: optimistically insert the Local user bubble into
     * the timeline state. Wrapped in runCatching because the underlying
     * repository can be in a torn-down state during rapid route changes;
     * the caller's outer catch still surfaces the user-visible error.
     */
    private suspend fun appendOptimisticLocalSafely(
        conversationId: String,
        otid: String,
        text: String,
        attachments: List<MessageContentPart.Image>,
    ) {
        runCatching {
            timelineRepository.appendOptimisticLocal(
                agentId = agentId,
                conversationId = conversationId,
                otid = otid,
                content = text,
                attachments = attachments,
            )
        }
    }

    /**
     * letta-mobile-mxwtn: best-effort failure flip. Walks both the current
     * active conversation and the explicit one because the failing path may
     * have raced a route change; the optimistic insert only ever happened
     * in ONE of them, so at most one call mutates state.
     */
    private suspend fun markOptimisticLocalFailedSafely(otid: String) {
        val candidates = sequenceOf(
            explicitConversationId,
            activeConversationId(),
        ).filterNotNull().distinct()
        for (conversationId in candidates) {
            runCatching {
                timelineRepository.markOptimisticLocalFailed(agentId, conversationId, otid)
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
        otid: String,
        text: String,
        attachments: List<MessageContentPart.Image>,
    ) {
        // letta-mobile-mxwtn: pre-minted-otid send. The Local bubble is
        // already in the timeline state from the optimistic insert, so we
        // use the no-Local-append variant. MarkSent / MarkFailed / reconcile
        // continue to run on top of the existing Local event — the user's
        // retry / confirmation path is unchanged.
        timelineRepository.sendWithOtid(
            agentId = agentId,
            conversationId = conversationId,
            content = text,
            otid = otid,
            attachments = attachments,
        )
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
