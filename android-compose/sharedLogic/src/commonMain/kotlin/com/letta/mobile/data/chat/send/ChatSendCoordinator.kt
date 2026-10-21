package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.IdFormats
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.transport.api.RedialWhileTurnActive
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.backendUrlTelemetryDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Platform-neutral SEND orchestration for the frame-channel (Iroh) send
 * path, extracted from Android's `WsChatSendCoordinator`
 * so desktop can reuse the exact same logic instead of reimplementing a thinner
 * send path.
 *
 * What lives here (pure orchestration, no Compose / Dagger / Android):
 *   - optimistic user-bubble construction via [TimelineExternalTransportWriter]
 *   - otid generation + reconciliation (mark-sent / mark-failed / reconcile)
 *   - the unbounded busy-send queue ([QueuedSendDriver])
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
    clearComposerAfterSend: () -> Unit,
    private val activeConversationId: () -> String?,
    private val setActiveConversationId: (String) -> Unit,
    private val startTimelineObserver: (String) -> Unit,
    private val clientVersion: () -> String,
    private val otidGenerator: () -> String,
    recordRuntimeEvents: suspend (List<ScopedRuntimeEvent>) -> Unit = {},
    private val prepareConversation: suspend (String) -> Unit = {},
) {
    // Send acceptance, transport events, and cleanup all mutate one ownership graph. Serializing
    // their suspend paths makes the lifecycle decision and the matching UI/OTID mutation atomic.
    private val turnStateMutex = Mutex()

    // One ownership registry; suspend lifecycle sequencing stays under the mutex above.
    private val turnRegistry = ConversationTurnRegistry {
        ConversationId(activeConversationId()?.takeIf { it.isNotBlank() } ?: IdFormats.defaultConversationIdFor(agentId))
    }
    private val foreground = ConversationChatUi(ui, activeConversationId, clearComposerAfterSend)
    // letta-mobile-1n5py: messages sent while their conversation is busy wait here, unbounded,
    // and run in order once the turn ahead ends. See [QueuedSendDriver].
    private val queuedSends = ChatSendQueue()
    private val queueTurns = CoordinatorQueueTurns(turnStateMutex, wsChatBridge, ::dispatchPendingSend)
    private val queueDriver = QueuedSendDriver(
        scope,
        queuedSends,
        queueTurns,
    )

    private val serverQueueMarks = ServerQueueMarks(
        queue = queuedSends,
        sendOfTurn = { turnId -> turnRegistry.statesSnapshot.firstOrNull { it.turnId == turnId }?.inFlightSend },
        sendOfConversation = { conversationId -> turnRegistry.peekState(ConversationId(conversationId))?.inFlightSend },
    )

    /** The send queue a chat screen shows and controls. */
    val sendQueue: ChatSendQueueControls = ChatSendQueueControls(scope, queueDriver, queuedSends.state)
    private val bridgeEventDeduplicator = BridgeEventDeduplicator()
    private val postSendReconciler = PostSendReconciler(
        scope = scope,
        agentId = agentId,
        timelineRepository = timelineRepository,
        delaysMs = { postSendReconcileDelaysMs },
    )
    private val runtimeEventBatcher = RuntimeEventBatcher(scope, recordRuntimeEvents)

    private val incoming = IncomingTurnProjection(AgentId(agentId), timelineRepository, turnRegistry, runtimeEventBatcher, foreground)
    private val terminalOwnership = TurnSettlementOwnership(turnRegistry, queuedSends)
    private val settlement = TurnSettlement(AgentId(agentId), timelineRepository, runtimeEventBatcher, terminalOwnership, foreground)

    /** This chat's agent-keyed subscription; hosts select it when it is on screen. */
    val eventSubscription = ChatSendEventSubscription(
        scope = scope,
        attachment = wsChatBridge.agentScopes.attachment(AgentId(agentId)) {
            turnRegistry.statesSnapshot.any { it.isTracking && !it.reachedTerminal } ||
                queuedSends.state.value.values.any { !it.isEmpty }
        },
        handle = { event -> handleEvent(event) },
    )

    init {
        scope.launch {
            wsChatBridge.redialWhileTurnActive.collect { event ->
                contained("RedialWhileTurnActive") { turnStateMutex.withLock { handleRedialWhileTurnActiveLocked(event) } }
            }
        }
    }

    /**
     * One frame must never take the process down. Everything a frame reaches reports a violated
     * expectation by throwing - the canonical timeline alone holds dozens of such fences - and
     * frames are dispatched on the main dispatcher, so an escaped failure killed the app mid-turn
     * and took the transport subscription with it. Contain per frame: that frame is lost and the
     * reconcile recovers its rows, while the turn and the stream survive.
     */
    private suspend fun contained(frame: String?, handle: suspend () -> Unit) {
        try {
            handle()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Telemetry.error(
                "AdminChatVM", "ws.event.handlerFailed", failure,
                "eventType" to (frame ?: ""),
                "agentId" to agentId,
            )
        }
    }

    /** Live state a stop/usage tail may mutate, after recording it; a retired turn's tail is only forwarded. */
    private fun liveTailState(event: WsTimelineEvent, tail: TurnTail): ConversationTurnState? {
        val state = if (!tail.turnId.isNullOrBlank()) {
            turnRegistry.resolveStateByTurn(tail)
        } else {
            turnRegistry.resolveStateByRun(tail)
                ?: turnRegistry.statesSnapshot.firstOrNull { tail.runId in it.settledRunIds }
        }
        when {
            state == null -> reportUnmatchedFrame(event, tail)
            !state.isRetiredTurn(tail) && !state.isRetiredRun(tail) -> {
                runtimeEventBatcher.enqueue(event, state.ownerConversationId)
                return state
            }
            else -> forwardRetiredTurnTail(runtimeEventBatcher, event, tail, state.ownerConversationId)
        }
        return null
    }

    /**
     * SENSING: a send starting for one conversation while
     * ANOTHER conversation's identity is still non-terminal is precisely the
     * overlap that used to evict state. Legal now — but recorded.
     */
    private fun reportCrossConversationSend(conversation: ConversationId) {
        val conversationId = conversation.value
        val others = turnRegistry.statesSnapshot.filter {
            it.conversationId != conversationId && it.isTracking && !it.reachedTerminal
        }
        if (others.isEmpty()) return
        Telemetry.event(
            "AdminChatVM", "ws.send.crossConversationOverlap",
            "conversationId" to conversationId,
            "otherConversations" to others.size,
            "otherTurnIds" to others.joinToString(",") { it.turnId ?: "<awaiting-turn-started>" },
        )
    }

    /**
     * SENSING: a frame that matches no tracked conversation
     * entry. Before keying, these silently landed on whatever the single global
     * happened to hold — i.e. they were misattributed rather than dropped.
     */
    private fun reportUnmatchedFrame(event: WsTimelineEvent, tail: TurnTail) {
        Telemetry.event(
            "AdminChatVM", "ws.event.unmatchedConversation",
            "eventType" to (event::class.simpleName ?: ""),
            "turnId" to (tail.turnId ?: ""),
            "runId" to (tail.runId ?: ""),
            "trackedConversations" to turnRegistry.trackedConversationCount,
        )
    }

    fun send(
        text: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): Job {
        // letta-mobile-dlbqq (Seam A): activeConversationId() is a LIVE getter
        // backed by savedState that a conversation switch mutates. Reading it
        // inside the launched coroutine let a switch landing between this
        // synchronous call and the coroutine body executing rebind the message
        // to the WRONG (newly-active) conversation. Seal the target id at the
        // synchronous call site so a later switch cannot rebind it. agentId is
        // already a construction-time constant and needs no capture.
        val targetConversationId = activeConversationId()
        // letta-mobile-ztuog: only the chat on screen sends, so a send claims the selection
        // (and so attaches) before its turn's first frame can arrive.
        eventSubscription.claimForSend()
        return scope.launch {
            turnStateMutex.withLock { sendInternalLocked(text, attachments, targetConversationId) }
        }
    }

    private suspend fun sendInternalLocked(
        text: String,
        attachments: List<MessageContentPart.Image>,
        targetConversationId: String?,
    ) {
        val timer = Telemetry.startTimer("AdminChatVM", "send.ws.enqueue")
        // dir4k: presentation can miss/reject a terminal fanout and remain
        // visually active after Iroh has already reached terminal. Before a
        // sequential send, heal that impossible state from transport ownership
        // so the new message is dispatched instead of queued behind a ghost turn.
        healStaleVisualPresence(targetConversationId?.let(::ConversationId))
        Telemetry.event(
            "IrohTrace", "coordinator.send.begin",
            "agentId" to agentId,
            "textLength" to text.length,
            "attachments" to attachments.size,
            "activeConversationId" to targetConversationId,
        )
        // No bearer-token gate: the Iroh transport authenticates the paired
        // peer by NodeID and ignores the token (d6e8g.9). The legacy shim WS
        // that needed one is gone (g70jb.4).
        val config = validatedActiveConfig(targetConversationId?.let(::ConversationId)) ?: return
        // Multimodal sends flow through content_parts; the client-side
        // downsample (≤ 4 images, ≤ 1568px longest side, ≤ 2 MB raw each) is
        // enforced at the composer attachment step before we get here.

        // Every send carries a concrete conversation_id. Pre-create fresh
        // conversations through REST instead of sending a blank placeholder.
        val currentConversationId = targetConversationId?.takeIf { it.isNotBlank() }
        val conversationId = when {
            currentConversationId != null -> currentConversationId
            else -> runCatching {
                conversationRepository.createConversation(AgentId(agentId)).id.value
            }.getOrElse { err ->
                if (err is CancellationException) throw err
                Telemetry.error("AdminChatVM", "ws.send.createConversationFailed", err)
                foreground.sendFailed(
                    targetConversationId?.let(::ConversationId),
                    "Failed to create a new conversation: ${err.message ?: "unknown"}",
                )
                timer.stop("accepted" to false, "reason" to "create_failed")
                return
            }
        }
        try {
            prepareConversation(conversationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            foreground.sendFailed(
                targetConversationId?.let(::ConversationId),
                "Conversation is not ready: ${failure.message ?: "unknown"}",
            )
            timer.stop("accepted" to false, "reason" to "conversation_not_ready")
            return
        }
        turnRegistry.stateFor(ConversationId(conversationId))
        reportCrossConversationSend(ConversationId(conversationId))
        turnRegistry.lastActiveConversationId = conversationId
        showConversation(ConversationId(conversationId), targetConversationId?.let(::ConversationId))

        Telemetry.event("IrohTrace", "coordinator.ensureConnected.begin", "conversationId" to conversationId)
        val connected = queueTurns.ensureConnected(config, clientVersion)
        Telemetry.event("IrohTrace", "coordinator.ensureConnected.done", "conversationId" to conversationId, "connected" to connected)
        if (!connected) {
            foreground.sendFailed(ConversationId(conversationId), "Chat channel is not connected")
            timer.stop("accepted" to false, "reason" to "not_connected")
            return
        }

        val pending = QueuedChatSend(
            id = QueuedSendId(otidGenerator()),
            conversationId = QueueConversationId(conversationId),
            text = text,
            attachments = attachments,
        )
        // letta-mobile-1n5py: never jump messages already waiting, and never fail a send because
        // a turn is running: queue it behind that turn instead.
        val queuePosition = queueDriver.submitLocked(pending)
        if (queuePosition != null) reportQueuedSend(pending, queuePosition)
        timer.stop(
            "accepted" to true,
            "conversationId" to conversationId,
            "otid" to pending.id.value,
            "attachments" to attachments.size,
            "queued" to (queuePosition != null),
        )
    }

    private fun showConversation(conversation: ConversationId, expectedSelection: ConversationId? = conversation) {
        if (!foreground.owns(expectedSelection)) {
            foreground.suppressed(conversation, "selectConversation")
            return
        }
        setActiveConversationId(conversation.value)
        // Selection publication can re-enter host code that switches again; recheck before observing.
        if (foreground.accepts(conversation, "startTimelineObserver")) startTimelineObserver(conversation.value)
    }

    private fun validatedActiveConfig(conversation: ConversationId?): LettaConfig? {
        val config = activeConfig()
        Telemetry.event(
            "IrohTrace", "coordinator.config",
            "hasConfig" to (config != null),
            "mode" to config?.mode?.name,
            "serverUrl" to backendUrlTelemetryDescriptor(config?.serverUrl),
            "hasToken" to !config?.accessToken.isNullOrBlank(),
        )
        if (config == null) {
            foreground.sendFailed(conversation, "No active backend is configured")
            return null
        }
        return config
    }

    fun cancel(): Boolean {
        val conversationId = activeConversationId() ?: turnRegistry.lastActiveConversationId ?: return false
        return queueDriver.stop(QueueConversationId(conversationId))
    }

    private suspend fun dispatchPendingSend(
        pending: QueuedChatSend,
        clearComposer: Boolean,
    ): Boolean {
        Telemetry.event(
            "IrohTrace", "dispatchPendingSend.bridgeSend.begin",
            "agentId" to agentId,
            "conversationId" to pending.conversationId.value,
            "otid" to pending.id.value,
            "clearComposer" to clearComposer,
        )
        val state = turnRegistry.stateFor(ConversationId(pending.conversationId.value))
        val accepted = state.acceptSend(
            pending = pending,
            send = {
                wsChatBridge.send(
                    agentId = agentId,
                    conversationId = pending.conversationId.value,
                    text = pending.text,
                    otid = pending.id.value,
                    attachments = pending.attachments,
                    startNewConversation = false,
                )
            },
            onAccepted = {
                pending.conversationId.value.takeIf { it.isNotBlank() }?.let { turnRegistry.lastActiveConversationId = it }
            },
        )
        Telemetry.event(
            "IrohTrace", "dispatchPendingSend.bridgeSend.done",
            "conversationId" to pending.conversationId.value,
            "otid" to pending.id.value,
            "accepted" to accepted,
        )
        if (!accepted) return false

        // A queued message gets its timeline row only now that it runs, so it lands after the
        // reply it waited behind rather than above it.
        timelineRepository.appendExternalTransportLocal(
            agentId = agentId,
            conversationId = pending.conversationId.value,
            content = pending.text,
            otid = pending.id.value,
            attachments = pending.attachments,
        )
        if (clearComposer) foreground.clearComposer(ConversationId(pending.conversationId.value))
        postSendReconciler.schedule(pending.conversationId.value, pending.id.value)
        foreground.dispatched(ConversationId(pending.conversationId.value))
        return true
    }

    private fun reportQueuedSend(pending: QueuedChatSend, position: Int) {
        foreground.queued(pending)
        Telemetry.event(
            "AdminChatVM", "ws.send.enqueued",
            "conversationId" to pending.conversationId.value,
            "otid" to pending.id.value,
            "position" to position,
        )
    }

    suspend fun handleEvent(event: WsTimelineEvent) = contained(event::class.simpleName) {
        turnStateMutex.withLock { handleEventLocked(event) }
    }

    private suspend fun handleEventLocked(event: WsTimelineEvent) {
        // letta-mobile-sfex6: strict agent scoping, kept as a defensive gate (see isForeignTo).
        if (event.isForeignTo(agentId)) return
        Telemetry.event(
            "IrohTrace", "coordinator.event",
            "type" to (event::class.simpleName ?: ""),
            "lastActiveConversationId" to turnRegistry.lastActiveConversationId,
            "trackedConversations" to turnRegistry.trackedConversationCount,
        )
        if (bridgeEventDeduplicator.isDuplicate(event, messageDeduplicationScope(event))) return
        when (event) {
            is WsTimelineEvent.TurnStarted -> handleTurnStarted(event)
            is WsTimelineEvent.MessageDelta -> handleMessageDelta(event)
            is WsTimelineEvent.StopReason -> handleStopReason(event)
            is WsTimelineEvent.UsageStatistics -> recordUsage(event)
            is WsTimelineEvent.TurnDone -> handleTurnDoneFrame(event)
            is WsTimelineEvent.SubscribeDone -> handleSubscribeDone(event)
            is WsTimelineEvent.Error -> handleErrorFrame(event)
            is WsTimelineEvent.Disconnected -> handleDisconnect(event)
            is WsTimelineEvent.GoalsUpdated, is WsTimelineEvent.AgentUpdated -> Unit
            is WsTimelineEvent.TurnQueued -> serverQueueMarks.markQueuedOnServer(event)
            // letta-mobile-bzvro.7: status-line events go to the run state only.
            is WsTimelineEvent.RunActivity -> recordRunStateOnly(event)
        }
    }

    private suspend fun handleStopReason(event: WsTimelineEvent.StopReason) {
        val state = liveTailState(event, TurnTail(event.turnId, event.runId)) ?: return
        if (ignoreForeignTurnStop(state, event)) return
        recordStopReasonForTurn(state, event)
        settlement.markTurnVisuallyComplete(state, reason = "stopReason")
    }

    private fun recordUsage(event: WsTimelineEvent.UsageStatistics) {
        val state = liveTailState(event, TurnTail(event.turnId, event.runId)) ?: return
        // lcp-cv3 §end-of-turn ordering: usage_statistics is first-wins
        // on the shim. Multi-step turns may produce per-step usage; the
        // run-level record reflects the first. Drop subsequent ones.
        if (state.usageRecorded) {
            Telemetry.event(
                "AdminChatVM", "ws.usage.duplicate",
                "turnId" to event.turnId,
            )
        } else {
            state.usageRecorded = true
            foreground.usage(ConversationId(state.ownerConversationId), event)
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

    private suspend fun handleSubscribeDone(event: WsTimelineEvent.SubscribeDone) {
        // SubscribeDone carries a run id only; resolve the owning
        // conversation from it before finalizing anything.
        val state = turnRegistry.resolveStateByRun(TurnTail(null, event.runId)) ?: return
        if (state.otid != null || ui.isStreaming()) {
            finishActiveTurn(state, TurnSettlementRequest(
                WsTimelineEvent.TurnDone(state.turnId.orEmpty(), event.runId, BridgeTurnStatus.fromWire(event.status)),
                reason = "subscribeDone", recordEvent = false,
            ))
        }
    }

    private suspend fun handleDisconnect(event: WsTimelineEvent.Disconnected) {
        if (event.willReconnect && !event.isAuthFailure) {
            Telemetry.event(
                "AdminChatVM", "ws.disconnected.transient",
                "code" to event.code,
                "attempt" to event.reconnectAttempt,
            )
            foreground.transientDisconnect(turnRegistry.statesSnapshot)
            return
        }
        failActiveTurnForDisconnect(event)
    }

    private fun messageDeduplicationScope(event: WsTimelineEvent): String? {
        val delta = event as? WsTimelineEvent.MessageDelta ?: return null
        return namedOrLastActive(delta.conversationId) ?: activeConversationId()
    }

    private fun recordRunStateOnly(event: WsTimelineEvent.RunActivity) =
        runtimeEventBatcher.enqueue(event, namedOrLastActive(event.conversationId))

    /** A frame's own (alias-resolved) conversation, else the conversation this chat last sent on. */
    private fun namedOrLastActive(named: String?): String? = named?.let(::ConversationId)
        ?.let { turnRegistry.resolveConversationId(it)?.value ?: it.value }
        ?: turnRegistry.lastActiveConversationId

    private suspend fun handleMessageDelta(event: WsTimelineEvent.MessageDelta) {
        serverQueueMarks.onOwnTurnFrame(event)
        val conversation = incoming.messageDelta(event) ?: return
        postSendReconciler.recordLiveIngest(conversation.value)
        foreground.messageDelta(conversation)
    }

    /**
     * Lifecycle ownership is the terminal fence, resolved to the OWNING
     * conversation first. It rejects delayed terminals from older generations
     * without touching the accepted send or the newer turn that currently owns UI
     * presence — and, since PR2, without touching a DIFFERENT conversation's live
     * turn. A terminal whose turn this conversation already retired takes the
     * stale path too (PR2 review, finding 2): a cleared entry's lifecycle would
     * otherwise accept any terminal, double-finishing the conversation.
     */
    private suspend fun handleTurnDoneFrame(event: WsTimelineEvent.TurnDone) {
        val owner = turnRegistry.resolveTerminal(event)
        val stale = owner == null ||
            owner.isRetiredTurn(TurnTail(event.turnId, event.runId)) ||
            !owner.identity.acceptsTerminal(event.turnId)
        if (stale) {
            if (owner == null) reportUnmatchedFrame(event, TurnTail(event.turnId, event.runId))
            settlement.staleTerminal(owner, event)
            // A terminal with no owned send can release a queue held for the other client.
            queueDriver.onForeignTerminalLocked()
            return
        }
        finishTurnDone(owner, event)
    }

    private suspend fun handleErrorFrame(event: WsTimelineEvent.Error) {
        if (!incoming.error(event)) reportUnmatchedFrame(event, TurnTail(event.turnId, event.runId))
    }

    private suspend fun handleRedialWhileTurnActiveLocked(event: RedialWhileTurnActive) {
        if (event.agentId != agentId) return
        // Redial recovery is conversation-scoped on the wire; only the named
        // conversation's own turn state may be finalized by it.
        val state = turnRegistry.peekState(ConversationId(event.conversationId)) ?: return
        if (state.otid == null && !ui.isStreaming() && !ui.isAgentTyping()) return
        when (
            val outcome = timelineRepository.reconcileRecentMessages(
                agentId = agentId,
                conversationId = event.conversationId,
                reason = "redial-recovery",
                forceRefresh = true,
                connectionGeneration = event.connectionGeneration,
            )
        ) {
            is RecentMessagesReconcileOutcome.Applied -> Unit
            is RecentMessagesReconcileOutcome.Skipped -> {
                Telemetry.event(
                    "AdminChatVM", "ws.redialRecovery.reconcileSkipped",
                    "conversationId" to event.conversationId,
                    "turnId" to event.turnId,
                    "runId" to event.runId,
                    "reason" to outcome.reason,
                )
                return
            }
            is RecentMessagesReconcileOutcome.Failed -> {
                Telemetry.error(
                    "AdminChatVM", "ws.redialRecovery.reconcileFailed", outcome.cause,
                    "conversationId" to event.conversationId,
                    "turnId" to event.turnId,
                    "runId" to event.runId,
                )
                return
            }
        }
        finishActiveTurn(state, TurnSettlementRequest(
            WsTimelineEvent.TurnDone(event.turnId, event.runId, BridgeTurnStatus.Completed),
            reason = "redial-recovery",
        ))
    }

    private suspend fun handleTurnStarted(event: WsTimelineEvent.TurnStarted) {
        val state = incoming.started(event, scope) ?: return
        val conversation = ConversationId(state.ownerConversationId)
        showConversation(conversation)
        foreground.started(conversation)
        incoming.drainBuffered(conversation)
    }

    /**
     *  (codex review P1): a stop_reason from a DIFFERENT
     * turn (fanout/replay) must not count as this turn's main-reply completion
     * evidence — a foreign completed stop would suppress the active turn's
     * failure notice.
     */
    private fun ignoreForeignTurnStop(
        state: ConversationTurnState,
        event: WsTimelineEvent.StopReason,
    ): Boolean {
        val activeTurn = state.turnId ?: return false
        if (event.turnId.isBlank() || event.turnId == activeTurn) return false
        Telemetry.event(
            "AdminChatVM", "ws.stopReason.foreignTurnIgnored",
            "conversationId" to state.conversationId,
            "received" to event.stopReason,
            "turnId" to event.turnId,
            "activeTurnId" to activeTurn,
        )
        return true
    }

    /**
     * lcp-cv3: stop_reason is first-wins for telemetry, but intermediate stops
     * (requires_approval) may still be followed by a completed main-reply stop —
     * upgrade so Failed-after-delivered suppression uses the right signal.
     */
    private fun recordStopReasonForTurn(
        state: ConversationTurnState,
        event: WsTimelineEvent.StopReason,
    ) {
        val previous = state.stopReason
        when {
            previous == null -> {
                state.stopReason = event.stopReason
                Telemetry.event(
                    "AdminChatVM", "ws.stopReason",
                    "value" to event.stopReason,
                    "turnId" to event.turnId,
                    "runId" to event.runId,
                )
            }
            TurnFailureNotices.isCompletedMainReplyStopReason(event.stopReason) &&
                !TurnFailureNotices.isCompletedMainReplyStopReason(previous) -> {
                state.stopReason = event.stopReason
                Telemetry.event(
                    "AdminChatVM", "ws.stopReason.upgraded",
                    "previous" to previous,
                    "received" to event.stopReason,
                    "turnId" to event.turnId,
                )
            }
            else -> {
                Telemetry.event(
                    "AdminChatVM", "ws.stopReason.duplicate",
                    "previous" to previous,
                    "received" to event.stopReason,
                    "turnId" to event.turnId,
                )
            }
        }
    }

    private suspend fun finishActiveTurn(state: ConversationTurnState, request: TurnSettlementRequest) {
        val conversationId = state.ownerConversationId.takeIf { it.isNotBlank() } ?: IdFormats.defaultConversationIdFor(agentId)
        val writes = settlement.finish(
            state, request,
            if (request.event.status is BridgeTurnStatus.Cancelled) serverQueueMarks.cancelledTerminalNotice(state.inFlightSend) else null,
        )
        releaseTerminalQueue(QueueConversationId(conversationId), writes)
    }

    /** Cancellation retires the turn but never starts another send; ordinary write failures do. */
    private suspend fun releaseTerminalQueue(conversationId: QueueConversationId, writes: List<Result<*>>) {
        val failures = writes.mapNotNull { it.exceptionOrNull() }
        failures.filterNot { it is CancellationException }.forEach { failure ->
            Telemetry.error("AdminChatVM", "ws.turnSettlement.failed", failure, "conversationId" to conversationId.value)
        }
        // Never consume cancellation, including one thrown by a writer inside NonCancellable.
        failures.firstOrNull { it is CancellationException }?.let { throw it }
        currentCoroutineContext().ensureActive()
        if (scope.isActive) queueDriver.onTurnFinishedLocked(conversationId)
    }

    /**
     * The owned terminal of [owner]'s turn.
     *
     * when another client (a second device) already runs this conversation,
     * the wrapper bounces this device's send before it starts. Instead of failing, the send waits
     * at the head of the queue and goes again once that turn ends. Its otid is not settled, so the
     * retry reuses it and the row the user already sees. A terminal that carried no send of this
     * device may be that other turn's end, so it releases held queues, after it is handled.
     */
    private suspend fun finishTurnDone(owner: ConversationTurnState, event: WsTimelineEvent.TurnDone) {
        val send = owner.inFlightSend
        val bounced = otherClientBounce(send, event.status, owner.bufferedErrorMessage, owner.deliveredAssistantContent)
        if (bounced != null) {
            owner.otid = null
            owner.reachedTerminal = true
            terminalOwnership.clear(owner, reason = "otherClientBusy")
            timelineRepository.clearExternalTransportActive(bounced.conversationId.value)
            foreground.visuallyComplete(ConversationId(bounced.conversationId.value))
            queueDriver.holdForOtherClientLocked(bounced)
            foreground.queued(bounced, clearComposer = false)
            return
        }
        owner.recordTerminalIdentity(event)
        finishActiveTurn(owner, TurnSettlementRequest(event, reason = "turnDone"))
        if (send == null) queueDriver.onForeignTerminalLocked()
    }

    /**
     *  PR2: SCOPED to the conversation whose presence is being
     * healed. The unscoped `hasAnyActiveChatTurn` read meant conversation B's live
     * turn suppressed conversation A's self-heal, so an evicted or orphaned A
     * presence was never recovered. Only a send with no known target (a brand-new
     * conversation) still falls back to the any-turn question.
     */
    private suspend fun healStaleVisualPresence(conversation: ConversationId?) {
        val transportOwnsTurn = if (conversation != null) {
            wsChatBridge.hasActiveChatTurn(conversation.value)
        } else {
            wsChatBridge.hasAnyActiveChatTurn
        }
        if (transportOwnsTurn) return
        if (!ui.isStreaming() && !ui.isAgentTyping()) return
        settlement.healStalePresence(turnRegistry.peekState(conversation), conversation)
    }

    private suspend fun failActiveTurnForDisconnect(event: WsTimelineEvent.Disconnected) {
        // A terminal disconnect kills EVERY conversation's turn, so this is the
        // one path that legitimately walks all entries — each is failed against
        // its own conversation id instead of one guessed global.
        val states = turnRegistry.statesSnapshot
        incoming.clearBuffered()
        queueDriver.onDisconnected()
        states.forEach { state -> settlement.failConversationForDisconnect(state, event) }
        if (states.isEmpty()) {
            // Nothing was tracked, but the visible conversation still needs its
            // transport-active flag and dangling-tool sweep reset.
            activeConversationId()?.let { conversationId -> settlement.endConversationAbnormally(ConversationId(conversationId)) }
        }
        ui.onDisconnectFailure(event.reason.ifBlank { "WebSocket disconnected" })
    }

    internal companion object {
        internal var postSendReconcileDelaysMs = longArrayOf(750L, 2_500L, 6_000L)
    }
}
