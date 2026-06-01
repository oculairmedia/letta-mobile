package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.runtime.toRuntimeEventDrafts
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Owns the admin-shim mobile WebSocket send path. */
internal class WsChatSendCoordinator(
    private val scope: CoroutineScope,
    private val agentId: String,
    private val activeConfig: () -> LettaConfig?,
    private val wsChatBridge: WsChatBridge,
    private val timelineRepository: TimelineExternalTransportWriter,
    private val conversationRepository: IConversationRepository,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val clearComposerAfterSend: () -> Unit,
    private val activeConversationId: () -> String?,
    private val isFreshRoute: Boolean = false,
    private val setActiveConversationId: (String) -> Unit,
    private val startTimelineObserver: (String) -> Unit,
    private val clientVersionProvider: ChatClientVersionProvider,
    private val backendDescriptor: () -> BackendDescriptor? = { null },
    private val runtimeEventSink: suspend (List<RuntimeEventDraft>) -> Unit = {},
) {
    @Volatile private var activeWsConversationId: String? = null
    @Volatile private var activeWsOtid: String? = null
    // letta-mobile-i8iw: lcp-cv3 contract — stop_reason and usage_statistics
    // are first-wins per turn on the shim. We capture once and drop later
    // duplicates defensively (drop with telemetry rather than overwriting).
    @Volatile private var activeWsTurnId: String? = null
    @Volatile private var stopReasonForTurn: String? = null
    @Volatile private var usageRecordedForTurn: Boolean = false
    // lcp-axv: failed turns emit `error` THEN `turn_done(status="failed")`
    // in lock-step. We buffer the error message and only flip UI state when
    // TurnDone arrives, so the "agent typing" indicator clears in sync with
    // the actual end-of-turn signal.
    @Volatile private var bufferedErrorMessage: String? = null
    private val preConversationMessageDeltas = ArrayDeque<LettaMessage>()
    private val pendingSendLock = Any()
    private val pendingSends = ArrayDeque<PendingWsSend>()
    @Volatile private var pendingConversationBootstrapLocal: PendingWsSend? = null

    init {
        scope.launch {
            wsChatBridge.events.collect { event -> handleEvent(event) }
        }
    }

    fun send(
        text: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): Job = scope.launch {
        val timer = Telemetry.startTimer("AdminChatVM", "send.ws.enqueue")
        val config = activeConfig()
        if (config == null) {
            failSend("No active backend is configured")
            return@launch
        }
        if (config.accessToken.isNullOrBlank()) {
            failSend("Admin-shim WebSocket requires an API token")
            return@launch
        }
        // lcp-dlj: multimodal sends now flow through content_parts. The
        // shim hard-caps the JSON-encoded payload at 10 MB; the client-
        // side downsample (≤ 4 images, ≤ 1568px longest side, ≤ 2 MB raw
        // each) is enforced at the composer attachment step before we
        // get here (TODO: letta-mobile-i9zz once filed). If the shim
        // still trips its cap we surface protocol_violation as a one-
        // shot toast via the standard Error path.

        // letta-mobile-wdrc: when a fresh route already has a live WS,
        // ask the shim to mint the conversation inside send_message. If
        // the socket is not live yet, keep vcky's REST pre-create path as
        // the compatibility fallback before connecting and sending.
        val currentConversationId = activeConversationId()
        val startNewConversation = isFreshRoute &&
            currentConversationId == null &&
            wsChatBridge.isConnected()
        val conversationId = when {
            currentConversationId != null -> currentConversationId
            startNewConversation -> NEW_CONVERSATION_PLACEHOLDER
            else -> runCatching {
                conversationRepository.createConversation(AgentId(agentId)).id.value
            }.getOrElse { err ->
                Telemetry.error("AdminChatVM", "ws.send.createConversationFailed", err)
                failSend("Failed to create a new conversation: ${err.message ?: "unknown"}")
                timer.stop("accepted" to false, "reason" to "create_failed")
                return@launch
            }
        }
        if (!startNewConversation) {
            activeWsConversationId = conversationId
            setActiveConversationId(conversationId)
            startTimelineObserver(conversationId)
        }

        val connected = ensureConnected(config)
        if (!connected) {
            failSend("Admin-shim WebSocket is not connected")
            timer.stop("accepted" to false, "reason" to "not_connected")
            return@launch
        }

        val pending = PendingWsSend(
            conversationId = conversationId,
            text = text,
            attachments = attachments,
            otid = "cm-android-${UUID.randomUUID()}",
            startNewConversation = startNewConversation,
        )
        val accepted = dispatchPendingSend(pending, appendOptimisticLocal = true)
        if (!accepted && startNewConversation) {
            uiState.value = uiState.value.copy(
                error = "WebSocket is busy; wait for the current turn to finish",
            )
            timer.stop("accepted" to false, "reason" to "busy_start_new")
            return@launch
        }
        if (!accepted && !enqueuePendingSend(pending)) {
            uiState.value = uiState.value.copy(
                error = "WebSocket send queue is full; wait for the current turn to finish",
            )
            timer.stop("accepted" to false, "reason" to "busy")
            return@launch
        }
        timer.stop(
            "accepted" to true,
            "conversationId" to conversationId,
            "otid" to pending.otid,
            "attachments" to attachments.size,
            "queued" to !accepted,
        )
    }

    fun cancel(): Boolean {
        val conversationId = activeConversationId() ?: activeWsConversationId ?: return false
        val accepted = wsChatBridge.cancel(conversationId)
        if (accepted) {
            val dropped = removePendingSends(conversationId)
            if (dropped.isNotEmpty()) {
                scope.launch { markPendingSendsFailed(dropped, "cancel", conversationId) }
            }
        }
        return accepted
    }

    private suspend fun dispatchPendingSend(
        pending: PendingWsSend,
        appendOptimisticLocal: Boolean,
    ): Boolean {
        val accepted = wsChatBridge.send(
            agentId = agentId,
            conversationId = pending.conversationId,
            text = pending.text,
            otid = pending.otid,
            attachments = pending.attachments,
            startNewConversation = pending.startNewConversation,
        )
        if (!accepted) return false

        activeWsOtid = pending.otid
        activeWsConversationId = pending.conversationId.takeIf { it.isNotBlank() }
        if (appendOptimisticLocal) {
            if (pending.startNewConversation) {
                pendingConversationBootstrapLocal = pending
            } else {
                timelineRepository.appendExternalTransportLocal(
                    conversationId = pending.conversationId,
                    content = pending.text,
                    otid = pending.otid,
                    attachments = pending.attachments,
                )
            }
            clearComposerAfterSend()
        }
        uiState.value = uiState.value.copy(
            conversationState = pending.conversationId
                .takeIf { it.isNotBlank() }
                ?.let { ConversationState.Ready(it) }
                ?: uiState.value.conversationState,
            isStreaming = true,
            isAgentTyping = true,
            error = null,
        )
        return true
    }

    private suspend fun enqueuePendingSend(pending: PendingWsSend): Boolean {
        val queued = synchronized(pendingSendLock) {
            if (pendingSends.size >= MAX_PENDING_SENDS) {
                false
            } else {
                pendingSends.addLast(pending)
                true
            }
        }
        if (!queued) {
            Telemetry.event(
                "AdminChatVM", "ws.queue.dropped",
                "conversationId" to pending.conversationId,
                "otid" to pending.otid,
                "capacity" to MAX_PENDING_SENDS,
            )
            return false
        }
        timelineRepository.appendExternalTransportLocal(
            conversationId = pending.conversationId,
            content = pending.text,
            otid = pending.otid,
            attachments = pending.attachments,
        )
        clearComposerAfterSend()
        uiState.value = uiState.value.copy(
            conversationState = ConversationState.Ready(pending.conversationId),
            isStreaming = true,
            isAgentTyping = true,
            error = null,
        )
        Telemetry.event(
            "AdminChatVM", "ws.send.enqueued",
            "conversationId" to pending.conversationId,
            "otid" to pending.otid,
            "queueDepth" to pendingQueueDepth(),
        )
        return true
    }

    private suspend fun drainPendingSend() {
        val pending = synchronized(pendingSendLock) { pendingSends.removeFirstOrNull() } ?: return
        Telemetry.event(
            "AdminChatVM", "ws.send.dequeued",
            "conversationId" to pending.conversationId,
            "otid" to pending.otid,
            "queueDepth" to pendingQueueDepth(),
        )
        if (!dispatchPendingSend(pending, appendOptimisticLocal = false)) {
            synchronized(pendingSendLock) { pendingSends.addFirst(pending) }
            Telemetry.event(
                "AdminChatVM", "ws.send.dequeueBlocked",
                "conversationId" to pending.conversationId,
                "otid" to pending.otid,
                "queueDepth" to pendingQueueDepth(),
            )
            // Avoid a tight loop if TurnDone and the transport in-flight flag race.
            delay(DEQUEUE_RETRY_DELAY_MS)
        }
    }

    private suspend fun clearPendingSends(reason: String) {
        pendingConversationBootstrapLocal = null
        val dropped = removeAllPendingSends()
        markPendingSendsFailed(dropped, reason, conversationId = null)
    }

    private fun removePendingSends(conversationId: String): List<PendingWsSend> {
        if (pendingConversationBootstrapLocal?.conversationId == conversationId) {
            pendingConversationBootstrapLocal = null
        }
        return synchronized(pendingSendLock) {
            val matching = mutableListOf<PendingWsSend>()
            val retained = ArrayDeque<PendingWsSend>()
            while (true) {
                val pending = pendingSends.removeFirstOrNull() ?: break
                if (pending.conversationId == conversationId) {
                    matching.add(pending)
                } else {
                    retained.addLast(pending)
                }
            }
            while (true) {
                pendingSends.addLast(retained.removeFirstOrNull() ?: break)
            }
            matching
        }
    }

    private fun removeAllPendingSends(): List<PendingWsSend> = synchronized(pendingSendLock) {
        val drained = mutableListOf<PendingWsSend>()
        while (true) {
            val pending = pendingSends.removeFirstOrNull() ?: break
            drained.add(pending)
        }
        drained
    }

    private suspend fun markPendingSendsFailed(
        dropped: List<PendingWsSend>,
        reason: String,
        conversationId: String?,
    ) {
        if (dropped.isEmpty()) return
        dropped.forEach { pending ->
            timelineRepository.markExternalTransportLocalFailed(pending.conversationId, pending.otid)
        }
        val attrs = buildList<Pair<String, Any?>> {
            add("reason" to reason)
            if (conversationId != null) add("conversationId" to conversationId)
            add("count" to dropped.size)
        }
        Telemetry.event(
            "AdminChatVM", "ws.queue.cleared",
            *attrs.toTypedArray(),
        )
    }

    private fun pendingQueueDepth(): Int = synchronized(pendingSendLock) { pendingSends.size }

    private suspend fun ensureConnected(config: LettaConfig): Boolean {
        if (wsChatBridge.isConnected()) return true
        runCatching {
            wsChatBridge.connect(
                baseShimUrl = config.serverUrl,
                token = config.accessToken.orEmpty(),
                deviceId = "android-letta-mobile",
                clientVersion = clientVersionProvider.clientVersion,
            )
        }.onFailure { error ->
            Telemetry.error("AdminChatVM", "ws.connect.failed", error)
            return false
        }
        return withTimeoutOrNull(CONNECT_WAIT_MS) {
            wsChatBridge.awaitConnected()
            true
        } ?: false
    }

    internal suspend fun handleEvent(event: WsTimelineEvent) {
        when (event) {
            is WsTimelineEvent.TurnStarted -> {
                activeWsConversationId = event.conversationId
                activeWsTurnId = event.turnId
                stopReasonForTurn = null
                usageRecordedForTurn = false
                bufferedErrorMessage = null
                recordRuntimeEvent(event)
                setActiveConversationId(event.conversationId)
                startTimelineObserver(event.conversationId)
                uiState.value = uiState.value.copy(
                    conversationState = ConversationState.Ready(event.conversationId),
                    isStreaming = true,
                    isAgentTyping = true,
                    error = null,
                )
                pendingConversationBootstrapLocal?.let { pending ->
                    timelineRepository.appendExternalTransportLocal(
                        conversationId = event.conversationId,
                        content = pending.text,
                        otid = pending.otid,
                        attachments = pending.attachments,
                    )
                    pendingConversationBootstrapLocal = null
                }
                drainPreConversationMessages(event.conversationId)
            }
            is WsTimelineEvent.MessageDelta -> {
                val conversationId = activeWsConversationId ?: activeConversationId()
                if (conversationId == null) {
                    preConversationMessageDeltas.addLast(event.message)
                    return
                }
                recordRuntimeEvent(event, conversationIdOverride = conversationId)
                timelineRepository.ingestExternalTransportMessage(conversationId, event.message)
            }
            is WsTimelineEvent.StopReason -> {
                recordRuntimeEvent(event)
                // lcp-cv3 §end-of-turn ordering: stop_reason is first-wins on
                // the shim. Defensive guard — log duplicates rather than overwrite.
                val previous = stopReasonForTurn
                if (previous != null) {
                    Telemetry.event(
                        "AdminChatVM", "ws.stopReason.duplicate",
                        "previous" to previous,
                        "received" to event.stopReason,
                        "turnId" to event.turnId,
                    )
                } else {
                    stopReasonForTurn = event.stopReason
                    Telemetry.event(
                        "AdminChatVM", "ws.stopReason",
                        "value" to event.stopReason,
                        "turnId" to event.turnId,
                        "runId" to event.runId,
                    )
                }
                markTurnVisuallyComplete(reason = "stopReason")
            }
            is WsTimelineEvent.UsageStatistics -> {
                recordRuntimeEvent(event)
                // lcp-cv3 §end-of-turn ordering: usage_statistics is first-wins
                // on the shim. Multi-step turns may produce per-step usage; the
                // run-level record reflects the first. Drop subsequent ones.
                if (usageRecordedForTurn) {
                    Telemetry.event(
                        "AdminChatVM", "ws.usage.duplicate",
                        "turnId" to event.turnId,
                    )
                } else {
                    usageRecordedForTurn = true
                    uiState.value = uiState.value.copy(
                        promptTokens = event.promptTokens.toInt(),
                        completionTokens = event.completionTokens.toInt(),
                        totalTokens = event.totalTokens.toInt(),
                    )
                    Telemetry.event(
                        "AdminChatVM", "ws.usage",
                        "prompt" to event.promptTokens,
                        "completion" to event.completionTokens,
                        "total" to event.totalTokens,
                        "turnId" to event.turnId,
                        "runId" to event.runId,
                    )
                }
            }
            is WsTimelineEvent.TurnDone -> {
                val conversationId = activeWsConversationId ?: defaultShimConversationId(agentId)
                recordRuntimeEvent(event, conversationIdOverride = conversationId)
                if (event.lossy) {
                    Telemetry.event(
                        "AdminChatVM", "ws.turnDone.lossy",
                        "dropCount" to event.dropCount,
                        "turnId" to event.turnId,
                        "runId" to event.runId,
                    )
                }
                if (event.lossy) activeWsOtid?.let { otid ->
                    timelineRepository.reconcileExternalTransportSend(
                        conversationId = conversationId,
                        agentId = agentId,
                        externalConversationId = conversationId,
                        otid = otid,
                    )
                }
                // letta-mobile-9hcg: flip the optimistic Local user bubble
                // from SENDING→SENT on every TurnDone. Without this, the
                // Local appended in [appendExternalTransportLocal] stays
                // SENDING for the lifetime of the cached timeline, which
                // keeps ChatTimelineObserver's isStreaming gate latched
                // and produces a typing-indicator flap on the next emit.
                activeWsOtid?.let { otid ->
                    timelineRepository.markExternalTransportLocalSent(conversationId, otid)
                }
                // lcp-axv: error-then-turn_done arrives in lock-step on failed
                // turns. Prefer the buffered error message from the preceding
                // Error frame when status="failed"; fall back to a generic
                // string if upstream skipped the error frame. Cancellation is
                // user-initiated and explicitly never sets an error banner.
                val stopReasonError = stopReasonForTurn.equals("error", ignoreCase = true)
                val nextError = when (event.status) {
                    "completed" -> bufferedErrorMessage
                        ?: if (stopReasonError) BARE_STOP_REASON_ERROR_MESSAGE else uiState.value.error
                    "cancelled" -> uiState.value.error
                    "failed" -> bufferedErrorMessage ?: "Turn failed"
                    else -> bufferedErrorMessage
                        ?: if (stopReasonError) {
                            BARE_STOP_REASON_ERROR_MESSAGE
                        } else {
                            "Turn ended unexpectedly (${event.status})"
                        }
                }
                uiState.value = uiState.value.copy(
                    isStreaming = false,
                    isAgentTyping = false,
                    error = nextError,
                )
                Telemetry.event(
                    "AdminChatVM", "ws.turnDone",
                    "status" to event.status,
                    "turnId" to event.turnId,
                    "runId" to event.runId,
                    "stopReason" to (stopReasonForTurn ?: ""),
                    "lossy" to event.lossy,
                )
                activeWsOtid = null
                activeWsTurnId = null
                stopReasonForTurn = null
                usageRecordedForTurn = false
                bufferedErrorMessage = null
                timelineRepository.clearExternalTransportActive(conversationId)
                drainPendingSend()
            }
            is WsTimelineEvent.Error -> {
                if (event.code == CURSOR_EXPIRED_ERROR_CODE) {
                    val conversationId = event.conversationId ?: activeWsConversationId ?: activeConversationId()
                    if (conversationId != null) {
                        runCatching {
                            timelineRepository.repairExpiredConversationCursor(
                                conversationId = conversationId,
                                fallbackSeq = event.lastSeq,
                            )
                        }.onSuccess {
                            Telemetry.event(
                                "AdminChatVM", "ws.cursorExpired.repaired",
                                "conversationId" to conversationId,
                                "afterSeq" to (event.afterSeq ?: -1L),
                                "oldestSeq" to (event.oldestSeq ?: -1L),
                                "lastSeq" to (event.lastSeq ?: -1L),
                            )
                            uiState.value = uiState.value.copy(error = null)
                        }.onFailure { t ->
                            Telemetry.error(
                                "AdminChatVM", "ws.cursorExpired.repairFailed", t,
                                "conversationId" to conversationId,
                            )
                            uiState.value = uiState.value.copy(
                                error = "Timeline repair failed: ${t.message ?: "unknown"}",
                            )
                        }
                        return
                    }
                }
                recordRuntimeEvent(event)
                // lcp-axv: stash the error and wait for the immediately-
                // following TurnDone to flip the UI. Surfacing the error
                // here would race with TurnDone and could leave isStreaming
                // / isAgentTyping stuck if TurnDone is delayed.
                bufferedErrorMessage = event.message.ifBlank { event.code }
                Telemetry.event(
                    "AdminChatVM", "ws.error.buffered",
                    "code" to event.code,
                    "message" to (event.message),
                    "turnId" to (event.turnId ?: ""),
                    "runId" to (event.runId ?: ""),
                )
            }
            is WsTimelineEvent.Disconnected -> {
                val conversationId = activeWsConversationId ?: activeConversationId()
                activeWsOtid?.let { otid ->
                    if (conversationId != null) {
                        timelineRepository.markExternalTransportLocalFailed(conversationId, otid)
                    } else {
                        Telemetry.event(
                            "AdminChatVM", "ws.activeSend.failedWithoutConversation",
                            "otid" to otid,
                            "disconnectCode" to event.code,
                        )
                    }
                }
                preConversationMessageDeltas.clear()
                clearPendingSends("disconnect")
                if (conversationId != null) {
                    timelineRepository.clearExternalTransportActive(conversationId)
                }
                val nextError = if (event.code == ChannelTransport.KEEPALIVE_PONG_TIMEOUT_CLOSE_CODE) {
                    null
                } else {
                    event.reason.ifBlank { "WebSocket disconnected" }
                }
                uiState.value = uiState.value.copy(
                    error = nextError,
                    isStreaming = false,
                    isAgentTyping = false,
                )
                activeWsOtid = null
                activeWsTurnId = null
                stopReasonForTurn = null
                usageRecordedForTurn = false
                bufferedErrorMessage = null
            }
            is WsTimelineEvent.UserActionOutcome -> recordRuntimeEvent(event)
        }
    }

    private fun failSend(message: String) {
        uiState.value = uiState.value.copy(
            error = message,
            isStreaming = false,
            isAgentTyping = false,
        )
    }

    private suspend fun markTurnVisuallyComplete(reason: String) {
        val conversationId = activeWsConversationId
            ?: activeConversationId()
            ?: defaultShimConversationId(agentId)
        activeWsOtid?.let { otid ->
            timelineRepository.markExternalTransportLocalSent(conversationId, otid)
        }
        timelineRepository.clearExternalTransportActive(conversationId)
        uiState.value = uiState.value.copy(
            isStreaming = false,
            isAgentTyping = false,
        )
        Telemetry.event(
            "AdminChatVM", "ws.turnVisuallyComplete",
            "conversationId" to conversationId,
            "reason" to reason,
            "turnId" to (activeWsTurnId ?: ""),
        )
    }

    private suspend fun recordRuntimeEvent(
        event: WsTimelineEvent,
        conversationIdOverride: String? = null,
    ) {
        val backend = backendDescriptor() ?: return
        val conversationId = (conversationIdOverride ?: activeWsConversationId ?: activeConversationId())
            ?.let(::ConversationId)
        val drafts = event.toRuntimeEventDrafts(
            backend = backend,
            fallbackAgentId = AgentId(agentId),
            fallbackConversationId = conversationId,
        )
        if (drafts.isEmpty()) return
        runCatching {
            runtimeEventSink(drafts)
        }.onFailure { error ->
            Telemetry.error("AdminChatVM", "runtimeEvent.recordFailed", error)
        }
    }

    private suspend fun drainPreConversationMessages(conversationId: String) {
        while (true) {
            val message = preConversationMessageDeltas.removeFirstOrNull() ?: return
            recordRuntimeEvent(WsTimelineEvent.MessageDelta(message), conversationIdOverride = conversationId)
            timelineRepository.ingestExternalTransportMessage(conversationId, message)
        }
    }

    private companion object {
        private const val CONNECT_WAIT_MS = 1_500L
        private const val MAX_PENDING_SENDS = 10
        private const val DEQUEUE_RETRY_DELAY_MS = 50L
        private const val NEW_CONVERSATION_PLACEHOLDER = ""
        private const val BARE_STOP_REASON_ERROR_MESSAGE =
            "Agent run failed after your message was sent. No error details were provided by the shim."
        private const val CURSOR_EXPIRED_ERROR_CODE = "cursor_expired"
        private fun defaultShimConversationId(agentId: String): String = "conv-default-$agentId"
    }

    private data class PendingWsSend(
        val conversationId: String,
        val text: String,
        val attachments: List<MessageContentPart.Image>,
        val otid: String,
        val startNewConversation: Boolean = false,
    )
}
