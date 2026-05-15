package com.letta.mobile.ui.screens.chat

import com.letta.mobile.bot.chat.ClientModeChatSender
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.bot.protocol.BotStreamEvent
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.channel.NotificationCandidatePhase
import com.letta.mobile.data.channel.NotificationCandidateSource
import com.letta.mobile.data.channel.NotificationDeliveryCandidate
import com.letta.mobile.channel.NotificationDeliveryCoordinator
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.buildContentParts
import com.letta.mobile.data.model.toJsonArray
import com.letta.mobile.data.timeline.ClientModeStreamChunk
import com.letta.mobile.data.timeline.ClientModeStreamEvent
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Coordinates Client Mode sends, stream lifecycle, and fresh-route migration. */
internal class ClientModeSendCoordinator(
    private val scope: CoroutineScope,
    private val agentId: String,
    private val clientModeChatSender: ClientModeChatSender,
    private val timelineRepository: TimelineRepository,
    private val notificationDeliveryCoordinator: NotificationDeliveryCoordinator,
    private val currentConversationTracker: CurrentConversationTracker,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val clearComposerAfterSend: () -> Unit,
    private val currentClientModeConversationId: () -> String?,
    private val setClientModeConversationId: (String?) -> Unit,
    private val setRouteConversationId: (String) -> Unit,
    private val setActiveConversationId: (String) -> Unit,
    private val markClientModeBootstrapReady: (String) -> Unit,
    private val pendingBootstrapMessages: () -> ImmutableList<UiMessage>,
    private val setBootstrapUserMessage: (UiMessage) -> Unit,
    private val clearBootstrapUserMessage: () -> Unit,
    private val showConversationSwap: (ClientModeConversationSwap) -> Unit,
    private val startTimelineObserver: (String) -> Unit,
    private val stopTimelineObserver: () -> Unit,
    private val refreshContextWindow: () -> Unit,
    private val collapseCompletedRunsIfStreamingFinished: (previous: ChatUiState, next: ChatUiState) -> ChatUiState,
) {
    private var streamJob: Job? = null
    @Volatile var isStreamInFlight: Boolean = false
        private set
    @Volatile var streamStartedAtElapsedMs: Long = 0L
        private set

    private var pendingStreamSessionId: String? = null
    private val pendingStreamChunks = ArrayDeque<BotStreamChunk>()

    fun cancelActiveStream(reason: String? = null) {
        reason?.let { streamJob?.cancel(CancellationException(it)) } ?: streamJob?.cancel()
        resetPreConversationBuffer()
        isStreamInFlight = false
        streamStartedAtElapsedMs = 0L
    }

    fun send(
        text: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
        explicitConversationId: String?,
    ): Job {
        streamJob?.cancel()
        isStreamInFlight = true
        streamStartedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        uiState.value = uiState.value.copy(
            isLoadingMessages = false,
            isLoadingOlderMessages = false,
            hasMoreOlderMessages = false,
            isStreaming = true,
            isAgentTyping = true,
            error = null,
        )
        val startedAt = java.time.Instant.now().toString()
        val userMessageId = "client-user-${java.util.UUID.randomUUID()}"
        val assistantMessageId = "client-assistant-${java.util.UUID.randomUUID()}"
        resetPreConversationBuffer()
        pendingStreamSessionId = assistantMessageId
        val outboundText = buildClientModeOutboundText(text, attachments)
        val initialPriorConversationId = explicitConversationId ?: currentClientModeConversationId()
        clearComposerAfterSend()
        val job = scope.launch {
            val priorConversationId = initialPriorConversationId
            currentConversationTracker.setCurrent(priorConversationId)
            if (priorConversationId != null) {
                val convId = priorConversationId
                uiState.value = uiState.value.copy(
                    isLoadingMessages = false,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    isStreaming = true,
                    isAgentTyping = true,
                    error = null,
                    conversationState = ConversationState.Ready(convId),
                )
                runCatching {
                    timelineRepository.appendClientModeLocal(
                        conversationId = convId,
                        content = text,
                        attachments = attachments,
                    )
                }.onFailure { e ->
                    android.util.Log.w(
                        "AdminChatViewModel",
                        "appendClientModeLocal failed; continuing without legacy in-memory fallback",
                        e,
                    )
                }
                startTimelineObserver(convId)
            } else {
                stopTimelineObserver()
                setBootstrapUserMessage(
                    UiMessage(
                        id = userMessageId,
                        role = "user",
                        content = text,
                        timestamp = startedAt,
                        attachments = attachments.map {
                            UiImageAttachment(
                                base64 = it.base64,
                                mediaType = it.mediaType,
                            )
                        },
                    )
                )
                uiState.value = uiState.value.copy(
                    messages = pendingBootstrapMessages(),
                    isLoadingMessages = false,
                    isLoadingOlderMessages = false,
                    hasMoreOlderMessages = false,
                    isStreaming = true,
                    isAgentTyping = true,
                    error = null,
                    conversationState = ConversationState.NoConversation,
                )
            }

            var latestConversationId = priorConversationId
            var swapEvaluated = false
            var accumulatedAssistantPreview = ""
            var sawAssistantPayload = false
            var migratedToTimeline = priorConversationId != null
            try {
                clientModeChatSender.streamMessage(
                    screenAgentId = agentId,
                    text = outboundText,
                    conversationId = priorConversationId,
                ).collect { chunk ->
                    chunk.conversationId?.takeIf { it.isNotBlank() }?.let { conversationId ->
                        latestConversationId = conversationId

                        val isTextPayload = !chunk.text.isNullOrEmpty() &&
                            chunk.event != BotStreamEvent.REASONING &&
                            chunk.event != BotStreamEvent.TOOL_CALL &&
                            chunk.event != BotStreamEvent.TOOL_RESULT

                        if (isTextPayload) {
                            accumulatedAssistantPreview += chunk.text.orEmpty()
                            submitNotificationCandidate(
                                conversationId = conversationId,
                                messageId = assistantMessageId,
                                previewText = accumulatedAssistantPreview,
                                phase = NotificationCandidatePhase.Partial,
                                isFinal = false,
                            )
                        }
                        if (!swapEvaluated) {
                            swapEvaluated = true
                            if (priorConversationId != null && priorConversationId != conversationId) {
                                showConversationSwap(
                                    ClientModeConversationSwap(
                                        requestedConversationId = priorConversationId,
                                        newConversationId = conversationId,
                                    )
                                )
                                setRouteConversationId(conversationId)

                                runCatching {
                                    timelineRepository.appendClientModeLocal(
                                        conversationId = conversationId,
                                        content = text,
                                        attachments = attachments,
                                    )
                                    clearBootstrapUserMessage()
                                    setClientModeConversationId(conversationId)
                                    setActiveConversationId(conversationId)
                                    markClientModeBootstrapReady(conversationId)
                                    currentConversationTracker.setCurrent(conversationId)
                                    startTimelineObserver(conversationId)
                                    migratedToTimeline = true
                                }.onFailure { e ->
                                    android.util.Log.w(
                                        "AdminChatViewModel",
                                        "Conversation swap migration to new conv timeline failed; staying on old conv",
                                        e,
                                    )
                                }
                            }
                        }
                    }
                    if (!latestConversationId.isNullOrBlank()) {
                        val latest = latestConversationId.orEmpty()
                        setClientModeConversationId(latest)
                        setActiveConversationId(latest)
                        markClientModeBootstrapReady(latest)
                        currentConversationTracker.setCurrent(latest)
                        if (priorConversationId == null && !migratedToTimeline) {
                            migratedToTimeline = true
                            val newConvId = latestConversationId
                            if (newConvId != null) {
                                runCatching {
                                    timelineRepository.appendClientModeLocal(
                                        conversationId = newConvId,
                                        content = text,
                                        attachments = attachments,
                                    )
                                    replayPreConversationBuffer(
                                        conversationId = newConvId,
                                        assistantMessageId = assistantMessageId,
                                    )
                                    clearBootstrapUserMessage()
                                    startTimelineObserver(newConvId)
                                }.onFailure { e ->
                                    android.util.Log.w(
                                        "AdminChatViewModel",
                                        "Fresh-route migration to timeline failed; keeping bootstrap user echo only",
                                        e,
                                    )
                                }
                            }
                        }
                    }

                    if (chunkCarriesAssistantPayload(chunk)) sawAssistantPayload = true

                    if (!chunk.done) {
                        handleStreamChunk(
                            chunk = chunk,
                            assistantMessageId = assistantMessageId,
                            conversationId = latestConversationId?.takeIf { it.isNotBlank() },
                        )
                        return@collect
                    }

                    handleStreamChunk(
                        chunk = chunk,
                        assistantMessageId = assistantMessageId,
                        conversationId = latestConversationId?.takeIf { it.isNotBlank() },
                    )

                    latestConversationId?.takeIf { it.isNotBlank() }?.let { conversationId ->
                        if (accumulatedAssistantPreview.isNotBlank()) {
                            submitNotificationCandidate(
                                conversationId = conversationId,
                                messageId = assistantMessageId,
                                previewText = accumulatedAssistantPreview,
                                phase = NotificationCandidatePhase.Final,
                                isFinal = true,
                            )
                        }
                    }

                    val terminalError = if (!sawAssistantPayload && !chunk.aborted) {
                        "Agent returned no content. Try again or check the agent's status."
                    } else {
                        null
                    }

                    latestConversationId?.takeIf { it.isNotBlank() }?.let { conversationId ->
                        reconcileClientModeConversation(
                            conversationId = conversationId,
                            reason = "stream_done",
                        )
                    }

                    val prevState = uiState.value
                    uiState.value = collapseCompletedRunsIfStreamingFinished(
                        prevState,
                        prevState.copy(
                            conversationState = latestConversationId?.let { ConversationState.Ready(it) }
                                ?: ConversationState.NoConversation,
                            isStreaming = false,
                            isAgentTyping = false,
                            error = terminalError ?: prevState.error,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                uiState.value = uiState.value.copy(
                    conversationState = latestConversationId?.let { ConversationState.Ready(it) }
                        ?: uiState.value.conversationState,
                    isStreaming = false,
                    isAgentTyping = false,
                )
            } catch (e: Exception) {
                android.util.Log.e("AdminChatViewModel", "sendViaClientMode: stream exception", e)
                val recoverableConversationId = latestConversationId?.takeIf { it.isNotBlank() }
                if (recoverableConversationId != null) {
                    setClientModeConversationId(recoverableConversationId)
                    setActiveConversationId(recoverableConversationId)
                    markClientModeBootstrapReady(recoverableConversationId)
                    currentConversationTracker.setCurrent(recoverableConversationId)
                    startTimelineObserver(recoverableConversationId)
                    uiState.value = uiState.value.copy(
                        conversationState = ConversationState.Ready(recoverableConversationId),
                        error = "Connection lost. Reconnecting and checking for completed output…",
                        isStreaming = true,
                        isAgentTyping = true,
                    )
                    scope.launch {
                        val reconciled = reconcileClientModeConversation(
                            conversationId = recoverableConversationId,
                            reason = "stream_exception",
                        )
                        val prevState = uiState.value
                        uiState.value = collapseCompletedRunsIfStreamingFinished(
                            prevState,
                            prevState.copy(
                                conversationState = ConversationState.Ready(recoverableConversationId),
                                error = if (reconciled) null else "Connection lost. Could not confirm whether the run completed; retry or refresh this conversation.",
                                isStreaming = false,
                                isAgentTyping = false,
                            ),
                        )
                    }
                } else {
                    uiState.value = uiState.value.copy(
                        conversationState = uiState.value.conversationState,
                        error = "Client Mode send failed before a conversation was created. Your message is still shown here; retry when reconnected." +
                            (e.message?.let { " ($it)" } ?: ""),
                        isStreaming = false,
                        isAgentTyping = false,
                    )
                }
            } finally {
                resetPreConversationBuffer()
                isStreamInFlight = false
                streamStartedAtElapsedMs = 0L
                refreshContextWindow()
                if (streamJob?.isCancelled != false) {
                    streamJob = null
                }
            }
        }
        streamJob = job
        return job
    }

    private fun resetPreConversationBuffer() {
        pendingStreamSessionId = null
        pendingStreamChunks.clear()
    }

    private fun bufferPreConversationChunk(
        chunk: BotStreamChunk,
        assistantMessageId: String,
    ) {
        if (chunk.done && !chunkCarriesAssistantPayload(chunk)) return
        if (pendingStreamSessionId != assistantMessageId) {
            pendingStreamSessionId = assistantMessageId
            pendingStreamChunks.clear()
        }
        if (pendingStreamChunks.size >= MAX_PRE_CONVERSATION_CLIENT_MODE_CHUNKS) {
            pendingStreamChunks.removeFirst()
            Telemetry.event(
                "AdminChatVM", "clientMode.preConversationBuffer.dropOldest",
                "assistantMessageId" to assistantMessageId,
                "maxChunks" to MAX_PRE_CONVERSATION_CLIENT_MODE_CHUNKS,
                level = Telemetry.Level.WARN,
            )
        }
        pendingStreamChunks.addLast(chunk)
        Telemetry.event(
            "AdminChatVM", "clientMode.preConversationChunkBuffered",
            "assistantMessageId" to assistantMessageId,
            "event" to (chunk.event?.name ?: "null"),
            "hasText" to (chunk.text != null),
            "bufferedChunks" to pendingStreamChunks.size,
        )
    }

    private suspend fun replayPreConversationBuffer(
        conversationId: String,
        assistantMessageId: String,
    ) {
        if (pendingStreamSessionId != assistantMessageId) return
        if (pendingStreamChunks.isEmpty()) return
        val bufferedChunks = pendingStreamChunks.toList()
        var replayed = 0
        bufferedChunks.forEach { bufferedChunk ->
            runCatching {
                timelineRepository.upsertClientModeStreamChunk(
                    conversationId = conversationId,
                    chunk = bufferedChunk.toTimelineStreamChunk(),
                    assistantMessageId = assistantMessageId,
                )
            }.onSuccess {
                replayed++
            }.onFailure {
                logTimelineUpsertFailure(
                    t = it,
                    kind = bufferedChunk.event?.name ?: "ASSISTANT",
                    localId = assistantMessageId,
                )
            }
        }
        resetPreConversationBuffer()
        Telemetry.event(
            "AdminChatVM", "clientMode.preConversationBufferReplayed",
            "conversationId" to conversationId,
            "assistantMessageId" to assistantMessageId,
            "replayedChunks" to replayed,
            "droppedChunks" to (bufferedChunks.size - replayed),
        )
    }

    private suspend fun handleStreamChunk(
        chunk: BotStreamChunk,
        assistantMessageId: String,
        conversationId: String? = null,
    ) {
        if (conversationId != null) {
            runCatching {
                timelineRepository.upsertClientModeStreamChunk(
                    conversationId = conversationId,
                    chunk = chunk.toTimelineStreamChunk(),
                    assistantMessageId = assistantMessageId,
                )
            }.onFailure {
                logTimelineUpsertFailure(
                    t = it,
                    kind = chunk.event?.name ?: "ASSISTANT",
                    localId = assistantMessageId,
                )
            }
            return
        }
        bufferPreConversationChunk(chunk, assistantMessageId)
    }

    private fun chunkCarriesAssistantPayload(chunk: BotStreamChunk): Boolean {
        if (!chunk.text.isNullOrEmpty()) return true
        return when (chunk.event) {
            BotStreamEvent.TOOL_CALL,
            BotStreamEvent.TOOL_RESULT,
            BotStreamEvent.REASONING -> true
            BotStreamEvent.CONVERSATION_SWAP,
            BotStreamEvent.ASSISTANT, null -> false
        }
    }

    private fun submitNotificationCandidate(
        conversationId: String,
        messageId: String,
        previewText: String,
        phase: NotificationCandidatePhase,
        isFinal: Boolean,
    ) {
        notificationDeliveryCoordinator.submit(
            NotificationDeliveryCandidate(
                conversationId = conversationId,
                agentId = agentId,
                agentName = uiState.value.agentName,
                conversationSummary = null,
                messageId = messageId,
                runId = null,
                source = NotificationCandidateSource.WebsocketClientMode,
                phase = phase,
                previewText = previewText,
                isFinal = isFinal,
            ),
        )
    }

    private suspend fun reconcileClientModeConversation(
        conversationId: String,
        reason: String,
    ): Boolean = runCatching {
        timelineRepository.reconcileRecentMessages(conversationId, "client_mode_$reason")
        // letta-mobile-a7ij: when SSE wins the race and reconcile appends a
        // Confirmed before the harness writes its matching Local, the orphan
        // Local stays in the timeline and renders as a duplicate bubble.
        // Re-run fuzzy collapse here — same pattern as NotificationReplyHandler
        // (letta-mobile-iuh6). Idempotent: no-op when nothing to absorb.
        timelineRepository.postHandlerCollapse(conversationId)
    }.onFailure { t ->
        Telemetry.error(
            "AdminChatVM", "clientMode.reconcileFailed", t,
            "conversationId" to conversationId,
            "reason" to reason,
        )
    }.isSuccess

    private fun logTimelineUpsertFailure(t: Throwable, kind: String, localId: String) {
        android.util.Log.w(
            "AdminChatViewModel",
            "Client Mode timeline upsert failed (kind=$kind, localId=$localId)",
            t,
        )
    }

    private companion object {
        private const val MAX_PRE_CONVERSATION_CLIENT_MODE_CHUNKS = 128
    }
}

internal fun BotStreamChunk.toTimelineStreamChunk(): ClientModeStreamChunk =
    ClientModeStreamChunk(
        event = when (event) {
            BotStreamEvent.REASONING -> ClientModeStreamEvent.REASONING
            BotStreamEvent.TOOL_CALL -> ClientModeStreamEvent.TOOL_CALL
            BotStreamEvent.TOOL_RESULT -> ClientModeStreamEvent.TOOL_RESULT
            BotStreamEvent.ASSISTANT -> ClientModeStreamEvent.ASSISTANT
            BotStreamEvent.CONVERSATION_SWAP, null -> null
        },
        text = text,
        uuid = uuid,
        toolCallId = toolCallId,
        toolName = toolName,
        toolInput = toolInput?.toString(),
        toolCalls = toolCalls.orEmpty(),
        isError = isError,
        done = done,
    )

private fun buildClientModeOutboundText(
    text: String,
    attachments: List<MessageContentPart.Image>,
): String = if (attachments.isEmpty()) {
    text
} else {
    buildContentParts(text, attachments).toJsonArray().toString()
}
