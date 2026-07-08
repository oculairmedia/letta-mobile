package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Platform-neutral SEND orchestration for the admin-shim mobile WebSocket
 * path, extracted from Android's `WsChatSendCoordinator` (letta-mobile-9ejia.5)
 * so desktop can reuse the exact same logic instead of reimplementing a thinner
 * send path.
 *
 * What lives here (pure orchestration, no Compose / Dagger / Android):
 *   - optimistic user-bubble construction via [TimelineExternalTransportWriter]
 *   - otid generation + reconciliation (mark-sent / mark-failed / reconcile)
 *   - the bounded pending-send queue + drain/clear/cancel semantics
 *   - turn-state transitions (active otid/turn id, first-wins stop_reason +
 *     usage guards, buffered-error-until-TurnDone ordering)
 *   - the [WsTimelineEvent] state machine, including the strict
 *     foreign-agent gate and pre-conversation delta buffering
 *
 * What is injected via seams (per-platform):
 *   - [ui]: UI-state mutations (Android maps these onto its Compose
 *     `ChatUiState`; desktop onto its own container)
 *   - [recordRuntimeEvent]: runtime-event recording (the
 *     `WsTimelineEvent.toRuntimeEventDrafts` mapper + sink live in the
 *     Android `core:data` layer today)
 *   - [otidGenerator]: opaque-transaction-id minting (Android uses a UUID)
 *   - [clientVersion]: connection handshake metadata
 *   - [scope]: the coroutine scope hosting the event collector + send launch
 *
 * Behavioral parity with the Android coordinator is the contract; the Android
 * `WsChatSendCoordinator` delegates to this class verbatim.
 */
class ChatSendCoordinator(
    private val scope: CoroutineScope,
    private val agentId: String,
    private val activeConfig: () -> LettaConfig?,
    private val wsChatBridge: WsChatBridge,
    private val timelineRepository: TimelineExternalTransportWriter,
    private val conversationRepository: IConversationRepository,
    private val ui: ChatSendUiSink,
    private val clearComposerAfterSend: () -> Unit,
    private val activeConversationId: () -> String?,
    private val setActiveConversationId: (String) -> Unit,
    private val startTimelineObserver: (String) -> Unit,
    private val clientVersion: () -> String,
    private val otidGenerator: () -> String,
    private val recordRuntimeEvent: suspend (event: WsTimelineEvent, conversationIdOverride: String?) -> Unit =
        { _, _ -> },
) {
    @Volatile private var activeWsConversationId: String? = null
    @Volatile private var activeWsOtid: String? = null
    @Volatile private var activeWsLocalConversationId: String? = null
    // letta-mobile-i8iw: lcp-cv3 contract — stop_reason and usage_statistics
    // are first-wins per turn on the shim. We capture once and drop later
    // duplicates defensively (drop with telemetry rather than overwriting).
    @Volatile private var activeWsTurnId: String? = null
    @Volatile private var activeWsRunId: String? = null
    private val activeAssistantMessageRunIds = linkedSetOf<String>()
    @Volatile private var stopReasonForTurn: String? = null
    @Volatile private var usageRecordedForTurn: Boolean = false
    // lcp-axv: failed turns emit `error` THEN `turn_done(status="failed")`
    // in lock-step. We buffer the error message and only flip UI state when
    // TurnDone arrives, so the "agent typing" indicator clears in sync with
    // the actual end-of-turn signal.
    @Volatile private var bufferedErrorMessage: String? = null
    private val preConversationMessageDeltas = ArrayDeque<LettaMessage>()
    private val pendingSendLock = SynchronizedObject()
    private val pendingSends = ArrayDeque<PendingWsSend>()
    @Volatile private var pendingConversationBootstrapLocal: PendingWsSend? = null
    private val seenBridgeEventLock = SynchronizedObject()
    private val seenBridgeEventKeys = ArrayDeque<String>()
    private val seenBridgeEventKeySet = mutableSetOf<String>()
    private val liveIngestLock = SynchronizedObject()
    private val lastLiveIngestByConversation = mutableMapOf<String, Long>()

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
        Telemetry.event(
            "IrohTrace", "coordinator.send.begin",
            "agentId" to agentId,
            "textLength" to text.length,
            "attachments" to attachments.size,
            "activeConversationId" to activeConversationId(),
        )
        val config = activeConfig()
        Telemetry.event(
            "IrohTrace", "coordinator.config",
            "hasConfig" to (config != null),
            "mode" to config?.mode?.name,
            "serverUrl" to config?.serverUrl,
            "hasToken" to !config?.accessToken.isNullOrBlank(),
        )
        if (config == null) {
            ui.onSendFailed("No active backend is configured")
            return@launch
        }
        if (config.accessToken.isNullOrBlank()) {
            ui.onSendFailed("Admin-shim WebSocket requires an API token")
            return@launch
        }
        // lcp-dlj: multimodal sends now flow through content_parts. The
        // shim hard-caps the JSON-encoded payload at 10 MB; the client-
        // side downsample (≤ 4 images, ≤ 1568px longest side, ≤ 2 MB raw
        // each) is enforced at the composer attachment step before we
        // get here (TODO: letta-mobile-i9zz once filed). If the shim
        // still trips its cap we surface protocol_violation as a one-
        // shot toast via the standard Error path.

        // The live shim requires every send_message to carry a concrete
        // conversation_id. Pre-create fresh conversations through REST instead
        // of sending a blank placeholder and relying on shim-side minting.
        val currentConversationId = activeConversationId()?.takeIf { it.isNotBlank() }
        val startNewConversation = false
        val conversationId = when {
            currentConversationId != null -> currentConversationId
            else -> runCatching {
                conversationRepository.createConversation(AgentId(agentId)).id.value
            }.getOrElse { err ->
                Telemetry.error("AdminChatVM", "ws.send.createConversationFailed", err)
                ui.onSendFailed("Failed to create a new conversation: ${err.message ?: "unknown"}")
                timer.stop("accepted" to false, "reason" to "create_failed")
                return@launch
            }
        }
        if (!startNewConversation) {
            activeWsConversationId = conversationId
            setActiveConversationId(conversationId)
            startTimelineObserver(conversationId)
        }

        Telemetry.event("IrohTrace", "coordinator.ensureConnected.begin", "conversationId" to conversationId)
        val connected = ensureConnected(config)
        Telemetry.event("IrohTrace", "coordinator.ensureConnected.done", "conversationId" to conversationId, "connected" to connected)
        if (!connected) {
            ui.onSendFailed("Admin-shim WebSocket is not connected")
            timer.stop("accepted" to false, "reason" to "not_connected")
            return@launch
        }

        val pending = PendingWsSend(
            conversationId = conversationId,
            text = text,
            attachments = attachments,
            otid = otidGenerator(),
            startNewConversation = startNewConversation,
        )
        Telemetry.event("IrohTrace", "coordinator.dispatch.begin", "conversationId" to conversationId, "otid" to pending.otid)
        val accepted = dispatchPendingSend(pending, appendOptimisticLocal = true)
        Telemetry.event("IrohTrace", "coordinator.dispatch.done", "conversationId" to conversationId, "otid" to pending.otid, "accepted" to accepted)
        if (!accepted && startNewConversation) {
            ui.onError("WebSocket is busy; wait for the current turn to finish")
            timer.stop("accepted" to false, "reason" to "busy_start_new")
            return@launch
        }
        if (!accepted && !enqueuePendingSend(pending)) {
            ui.onError("WebSocket send queue is full; wait for the current turn to finish")
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
        Telemetry.event(
            "IrohTrace", "dispatchPendingSend.bridgeSend.begin",
            "agentId" to agentId,
            "conversationId" to pending.conversationId,
            "otid" to pending.otid,
            "appendOptimisticLocal" to appendOptimisticLocal,
        )
        val accepted = wsChatBridge.send(
            agentId = agentId,
            conversationId = pending.conversationId,
            text = pending.text,
            otid = pending.otid,
            attachments = pending.attachments,
            startNewConversation = pending.startNewConversation,
        )
        Telemetry.event(
            "IrohTrace", "dispatchPendingSend.bridgeSend.done",
            "conversationId" to pending.conversationId,
            "otid" to pending.otid,
            "accepted" to accepted,
        )
        if (!accepted) return false

        activeWsOtid = pending.otid
        activeWsLocalConversationId = pending.conversationId.takeIf { it.isNotBlank() }
        activeWsConversationId = pending.conversationId.takeIf { it.isNotBlank() }
        if (appendOptimisticLocal) {
            if (pending.startNewConversation) {
                pendingConversationBootstrapLocal = pending
            } else {
                timelineRepository.appendExternalTransportLocal(
                    agentId = agentId,
                    conversationId = pending.conversationId,
                    content = pending.text,
                    otid = pending.otid,
                    attachments = pending.attachments,
                )
            }
            clearComposerAfterSend()
        }
        schedulePostSendReconcile(pending)
        ui.onSendDispatched(pending.conversationId.takeIf { it.isNotBlank() })
        return true
    }

    private fun schedulePostSendReconcile(pending: PendingWsSend) {
        val sentAtMillis = currentTimeMillis()
        scope.launch {
            for (delayMs in postSendReconcileDelaysMs) {
                delay(delayMs)
                if (hasLiveIngestSince(pending.conversationId, sentAtMillis)) {
                    Telemetry.event(
                        "AdminChatVM", "ws.postSendReconcile.skippedLiveStream",
                        "conversationId" to pending.conversationId,
                        "otid" to pending.otid,
                        "delayMs" to delayMs,
                    )
                    continue
                }
                runCatching {
                    timelineRepository.reconcileRecentMessages(
                        agentId = agentId,
                        conversationId = pending.conversationId,
                        reason = "post-send-$delayMs",
                        forceRefresh = true,
                    )
                }.onSuccess {
                    Telemetry.event(
                        "AdminChatVM", "ws.postSendReconcile.ok",
                        "conversationId" to pending.conversationId,
                        "otid" to pending.otid,
                        "delayMs" to delayMs,
                    )
                }.onFailure { error ->
                    Telemetry.error(
                        "AdminChatVM", "ws.postSendReconcile.failed", error,
                        "conversationId" to pending.conversationId,
                        "otid" to pending.otid,
                        "delayMs" to delayMs,
                    )
                }
            }
        }
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
            agentId = agentId,
            conversationId = pending.conversationId,
            content = pending.text,
            otid = pending.otid,
            attachments = pending.attachments,
        )
        clearComposerAfterSend()
        ui.onSendQueued(pending.conversationId)
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
            timelineRepository.markExternalTransportLocalFailed(agentId, pending.conversationId, pending.otid)
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
                clientVersion = clientVersion(),
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

    suspend fun handleEvent(event: WsTimelineEvent) {
        // letta-mobile-sfex6: strict agent scoping. wsChatBridge.events is a
        // GLOBAL flow — every per-(agentId,conversationId) coordinator collects
        // it, so a frame for one agent reaches every coordinator. When two
        // agents share the bare conversation id "default" (main + a subagent),
        // a foreign agent's TurnStarted would otherwise set THIS coordinator's
        // activeWsConversationId and its deltas would ingest into our timeline —
        // the cross-conversation leak. Drop any event that carries an explicit
        // agentId not matching ours BEFORE it can mutate active-turn state.
        // (MessageDelta/StopReason/etc. carry no agentId; they are scoped
        // transitively because activeWsConversationId is only ever set by a
        // TurnStarted that passed this gate.)
        val eventAgentId: String? = when (event) {
            is WsTimelineEvent.TurnStarted -> event.agentId
            is WsTimelineEvent.AgentUpdated -> event.agentId
            else -> null
        }
        if (eventAgentId != null && eventAgentId != agentId) {
            Telemetry.event(
                "AdminChatVM", "ws.event.foreignAgentDropped",
                "eventType" to (event::class.simpleName ?: ""),
                "eventAgentId" to eventAgentId,
                "boundAgentId" to agentId,
            )
            return
        }
        Telemetry.event(
            "IrohTrace", "coordinator.event",
            "type" to (event::class.simpleName ?: ""),
            "activeConversationId" to activeWsConversationId,
            "activeTurnId" to activeWsTurnId,
        )
        if (dropDuplicateBridgeEvent(event)) return
        when (event) {
            is WsTimelineEvent.TurnStarted -> {
                // Iroh run-id promotion re-emits TurnStarted for the SAME turn
                // once the real server run id replaces the synthetic
                // `iroh-run-*` placeholder. That is a run-id update, not a new
                // turn: resetting per-turn state here (stop/usage/error guards,
                // assistant run-id set) mid-turn corrupted post-tool
                // settlement and contributed to the flicker. Update the run id
                // and keep the turn state intact.
                if (event.turnId == activeWsTurnId && activeWsConversationId == event.conversationId) {
                    Telemetry.event(
                        "AdminChatVM", "ws.turnStarted.runPromoted",
                        "turnId" to event.turnId,
                        "previousRunId" to (activeWsRunId ?: ""),
                        "runId" to event.runId,
                    )
                    activeWsRunId = event.runId
                    return
                }
                activeWsConversationId = event.conversationId
                activeWsTurnId = event.turnId
                activeWsRunId = event.runId
                activeAssistantMessageRunIds.clear()
                stopReasonForTurn = null
                usageRecordedForTurn = false
                bufferedErrorMessage = null
                recordRuntimeEvent(event, activeWsConversationId)
                setActiveConversationId(event.conversationId)
                startTimelineObserver(event.conversationId)
                ui.onTurnStarted(event.conversationId)
                pendingConversationBootstrapLocal?.let { pending ->
                    timelineRepository.appendExternalTransportLocal(
                        agentId = agentId,
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
                com.letta.mobile.util.Telemetry.event(
                    "IrohGate", "gate3.coordinatorMessageDelta",
                    "resolvedConversationId" to conversationId,
                    "messageId" to event.message.id,
                    "messageType" to event.message.messageType,
                    "isReplay" to event.isReplay,
                )
                if (conversationId == null) {
                    preConversationMessageDeltas.addLast(event.message)
                    return
                }
                recordRuntimeEvent(event, conversationId)
                rememberActiveAssistantMessageRunId(event.message)
                timelineRepository.ingestExternalTransportMessage(agentId, conversationId, event.message)
                if (!event.isReplay) {
                    rememberLiveIngest(conversationId)
                    ui.onMessageDelta(conversationId)
                }
            }
            is WsTimelineEvent.StopReason -> {
                recordRuntimeEvent(event, activeWsConversationId)
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
                recordRuntimeEvent(event, activeWsConversationId)
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
                    ui.onUsage(
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
                finishActiveTurn(
                    status = event.status,
                    runId = event.runId,
                    turnId = event.turnId,
                    lossy = event.lossy,
                    dropCount = event.dropCount,
                    reason = "turnDone",
                    recordEvent = event,
                )
            }
            is WsTimelineEvent.SubscribeDone -> {
                if (activeWsOtid != null || ui.isStreaming()) {
                    finishActiveTurn(
                        status = event.status,
                        runId = event.runId,
                        turnId = activeWsTurnId.orEmpty(),
                        lossy = false,
                        dropCount = 0L,
                        reason = "subscribeDone",
                        recordEvent = null,
                    )
                }
            }
            is WsTimelineEvent.Error -> {
                if (event.code == CURSOR_EXPIRED_ERROR_CODE) {
                    val conversationId = event.conversationId ?: activeWsConversationId ?: activeConversationId()
                    if (conversationId != null) {
                        runCatching {
                            timelineRepository.repairExpiredConversationCursorScoped(
                                agentId = agentId,
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
                            ui.onError(null)
                        }.onFailure { t ->
                            Telemetry.error(
                                "AdminChatVM", "ws.cursorExpired.repairFailed", t,
                                "conversationId" to conversationId,
                            )
                            ui.onError("Timeline repair failed: ${t.message ?: "unknown"}")
                        }
                        return
                    }
                }
                recordRuntimeEvent(event, activeWsConversationId)
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
                if (event.willReconnect && !event.isAuthFailure) {
                    Telemetry.event(
                        "AdminChatVM", "ws.disconnected.transient",
                        "code" to event.code,
                        "attempt" to event.reconnectAttempt,
                    )
                    ui.onTransientDisconnect(hasActiveSend = activeWsOtid != null)
                    return
                }
                failActiveTurnForDisconnect(event)
            }
            is WsTimelineEvent.GoalsUpdated -> Unit
            is WsTimelineEvent.AgentUpdated -> Unit
            is WsTimelineEvent.UserActionOutcome -> recordRuntimeEvent(event, activeWsConversationId)
        }
    }

    private fun dropDuplicateBridgeEvent(event: WsTimelineEvent): Boolean {
        val key = bridgeEventKey(event) ?: return false
        val isDuplicate = synchronized(seenBridgeEventLock) {
            if (key in seenBridgeEventKeySet) {
                true
            } else {
                seenBridgeEventKeySet.add(key)
                seenBridgeEventKeys.addLast(key)
                while (seenBridgeEventKeys.size > MAX_SEEN_BRIDGE_EVENTS) {
                    seenBridgeEventKeySet.remove(seenBridgeEventKeys.removeFirst())
                }
                false
            }
        }
        if (isDuplicate) {
            Telemetry.event(
                "AdminChatVM", "ws.event.exactDuplicateDropped",
                "eventType" to (event::class.simpleName ?: ""),
                "keyHash" to key.hashCode().toString(),
            )
        }
        return isDuplicate
    }

    private fun bridgeEventKey(event: WsTimelineEvent): String? = when (event) {
        is WsTimelineEvent.TurnStarted -> "started|${event.conversationId}|${event.turnId}|${event.runId}"
        is WsTimelineEvent.MessageDelta -> {
            val conversationId = activeWsConversationId ?: activeConversationId().orEmpty()
            val message = event.message
            "message|$conversationId|${message.id}|${message.messageType}|${message.runId.orEmpty()}|${messageContentForDedupe(message)}"
        }
        is WsTimelineEvent.StopReason -> "stop|${event.turnId}|${event.runId}|${event.stopReason}"
        is WsTimelineEvent.UsageStatistics -> "usage|${event.turnId}|${event.runId}|${event.promptTokens}|${event.completionTokens}|${event.totalTokens}"
        is WsTimelineEvent.TurnDone -> "done|${event.turnId}|${event.runId}|${event.status}|${event.lossy}|${event.dropCount}"
        is WsTimelineEvent.Error -> "error|${event.conversationId.orEmpty()}|${event.turnId.orEmpty()}|${event.runId.orEmpty()}|${event.code}|${event.message}"
        is WsTimelineEvent.UserActionOutcome -> "action|${event.frameId}|${event.actionId.orEmpty()}|${event.outcome}|${event.reason.orEmpty()}"
        else -> null
    }

    private fun messageContentForDedupe(message: LettaMessage): String = when (message) {
        is AssistantMessage -> message.content
        is UserMessage -> message.content
        is SystemMessage -> message.content
        is ReasoningMessage -> message.reasoning
        is ToolCallMessage -> message.effectiveToolCalls.joinToString(separator = "|") { it.effectiveId + ":" + (it.name ?: "") }
        is ToolReturnMessage -> message.toolCallId.orEmpty() + ":" + message.toolReturn.funcResponse.orEmpty()
        else -> message.date.orEmpty() + ":" + message.seqId.toString()
    }

    private fun rememberLiveIngest(conversationId: String) {
        synchronized(liveIngestLock) {
            lastLiveIngestByConversation[conversationId] = currentTimeMillis()
        }
    }

    private fun hasLiveIngestSince(conversationId: String, sinceMillis: Long): Boolean = synchronized(liveIngestLock) {
        (lastLiveIngestByConversation[conversationId] ?: Long.MIN_VALUE) >= sinceMillis
    }

    private fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private fun rememberActiveAssistantMessageRunId(message: LettaMessage) {
        if (message !is AssistantMessage) return
        val messageRunId = message.runId?.takeIf { it.isNotBlank() } ?: return
        activeAssistantMessageRunIds += messageRunId
        while (activeAssistantMessageRunIds.size > MAX_ACTIVE_ASSISTANT_RUN_IDS) {
            activeAssistantMessageRunIds.remove(activeAssistantMessageRunIds.first())
        }
    }

    private suspend fun cleanupAbandonedAssistantFragmentsSafely(
        conversationId: String,
        runId: String?,
        turnId: String?,
        reason: String,
        candidateRunIds: Set<String> = emptySet(),
    ) {
        try {
            timelineRepository.cleanupAbandonedAssistantFragments(agentId, conversationId, runId, turnId, reason, candidateRunIds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Telemetry.error(
                "AdminChatVM", "cleanupAbandonedAssistantFragments.failed", error,
                "conversationId" to conversationId,
                "runId" to (runId ?: ""),
                "turnId" to (turnId ?: ""),
                "reason" to reason,
            )
        }
    }

    private suspend fun finishActiveTurn(
        status: String,
        runId: String,
        turnId: String,
        lossy: Boolean,
        dropCount: Long,
        reason: String,
        recordEvent: WsTimelineEvent.TurnDone?,
    ) {
        val conversationId = activeWsConversationId ?: defaultShimConversationId(agentId)
        if (status == "failed" || status == "cancelled") {
            cleanupAbandonedAssistantFragmentsSafely(
                conversationId = conversationId,
                runId = runId,
                turnId = turnId,
                reason = "turn_done_$status",
                candidateRunIds = activeCleanupCandidateRunIds(runId),
            )
        }
        if (recordEvent != null) {
            recordRuntimeEvent(recordEvent, conversationId)
        }
        if (lossy) {
            Telemetry.event(
                "AdminChatVM", "ws.turnDone.lossy",
                "dropCount" to dropCount,
                "turnId" to turnId,
                "runId" to runId,
            )
            activeWsOtid?.let { otid ->
                timelineRepository.reconcileExternalTransportSend(
                    conversationId = conversationId,
                    agentId = agentId,
                    externalConversationId = conversationId,
                    otid = otid,
                )
            }
        }
        activeWsOtid?.let { otid ->
            val localConversationId = activeWsLocalConversationId ?: conversationId
            if (status == "failed") {
                timelineRepository.markExternalTransportLocalFailed(agentId, localConversationId, otid)
            } else {
                timelineRepository.markExternalTransportLocalSent(agentId, localConversationId, otid)
            }
        }
        val stopReasonError = stopReasonForTurn.equals("error", ignoreCase = true)
        val nextError = when (status) {
            "completed" -> bufferedErrorMessage
                ?: if (stopReasonError) BARE_STOP_REASON_ERROR_MESSAGE else ui.currentError()
            "cancelled" -> ui.currentError()
            "failed" -> bufferedErrorMessage ?: "Turn failed"
            else -> bufferedErrorMessage
                ?: if (stopReasonError) BARE_STOP_REASON_ERROR_MESSAGE else "Turn ended unexpectedly ($status)"
        }
        ui.onTurnFinished(nextError)
        Telemetry.event(
            "AdminChatVM", "ws.turnComplete",
            "status" to status,
            "turnId" to turnId,
            "runId" to runId,
            "stopReason" to (stopReasonForTurn ?: ""),
            "lossy" to lossy,
            "reason" to reason,
        )
        activeWsOtid = null
        activeWsLocalConversationId = null
        activeWsTurnId = null
        activeWsRunId = null
        activeAssistantMessageRunIds.clear()
        stopReasonForTurn = null
        usageRecordedForTurn = false
        bufferedErrorMessage = null
        timelineRepository.clearExternalTransportActive(conversationId)
        drainPendingSend()
    }

    private suspend fun failActiveTurnForDisconnect(event: WsTimelineEvent.Disconnected) {
        val conversationId = activeWsConversationId ?: activeConversationId()
        activeWsOtid?.let { otid ->
            val localConversationId = activeWsLocalConversationId ?: conversationId
            if (localConversationId != null) {
                timelineRepository.markExternalTransportLocalFailed(agentId, localConversationId, otid)
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
            val cleanupRunId = activeWsRunId
            val cleanupTurnId = activeWsTurnId
            if (activeWsOtid != null || cleanupRunId != null || cleanupTurnId != null) {
                cleanupAbandonedAssistantFragmentsSafely(
                    conversationId = conversationId,
                    runId = cleanupRunId,
                    turnId = cleanupTurnId,
                    reason = "disconnect",
                    candidateRunIds = activeCleanupCandidateRunIds(cleanupRunId),
                )
            }
            timelineRepository.clearExternalTransportActive(conversationId)
        }
        ui.onDisconnectFailure(event.reason.ifBlank { "WebSocket disconnected" })
        activeWsOtid = null
        activeWsLocalConversationId = null
        activeWsTurnId = null
        activeWsRunId = null
        activeAssistantMessageRunIds.clear()
        stopReasonForTurn = null
        usageRecordedForTurn = false
        bufferedErrorMessage = null
    }

    private fun activeCleanupCandidateRunIds(primaryRunId: String?): Set<String> = buildSet {
        primaryRunId?.takeIf { it.isNotBlank() }?.let(::add)
        activeWsRunId?.takeIf { it.isNotBlank() }?.let(::add)
        activeAssistantMessageRunIds.mapNotNullTo(this) { it.takeIf(String::isNotBlank) }
    }

    private suspend fun markTurnVisuallyComplete(reason: String) {
        val conversationId = activeWsConversationId
            ?: activeConversationId()
            ?: defaultShimConversationId(agentId)
        activeWsOtid?.let { otid ->
            timelineRepository.markExternalTransportLocalSent(agentId, activeWsLocalConversationId ?: conversationId, otid)
        }
        timelineRepository.clearExternalTransportActive(agentId, conversationId)
        ui.onTurnVisuallyComplete()
        Telemetry.event(
            "AdminChatVM", "ws.turnVisuallyComplete",
            "conversationId" to conversationId,
            "reason" to reason,
            "turnId" to (activeWsTurnId ?: ""),
        )
    }

    private suspend fun drainPreConversationMessages(conversationId: String) {
        while (true) {
            val message = preConversationMessageDeltas.removeFirstOrNull() ?: return
            recordRuntimeEvent(WsTimelineEvent.MessageDelta(message), conversationId)
            rememberActiveAssistantMessageRunId(message)
            timelineRepository.ingestExternalTransportMessage(agentId, conversationId, message)
        }
    }

    internal companion object {
        private const val CONNECT_WAIT_MS = 1_500L
        private const val MAX_PENDING_SENDS = 10
        private const val MAX_SEEN_BRIDGE_EVENTS = 512
        private const val MAX_ACTIVE_ASSISTANT_RUN_IDS = 8
        private const val DEQUEUE_RETRY_DELAY_MS = 50L
        internal var postSendReconcileDelaysMs = longArrayOf(750L, 2_500L, 6_000L)
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
