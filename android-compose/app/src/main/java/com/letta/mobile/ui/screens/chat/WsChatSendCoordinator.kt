package com.letta.mobile.ui.screens.chat

import com.letta.mobile.BuildConfig
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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
    private val timelineRepository: TimelineRepository,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val clearComposerAfterSend: () -> Unit,
    private val activeConversationId: () -> String?,
    private val setActiveConversationId: (String) -> Unit,
    private val startTimelineObserver: (String) -> Unit,
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

        val conversationId = defaultShimConversationId(agentId)
        activeWsConversationId = conversationId
        setActiveConversationId(conversationId)
        startTimelineObserver(conversationId)

        val connected = ensureConnected(config)
        if (!connected) {
            failSend("Admin-shim WebSocket is not connected")
            timer.stop("accepted" to false, "reason" to "not_connected")
            return@launch
        }

        val otid = "cm-android-${UUID.randomUUID()}"
        val accepted = wsChatBridge.send(
            agentId = agentId,
            conversationId = conversationId,
            text = text,
            otid = otid,
            attachments = attachments,
        )
        if (!accepted) {
            failSend("A WebSocket chat turn is already in flight")
            timer.stop("accepted" to false, "reason" to "busy")
            return@launch
        }
        activeWsOtid = otid

        timelineRepository.appendExternalTransportLocal(
            conversationId = conversationId,
            content = text,
            otid = otid,
            attachments = attachments,
        )
        clearComposerAfterSend()
        uiState.value = uiState.value.copy(
            conversationState = ConversationState.Ready(conversationId),
            isStreaming = true,
            isAgentTyping = true,
        )
        timer.stop(
            "accepted" to true,
            "conversationId" to conversationId,
            "otid" to otid,
            "attachments" to attachments.size,
        )
    }

    fun cancel(): Boolean = wsChatBridge.cancel()

    private suspend fun ensureConnected(config: LettaConfig): Boolean {
        if (wsChatBridge.state.value is ChannelTransport.State.Connected) return true
        runCatching {
            wsChatBridge.connect(
                baseShimUrl = config.serverUrl,
                token = config.accessToken.orEmpty(),
                deviceId = "android-letta-mobile",
                clientVersion = "letta-mobile/${BuildConfig.VERSION_NAME} (android)",
            )
        }.onFailure { error ->
            Telemetry.error("AdminChatVM", "ws.connect.failed", error)
            return false
        }
        return withTimeoutOrNull(CONNECT_WAIT_MS) {
            wsChatBridge.state.filter { it is ChannelTransport.State.Connected }.first()
            true
        } ?: false
    }

    private suspend fun handleEvent(event: WsTimelineEvent) {
        when (event) {
            is WsTimelineEvent.TurnStarted -> {
                activeWsConversationId = event.conversationId
                activeWsTurnId = event.turnId
                stopReasonForTurn = null
                usageRecordedForTurn = false
                bufferedErrorMessage = null
                setActiveConversationId(event.conversationId)
                startTimelineObserver(event.conversationId)
                uiState.value = uiState.value.copy(
                    conversationState = ConversationState.Ready(event.conversationId),
                    isStreaming = true,
                    isAgentTyping = true,
                    error = null,
                )
            }
            is WsTimelineEvent.MessageDelta -> {
                val conversationId = activeWsConversationId ?: activeConversationId() ?: return
                timelineRepository.ingestExternalTransportMessage(conversationId, event.message)
            }
            is WsTimelineEvent.StopReason -> {
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
            }
            is WsTimelineEvent.UsageStatistics -> {
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
                // lcp-srk: reconcile from disk only when the shim signals it
                // dropped frames. Clean turns can skip the round-trip.
                if (event.lossy) {
                    Telemetry.event(
                        "AdminChatVM", "ws.turnDone.lossy",
                        "dropCount" to event.dropCount,
                        "turnId" to event.turnId,
                        "runId" to event.runId,
                    )
                    activeWsOtid?.let { otid ->
                        timelineRepository.reconcileExternalTransportSend(
                            conversationId = conversationId,
                            agentId = agentId,
                            externalConversationId = defaultShimConversationId(agentId),
                            otid = otid,
                        )
                    }
                }
                // lcp-axv: error-then-turn_done arrives in lock-step on failed
                // turns. Prefer the buffered error message from the preceding
                // Error frame when status="failed"; fall back to a generic
                // string if upstream skipped the error frame. Cancellation is
                // user-initiated and explicitly never sets an error banner.
                val nextError = when (event.status) {
                    "completed" -> uiState.value.error
                    "cancelled" -> uiState.value.error
                    "failed" -> bufferedErrorMessage ?: "Turn failed"
                    else -> bufferedErrorMessage ?: "Turn ended unexpectedly (${event.status})"
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
            }
            is WsTimelineEvent.Error -> {
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
                uiState.value = uiState.value.copy(
                    error = event.reason.ifBlank { "WebSocket disconnected" },
                    isStreaming = false,
                    isAgentTyping = false,
                )
            }
        }
    }

    private fun failSend(message: String) {
        uiState.value = uiState.value.copy(
            error = message,
            isStreaming = false,
            isAgentTyping = false,
        )
    }

    private companion object {
        private const val CONNECT_WAIT_MS = 1_500L
        private fun defaultShimConversationId(agentId: String): String = "conv-default-$agentId"
    }
}
