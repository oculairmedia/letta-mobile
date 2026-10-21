package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.runtime.TurnFailureNotice
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.runtime.terminalReasonKind
import com.letta.mobile.data.timeline.IROH_SYNTHETIC_RUN_ID_PREFIXES
import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.api.TimelineIngestSources
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.transport.api.RedialWhileTurnActive
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.backendUrlTelemetryDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

import kotlin.time.Duration.Companion.milliseconds
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
    private val clearComposerAfterSend: () -> Unit,
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
        ConversationId(activeConversationId()?.takeIf { it.isNotBlank() } ?: defaultShimConversationId(agentId))
    }
    private val preConversationMessageDeltas = ArrayDeque<WsTimelineEvent.MessageDelta>()
    // letta-mobile-1n5py: messages sent while their conversation is busy wait here, unbounded,
    // and run in order once the turn ahead ends. See [QueuedSendDriver].
    private val queuedSends = ChatSendQueue()
    private val queueDriver = QueuedSendDriver(
        scope,
        queuedSends,
        CoordinatorQueueTurns(turnStateMutex, wsChatBridge) { dispatchPendingSend(it, clearComposer = false) },
    )

    private val serverQueueMarks = ServerQueueMarks(
        queue = queuedSends,
        sendOfTurn = { turnId -> turnRegistry.statesSnapshot.firstOrNull { it.turnId == turnId }?.inFlightSend },
        sendOfConversation = { conversationId -> peekState(conversationId)?.inFlightSend },
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
                contained("RedialWhileTurnActive") { handleRedialWhileTurnActive(event) }
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

    private fun resolveConversationId(conversationId: String?): String? =
        turnRegistry.resolveConversationId(conversationId?.let(::ConversationId))?.value

    private fun retainSettledOtid(otid: String?) = turnRegistry.retainSettledOtid(otid?.let(::QueuedSendId))

    private fun stateFor(conversationId: String): ConversationTurnState =
        turnRegistry.stateFor(ConversationId(conversationId))

    private fun peekState(conversationId: String?): ConversationTurnState? =
        turnRegistry.peekState(conversationId?.let(::ConversationId))

    private fun bindInboundTurnByOtid(
        otid: String,
        serverConversationId: String?,
        turnId: String?,
        runId: String?,
    ): ConversationTurnState? {
        val binding = turnRegistry.bindInboundTurnByOtid(
            QueuedSendId(otid), serverConversationId?.let(::ConversationId), TurnTail(turnId, runId),
        )
        binding.rekeyedConversationId?.value?.let { conversationId ->
            if (ownsForegroundUi(conversationId)) {
                ui.onTurnStarted(conversationId)
            } else {
                reportBackgroundUiSuppressed(conversationId, "onTurnStarted")
            }
        }
        return binding.state
    }

    /**
     * True when [turnId] names a turn this conversation has already settled. Such
     * a frame is history: only the TurnDone stale path (old-run fragment cleanup)
     * still wants it; stop/usage/error frames must be dropped rather than applied
     * to a cleared entry.
     */
    private fun isRetiredTurn(state: ConversationTurnState, turnId: String?): Boolean {
        val key = turnId?.takeIf { it.isNotBlank() } ?: return false
        return state.turnId != key && state.identity.isFenced(key)
    }

    /** Live state a stop/usage tail may mutate; a retired turn's tail is only forwarded ([forwardRetiredTurnTail]). */
    private fun liveTailStateOrForward(event: WsTimelineEvent, tail: TurnTail): ConversationTurnState? {
        val state = if (!tail.turnId.isNullOrBlank()) {
            turnRegistry.resolveStateByTurn(tail)
        } else {
            turnRegistry.resolveStateByRun(tail)
                ?: turnRegistry.statesSnapshot.firstOrNull { tail.runId in it.settledRunIds }
        }
        when {
            state == null -> reportUnmatchedFrame(event, tail.turnId, tail.runId)
            !isRetiredTurn(state, tail.turnId) &&
                !state.isRetiredRun(tail) -> return state
            else -> forwardRetiredTurnTail(runtimeEventBatcher, event, tail, state.localConversationId ?: state.conversationId)
        }
        return null
    }

    /**
     * [ChatSendUiSink] is a SINGLETON bound to whatever conversation is on
     * screen — it has no conversation key (the Android sink mutates one
     * `ChatUiState.isStreaming` / `isAgentTyping` / error field). Keying turn
     * state alone was therefore not enough (PR2 review, finding 1): conversation
     * A's terminal reached its own keyed state correctly and then cleared the
     * presence of, and surfaced A's error on, whatever conversation B the user was
     * actually looking at.
     *
     * Presence-mutating callbacks are gated on this. A null visible conversation
     * means "cannot tell" — the legacy behavior (apply) is preserved there.
     */
    private fun ownsForegroundUi(conversationId: String?): Boolean {
        val visible = activeConversationId() ?: return true
        return visible == conversationId
    }

    private fun reportBackgroundUiSuppressed(conversationId: String?, callback: String) {
        Telemetry.event(
            "AdminChatVM", "ws.ui.backgroundConversationSuppressed",
            "conversationId" to conversationId,
            "visibleConversationId" to activeConversationId(),
            "callback" to callback,
        )
    }

    /**
     * SENSING: a send starting for one conversation while
     * ANOTHER conversation's identity is still non-terminal is precisely the
     * overlap that used to evict state. Legal now — but recorded.
     */
    private fun reportCrossConversationSend(conversationId: String) {
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
    private fun reportUnmatchedFrame(event: WsTimelineEvent, turnId: String?, runId: String?) {
        Telemetry.event(
            "AdminChatVM", "ws.event.unmatchedConversation",
            "eventType" to (event::class.simpleName ?: ""),
            "turnId" to (turnId ?: ""),
            "runId" to (runId ?: ""),
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
            sendInternal(text, attachments, targetConversationId)
        }
    }

    private suspend fun sendInternal(
        text: String,
        attachments: List<MessageContentPart.Image>,
        targetConversationId: String?,
    ) = turnStateMutex.withLock {
        sendInternalLocked(text, attachments, targetConversationId)
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
        healStaleVisualPresence(targetConversationId)
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
        val config = validatedActiveConfig() ?: return
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
                ui.onSendFailed("Failed to create a new conversation: ${err.message ?: "unknown"}")
                timer.stop("accepted" to false, "reason" to "create_failed")
                return
            }
        }
        try {
            prepareConversation(conversationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ui.onSendFailed("Conversation is not ready: ${failure.message ?: "unknown"}")
            timer.stop("accepted" to false, "reason" to "conversation_not_ready")
            return
        }
        stateFor(conversationId)
        reportCrossConversationSend(conversationId)
        turnRegistry.lastActiveConversationId = conversationId
        setActiveConversationId(conversationId)
        startTimelineObserver(conversationId)

        Telemetry.event("IrohTrace", "coordinator.ensureConnected.begin", "conversationId" to conversationId)
        val connected = ensureConnected(config)
        Telemetry.event("IrohTrace", "coordinator.ensureConnected.done", "conversationId" to conversationId, "connected" to connected)
        if (!connected) {
            ui.onSendFailed("Chat channel is not connected")
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
        val accepted = !queuedSends.hasItems(pending.conversationId) && dispatchPendingSend(pending, clearComposer = true)
        if (!accepted) enqueueBusySend(pending)
        timer.stop(
            "accepted" to true,
            "conversationId" to conversationId,
            "otid" to pending.id.value,
            "attachments" to attachments.size,
            "queued" to !accepted,
        )
    }

    private fun validatedActiveConfig(): LettaConfig? {
        val config = activeConfig()
        Telemetry.event(
            "IrohTrace", "coordinator.config",
            "hasConfig" to (config != null),
            "mode" to config?.mode?.name,
            "serverUrl" to backendUrlTelemetryDescriptor(config?.serverUrl),
            "hasToken" to !config?.accessToken.isNullOrBlank(),
        )
        if (config == null) {
            ui.onSendFailed("No active backend is configured")
            return null
        }
        return config
    }

    fun cancel(): Boolean {
        val conversationId = activeConversationId() ?: turnRegistry.lastActiveConversationId ?: return false
        val accepted = wsChatBridge.cancel(conversationId)
        // letta-mobile-1n5py: a Stop holds what is queued (the App Server parks its own queue on
        // abort too) instead of running it or throwing it away.
        if (accepted) queueDriver.onStopped(QueueConversationId(conversationId))
        return accepted
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
        val state = stateFor(pending.conversationId.value)
        val accepted = state.identity.acceptSend(
            conversationId = pending.conversationId.value,
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
                state.otid = pending.id.value
                state.inFlightSend = pending
                state.localConversationId = pending.conversationId.value.takeIf { it.isNotBlank() }
                state.reachedTerminal = false
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
        if (clearComposer) clearComposerAfterSend()
        postSendReconciler.schedule(pending.conversationId.value, pending.id.value)
        ui.onSendDispatched(pending.conversationId.value.takeIf { it.isNotBlank() })
        return true
    }

    private suspend fun enqueueBusySend(pending: QueuedChatSend) {
        val position = queueDriver.enqueueLocked(pending)
        clearComposerAfterSend()
        ui.onSendQueued(pending.conversationId.value)
        Telemetry.event(
            "AdminChatVM", "ws.send.enqueued",
            "conversationId" to pending.conversationId.value,
            "otid" to pending.id.value,
            "position" to position,
        )
    }

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
        return withTimeoutOrNull(CONNECT_WAIT_MS.milliseconds) {
            wsChatBridge.awaitConnected()
            true
        } ?: false
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
        val fallbackConversationId = if (event is WsTimelineEvent.MessageDelta) {
            resolveConversationId(event.conversationId) ?: event.conversationId
                ?: turnRegistry.lastActiveConversationId
                ?: activeConversationId()
        } else {
            null
        }
        if (bridgeEventDeduplicator.isDuplicate(event, fallbackConversationId)) return
        when (event) {
            is WsTimelineEvent.TurnStarted -> handleTurnStarted(event)
            is WsTimelineEvent.MessageDelta -> handleMessageDelta(event)
            is WsTimelineEvent.StopReason -> {
                val state = liveTailStateOrForward(event, TurnTail(event.turnId, event.runId)) ?: return
                val effectiveConversationId = state.localConversationId ?: state.conversationId
                runtimeEventBatcher.enqueue(event, effectiveConversationId)
                if (ignoreForeignTurnStop(state, event)) return
                recordStopReasonForTurn(state, event)
                markTurnVisuallyComplete(state, reason = "stopReason")
            }
            is WsTimelineEvent.UsageStatistics -> {
                val state = liveTailStateOrForward(event, TurnTail(event.turnId, event.runId)) ?: return
                val effectiveConversationId = state.localConversationId ?: state.conversationId
                runtimeEventBatcher.enqueue(event, effectiveConversationId)
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
            is WsTimelineEvent.TurnDone -> handleTurnDoneFrame(event)
            is WsTimelineEvent.SubscribeDone -> {
                // SubscribeDone carries a run id only; resolve the owning
                // conversation from it before finalizing anything.
                val state = turnRegistry.resolveStateByRun(TurnTail(null, event.runId))
                if (state != null && (state.otid != null || ui.isStreaming())) {
                    finishActiveTurn(
                        state = state,
                        status = BridgeTurnStatus.fromWire(event.status),
                        runId = event.runId,
                        turnId = state.turnId.orEmpty(),
                        lossy = false,
                        dropCount = 0L,
                        reason = "subscribeDone",
                        recordEvent = null,
                    )
                }
            }
            is WsTimelineEvent.Error -> handleErrorFrame(event)
            is WsTimelineEvent.Disconnected -> {
                if (event.willReconnect && !event.isAuthFailure) {
                    Telemetry.event(
                        "AdminChatVM", "ws.disconnected.transient",
                        "code" to event.code,
                        "attempt" to event.reconnectAttempt,
                    )
                    ui.onTransientDisconnect(hasActiveSend = turnRegistry.statesSnapshot.any { it.otid != null })
                    return
                }
                failActiveTurnForDisconnect(event)
            }
            is WsTimelineEvent.GoalsUpdated, is WsTimelineEvent.AgentUpdated -> Unit
            is WsTimelineEvent.TurnQueued -> serverQueueMarks.markQueuedOnServer(event)
            // letta-mobile-bzvro.7: status-line events go to the run state only.
            is WsTimelineEvent.RunActivity -> recordRunStateOnly(event)
        }
    }

    private fun recordRunStateOnly(event: WsTimelineEvent.RunActivity) {
        val named = event.conversationId
        runtimeEventBatcher.enqueue(event, resolveConversationId(named) ?: named ?: turnRegistry.lastActiveConversationId)
    }

    /** A delta naming a turn this conversation has already finished is that turn's tail. */
    private fun isRetiredTurnTail(event: WsTimelineEvent.MessageDelta, conversationId: String): Boolean {
        val state = peekState(conversationId) ?: return false
        val turnId = event.turnId?.takeIf { it.isNotBlank() }
        if (turnId != null) return isRetiredTurn(state, turnId)
        // A frame without a turn id still names its run; a settled run that is not the live one
        // is a finished turn's tail too.
        val runId = event.message.runId?.takeIf { it.isNotBlank() } ?: return false
        return runId != state.runId && runId in state.settledRunIds
    }

    /**
     * Prefer the state the otid bound, then the state that owns this turn, then whatever the
     * frame's conversation resolves to. The frame's own id is the last thing to trust: it is the
     * server's name for the conversation, which is the mismatch this binding exists to absorb.
     */
    private fun deltaConversationId(
        event: WsTimelineEvent.MessageDelta,
        boundState: ConversationTurnState?,
    ): String? {
        val rawConv = event.conversationId
        val frameConv = rawConv?.let { resolveConversationId(it) } ?: rawConv
        if (frameConv != null) {
            // The frame names its (agent, conversation). That is where it belongs, whatever this
            // screen shows: resolve only to a state that genuinely owns this conversation's turn,
            // never to one fabricated for the open conversation. Before, an unknown turn fell
            // through to turnRegistry.fallbackState(), which minted a state for the OPEN conversation and
            // ingested another conversation's run into it - across agents, and across two
            // conversations of the same agent.
            val state = boundState
                ?: ownedStateForTurn(event.turnId, frameConv)
                ?: peekState(frameConv)
            return state?.let { it.localConversationId ?: it.conversationId } ?: frameConv
        }
        // No conversation on the frame: legacy WS frames only (Iroh always stamps agent, conversation
        // and turn). The WS contract attributes these to this chat's own send or open conversation,
        // pinned by WsChatSendCoordinatorTest (replay, live stream, pre-conversation buffering).
        val state = boundState ?: event.turnId?.let { id ->
            turnRegistry.resolveStateByTurn(TurnTail(id, null))?.takeUnless { isRetiredTurn(it, id) }
        }
        return state?.let { it.localConversationId ?: it.conversationId }
            ?: turnRegistry.lastActiveConversationId
            ?: activeConversationId()
    }

    /**
     * The state that owns [turnId] for a frame that names [frameConversationId]: an exact live turn,
     * a turn this conversation already fenced, or this conversation's own send still awaiting its
     * `TurnStarted`. Unlike [ConversationTurnRegistry.resolveStateByTurn] it never falls back to a state for whatever
     * conversation is active, and a state for another conversation never claims the frame.
     */
    private fun ownedStateForTurn(turnId: String?, frameConversationId: String): ConversationTurnState? {
        val key = turnId?.takeIf { it.isNotBlank() } ?: return null
        val states = turnRegistry.statesSnapshot
        fun ownsConversation(state: ConversationTurnState) =
            state.conversationId == frameConversationId || state.localConversationId == frameConversationId
        states.firstOrNull { it.turnId == key }?.let { return it.takeUnless { state -> isRetiredTurn(state, key) } }
        states.firstOrNull { it.identity.isFenced(key) }?.let { return it }
        return states.singleOrNull { it.turnId == null && it.identity.active != null && ownsConversation(it) }
    }

    private fun resolveDeltaState(
        event: WsTimelineEvent.MessageDelta,
        conversationId: ConversationId,
        boundState: ConversationTurnState?,
        retiredTail: Boolean,
    ): ConversationTurnState? {
        if (!shouldAdoptPassiveDelta(event, boundState, retiredTail)) {
            return peekState(conversationId.value)
        }
        return stateFor(conversationId.value).also { state ->
            if (state.adoptPassiveDelta(event)) {
                turnRegistry.lastActiveConversationId = state.localConversationId ?: state.conversationId
            }
        }
    }

    private suspend fun handleMessageDelta(event: WsTimelineEvent.MessageDelta) {
        serverQueueMarks.onOwnTurnFrame(event)
        val otid = event.message.otid
        val boundState = if (otid != null && !event.isReplay) {
            bindInboundTurnByOtid(
                otid = otid,
                serverConversationId = event.conversationId,
                turnId = event.turnId,
                runId = event.message.runId,
            )
        } else {
            null
        }
        val conversationId = deltaConversationId(event, boundState)
        Telemetry.event(
            "IrohGate", "gate3.coordinatorMessageDelta",
            "resolvedConversationId" to conversationId,
            "messageId" to event.message.id,
            "messageType" to event.message.messageType,
            "isReplay" to event.isReplay,
        )
        if (conversationId == null) {
            preConversationMessageDeltas.addLast(event)
            return
        }
        runtimeEventBatcher.enqueue(event, conversationId)
        val retiredTail = isRetiredTurnTail(event, conversationId)
        val deltaState = resolveDeltaState(event, ConversationId(conversationId), boundState, retiredTail)
        rememberActiveAssistantMessageRunId(
            state = deltaState,
            message = event.message,
            frameConversationId = event.conversationId,
            isReplay = event.isReplay,
        )
        timelineRepository.ingestExternalTransportMessage(
            agentId, conversationId, event.message,
            source = TimelineIngestSources.coordinator(retiredTail),
        )
        if (retiredTail) {
            // A reply's last deltas can reach us after its terminal (Iroh emits them behind
            // turn_finished). The timeline folds them into the finished turn, but the turn is over:
            // latching typing here re-lit Thinking/Stop run with no terminal left to clear them.
            Telemetry.event(
                "AdminChatVM", "ws.event.retiredTurnTailDelta",
                "turnId" to event.turnId,
                "messageType" to event.message.messageType,
                "conversationId" to conversationId,
            )
            return
        }
        if (!event.isReplay) {
            postSendReconciler.recordLiveIngest(conversationId)
            // Finding 1: a background conversation's delta must not latch
            // the visible screen's typing indicator — that IS the reported
            // "B shows A's thinking indicator" symptom.
            if (ownsForegroundUi(conversationId)) {
                ui.onMessageDelta(conversationId)
            } else {
                reportBackgroundUiSuppressed(conversationId, "onMessageDelta")
            }
        }
    }

    private fun resolveTurnDoneOwner(event: WsTimelineEvent.TurnDone): ConversationTurnState? {
        if (event.turnId.isBlank()) return turnRegistry.resolveStateByRun(TurnTail(null, event.runId))
        val states = turnRegistry.statesSnapshot
        val runMatch = states.firstOrNull { it.canAdoptTerminal(event) }
        val turnAlreadyKnown = states.any { s -> s.turnId == event.turnId || s.identity.isFenced(event.turnId) }
        if (runMatch != null && !turnAlreadyKnown) {
            return runMatch
        }
        return turnRegistry.resolveStateByTurn(TurnTail(event.turnId, event.runId))
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
        val owner = resolveTurnDoneOwner(event)
        val stale = owner == null ||
            isRetiredTurn(owner, event.turnId) ||
            !owner.identity.acceptsTerminal(event.turnId)
        if (stale) {
            if (owner == null) reportUnmatchedFrame(event, event.turnId, event.runId)
            if (event.status is BridgeTurnStatus.Failed || event.status is BridgeTurnStatus.Cancelled) {
                val conversationId = (owner ?: turnRegistry.fallbackState())?.conversationId
                    ?: turnRegistry.lastActiveConversationId
                    ?: defaultShimConversationId(agentId)
                cleanupAbandonedAssistantFragmentsSafely(
                    conversationId = conversationId,
                    runId = event.runId,
                    turnId = event.turnId,
                    reason = "turn_done_stale_${event.status}",
                    // Scope cleanup to the OLD run only — do NOT fold in the
                    // active (newer) run's candidate ids.
                    candidateRunIds = event.runId.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet(),
                )
            }
            Telemetry.event(
                "AdminChatVM", "ws.turnDone.staleIgnored",
                "incomingTurnId" to event.turnId,
                "incomingRunId" to event.runId,
                "ownerConversationId" to (owner?.conversationId ?: ""),
                "activeTurnId" to (owner?.turnId ?: ""),
                "activeRunId" to (owner?.runId ?: ""),
                "status" to event.status,
            )
            // letta-mobile-1n5py.1: a terminal of no send of this device may end the other
            // client's turn a held queue waits for.
            queueDriver.onForeignTerminalLocked()
            return
        }
        finishTurnDone(owner, event)
    }

    private suspend fun handleErrorFrame(event: WsTimelineEvent.Error) {
        if (event.code == CURSOR_EXPIRED_ERROR_CODE && repairExpiredCursor(event)) return
        // An Error frame belongs to the conversation it names. Buffering it into
        // THAT conversation's state (instead of a single global) is what stops a
        // foreign failure from poisoning another conversation's turn.
        val state = resolveStateForError(event)
        if (state == null) {
            reportUnmatchedFrame(event, event.turnId, event.runId)
            return
        }
        // Still ignore a frame for a different TURN inside the same conversation
        // so a superseded turn cannot poison the live one.
        if (event.turnId != null && state.turnId != null && event.turnId != state.turnId) return
        runtimeEventBatcher.enqueue(event, state.conversationId)
        // lcp-axv: stash the error and wait for the immediately-following TurnDone
        // to flip the UI. Surfacing the error here would race with TurnDone and
        // could leave isStreaming / isAgentTyping stuck if TurnDone is delayed.
        state.bufferedErrorMessage = event.message.ifBlank { event.code }
        state.bufferedErrorKind = event.code.takeIf { TurnFailureNotices.isKnownKind(it) }
        Telemetry.event(
            "AdminChatVM", "ws.error.buffered",
            "conversationId" to state.conversationId,
            "code" to event.code,
            "message" to (event.message),
            "turnId" to (event.turnId ?: ""),
            "runId" to (event.runId ?: ""),
        )
    }

    /** Returns true when the frame was consumed as a cursor repair. */
    private suspend fun repairExpiredCursor(event: WsTimelineEvent.Error): Boolean {
        val conversationId = event.conversationId ?: turnRegistry.lastActiveConversationId ?: activeConversationId()
            ?: return false
        runCatching {
            timelineRepository.repairExpiredConversationCursorScoped(
                agentId = agentId,
                conversationId = conversationId,
                fallbackSeq = event.lastSeq,
                expectedWatermark = event.afterSeq,
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
            if (t is kotlinx.coroutines.CancellationException) throw t
            Telemetry.error(
                "AdminChatVM", "ws.cursorExpired.repairFailed", t,
                "conversationId" to conversationId,
            )
            ui.onError("Timeline repair failed: ${t.message ?: "unknown"}")
        }
        return true
    }

    private suspend fun handleRedialWhileTurnActive(event: RedialWhileTurnActive) = turnStateMutex.withLock {
        handleRedialWhileTurnActiveLocked(event)
    }

    private suspend fun handleRedialWhileTurnActiveLocked(event: RedialWhileTurnActive) {
        if (event.agentId != agentId) return
        // Redial recovery is conversation-scoped on the wire; only the named
        // conversation's own turn state may be finalized by it.
        val state = peekState(event.conversationId) ?: return
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
        finishActiveTurn(
            state = state,
            status = BridgeTurnStatus.Completed,
            runId = event.runId,
            turnId = event.turnId,
            lossy = false,
            dropCount = 0L,
            reason = "redial-recovery",
            recordEvent = WsTimelineEvent.TurnDone(
                turnId = event.turnId,
                runId = event.runId,
                status = BridgeTurnStatus.Completed,
            ),
        )
    }

    private suspend fun handleTurnStarted(event: WsTimelineEvent.TurnStarted) {
        val state = turnRegistry.resolveStateForTurnStarted(event)
        val effectiveConversationId = state.localConversationId ?: state.conversationId
        val identityTransition = state.identity.turnStarted(
            conversationId = effectiveConversationId,
            turnId = event.turnId,
            runId = event.runId,
        )
        // Iroh run-id promotion re-emits TurnStarted for the SAME turn
        // once the real server run id replaces the synthetic
        // `iroh-run-*` placeholder. That is a run-id update, not a new
        // turn: resetting per-turn state here (stop/usage/error guards,
        // assistant run-id set) mid-turn corrupted post-tool
        // settlement and contributed to the flicker. Update the run id
        // and keep the turn state intact.
        // Conversation equality is now implicit: `state` IS this conversation's entry.
        val exactActiveTurn = event.turnId == state.turnId
        if (identityTransition is TurnIdentityTransition.SameTurn && exactActiveTurn) {
            Telemetry.event(
                "AdminChatVM", "ws.turnStarted.runPromoted",
                "conversationId" to effectiveConversationId,
                "turnId" to event.turnId,
                "previousRunId" to (state.runId ?: ""),
                "runId" to event.runId,
            )
            state.runId = event.runId
            return
        }
        // dir4k (z5vfy PR-3): optimistic-local / OTID settlement. A new
        // turn is starting while a PREVIOUS turn's optimistic-local user
        // bubble may still be unsettled (this conversation's otid set, no terminal
        // reconciled it). If we adopt the new turn without settling the
        // old otid, the orphaned pending-local row keeps the coordinator
        // believing a send is in flight and re-latches Thinking. Since a
        // fresh server turn is proceeding, the prior local send did land:
        // settle it as sent before adopting the new turn identity.
        // PR2: only THIS conversation's unsettled otid is settled here. The old
        // global read settled (and then dropped) another conversation's live send.
        state.otid?.let { staleOtid ->
            val staleLocalConv = state.localConversationId ?: effectiveConversationId
            scope.launch {
                runCatching {
                    timelineRepository.markExternalTransportLocalSent(agentId, staleLocalConv, staleOtid)
                }
            }
            Telemetry.event(
                "AdminChatVM", "ws.turnStarted.staleOtidSettled",
                "staleOtid" to staleOtid,
                "newTurnId" to event.turnId,
                "conversationId" to effectiveConversationId,
            )
        }
        turnRegistry.lastActiveConversationId = effectiveConversationId
        state.turnId = event.turnId
        state.runId = event.runId
        state.reachedTerminal = false
        state.resetDeliveryEvidence()
        // letta-mobile-dangling-tool: a fresh turn on this conversation
        // supersedes whatever the previous turn's post-turn dangling-
        // tool-call sweep left pending.
        runCatching {
            timelineRepository.turnStarted(agentId, effectiveConversationId, event.runId, event.turnId)
        }
        runtimeEventBatcher.enqueue(event, effectiveConversationId)
        setActiveConversationId(effectiveConversationId)
        startTimelineObserver(effectiveConversationId)
        if (ownsForegroundUi(effectiveConversationId)) {
            // Replay metadata is carried through the production WS frame mapper
            // so a remotely-started active turn gets the same visible presence
            // as a local send. A replayed start is still active until its
            // terminal frame arrives; suppressing it makes remote work invisible.
            ui.onTurnStarted(effectiveConversationId)
        } else {
            reportBackgroundUiSuppressed(effectiveConversationId, "onTurnStarted")
        }
        drainPreConversationMessages(effectiveConversationId)
    }

    /**
     * Renders a dead turn as a visible ERROR row in the
     * chat timeline (the same TimelineMessageType.ERROR surface
     * added for server-emitted error frames) instead of leaving the turn
     * silently empty. The row carries the fixed per-family copy only; the raw
     * provider reason stays on the existing error banner path, which is the
     * only place this codebase already surfaces raw backend error text.
     *
     * The id is deterministic per (run, turn) so a replayed/duplicated terminal
     * cannot stack multiple identical error rows.
     */
    private suspend fun appendTurnFailureNotice(
        conversationId: String,
        runId: String,
        turnId: String,
        notice: TurnFailureNotice,
    ) {
        val stableRunId = runId.takeIf { it.isNotBlank() }
        val stableTurnId = turnId.takeIf { it.isNotBlank() }
        // letta-mobile-br5g0 (codex review): run ids reset across App Server
        // restarts (local-run-1 gets reused), so the run id alone is not
        // globally unique. Key the row by (turn, run) so a later failure that
        // reuses the run number still renders instead of merging into the old
        // row; a replayed terminal for the SAME turn still dedupes.
        val id = "turn-failed-" + when {
            stableTurnId != null && stableRunId != null -> "$stableTurnId-$stableRunId"
            stableTurnId != null -> stableTurnId
            stableRunId != null -> stableRunId
            else -> "unknown"
        }
        runCatching {
            timelineRepository.ingestExternalTransportMessage(
                agentId = agentId,
                conversationId = conversationId,
                message = ErrorMessage(
                    id = id,
                    contentRaw = JsonPrimitive(notice.message),
                    code = notice.kind,
                    runId = stableRunId,
                ),
                source = "coordinator.turnFailure",
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Telemetry.error(
                "AdminChatVM", "ws.turnFailureNotice.failed", error,
                "conversationId" to conversationId,
                "reasonKind" to notice.kind,
            )
        }
        Telemetry.event(
            "AdminChatVM", "ws.turnFailureNotice.rendered",
            "conversationId" to conversationId,
            "turnId" to turnId,
            "runId" to runId,
            "reasonKind" to notice.kind,
        )
    }

    /**
     * "did this turn deliver assistant content" is read
     * from observed turn state (an assistant delta with non-blank text was
     * ingested), never inferred from timing.
     *
     * codex review (P1): the evidence must BELONG to the active turn.
     * Server-side Iroh fanout and replay push assistant deltas from other
     * conversations/turns through this same flow; counting those marked a
     * failing turn as delivered and suppressed its failure notice. Scope:
     * never from replay, only while a turn is active, only when the frame's
     * conversation (when tagged) matches, and only when the message's
     * run id matches the active run. Synthetic iroh-run-* placeholders are
     * treated as unknown (run-id promotion may not have landed yet); untagged
     * frames fall back to active-turn scoping. A run id is NOT required — a
     * delivered reply without one still counts as delivered.
     */
    private fun countsAsActiveTurnDelivery(
        state: ConversationTurnState,
        message: AssistantMessage,
        frameConversationId: String?,
        isReplay: Boolean,
    ): Boolean {
        if (isReplay) return false
        if (state.turnId == null) return false
        if (message.content.isBlank()) return false
        if (!frameConversationMatchesActive(state, frameConversationId)) return false
        return state.runMatchesActiveTurn(message)
    }

    /** Untagged frames fall back to active-turn scoping. */
    private fun frameConversationMatchesActive(
        state: ConversationTurnState,
        frameConversationId: String?,
    ): Boolean {
        val tagged = frameConversationId ?: return true
        val targetConv = state.localConversationId ?: state.conversationId
        return tagged == targetConv ||
            tagged == state.serverConversationId ||
            resolveConversationId(tagged) == targetConv
    }

    /**
     * Synthetic iroh-run-* placeholders are treated as unknown (run-id
     * promotion may not have landed yet); a message without a run id falls
     * back to active-turn scoping.
     */
    private fun rememberActiveAssistantMessageRunId(
        state: ConversationTurnState?,
        message: LettaMessage,
        frameConversationId: String?,
        isReplay: Boolean,
    ) {
        if (state == null) return
        if (message !is AssistantMessage) return
        if (countsAsActiveTurnDelivery(state, message, frameConversationId, isReplay)) {
            state.deliveredAssistantContent = true
        }
        val messageRunId = message.runId?.takeIf { it.isNotBlank() }
        if (messageRunId == null) return
        // A replayed frame describes a run that already finished. This set
        // feeds activeCleanupCandidateRunIds, so latching a historical run id
        // onto the CURRENT turn hands abandoned-fragment cleanup a licence to
        // sweep rows belonging to older completed runs the next time this turn
        // reaches turn_done or the connection drops. Replay may still mark
        // delivery above (that check carries its own replay guard); it must
        // never widen the set of runs this turn is allowed to clean up.
        if (isReplay) return
        state.activeAssistantMessageRunIds += messageRunId
        while (state.activeAssistantMessageRunIds.size > MAX_ACTIVE_ASSISTANT_RUN_IDS) {
            state.activeAssistantMessageRunIds.remove(state.activeAssistantMessageRunIds.first())
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
            val removed = timelineRepository.cleanupAbandonedAssistantFragments(
                agentId, conversationId, runId, turnId, reason, candidateRunIds,
            )
            Telemetry.event(
                "AdminChatVM", "cleanupAbandonedAssistantFragments.completed",
                "conversationId" to conversationId,
                "runId" to (runId ?: ""),
                "turnId" to (turnId ?: ""),
                "candidateRunIds" to candidateRunIds.joinToString(","),
                "removedCount" to removed,
                "reason" to reason,
            )
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

    /**
     * lcp-cv3: stop_reason is first-wins for telemetry, but intermediate stops
     * (requires_approval) may still be followed by a completed main-reply stop —
     * upgrade so Failed-after-delivered suppression uses the right signal.
     */
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

    /**
     * The turn answered for this send, so its otid is settled: fence it against a replayed echo
     * rebinding a finished turn, and record on the timeline how it ended.
     */
    private suspend fun settlePendingSend(
        state: ConversationTurnState,
        conversationId: String,
        deadTurn: Boolean,
    ) {
        val otid = state.otid ?: return
        retainSettledOtid(otid)
        val localConversationId = state.localConversationId ?: conversationId
        if (deadTurn) {
            timelineRepository.markExternalTransportLocalFailed(agentId, localConversationId, otid)
        } else {
            timelineRepository.markExternalTransportLocalSent(agentId, localConversationId, otid)
        }
    }

    private suspend fun finishActiveTurn(
        state: ConversationTurnState,
        status: BridgeTurnStatus,
        runId: String,
        turnId: String,
        lossy: Boolean,
        dropCount: Long,
        reason: String,
        recordEvent: WsTimelineEvent.TurnDone?,
    ) {
        val conversationId = (state.localConversationId ?: state.conversationId).takeIf { it.isNotBlank() }
            ?: defaultShimConversationId(agentId)
        val completion = TurnTerminalPolicy.classify(
            deliveredAssistantContent = state.deliveredAssistantContent,
            stopReason = state.stopReason,
            bufferedErrorMessage = state.bufferedErrorMessage,
        )
        val terminalNotice = when (status) {
            BridgeTurnStatus.Failed -> TurnFailureNotices.forFailedTerminal(
                reason = state.bufferedErrorMessage,
                deliveredAssistantContent = state.deliveredAssistantContent,
                mainReplyCompleted = completion.isAuthoritativelyComplete,
                kindHint = state.bufferedErrorKind,
            )
            BridgeTurnStatus.Cancelled -> serverQueueMarks.cancelledTerminalNotice(state.inFlightSend)
            else -> null
        }
        val deadTurn = status is BridgeTurnStatus.Failed && terminalNotice != null
        // Reconciliation/notice writes remain cancellable. Their failure must not strand the
        // terminal's ownership: retain the failure until the mandatory local retirement finishes.
        val terminalWrites = runCatching {
            if (shouldCleanupAbandonedAssistantFragments(status, deadTurn, runId)) {
                cleanupAbandonedAssistantFragmentsSafely(
                    conversationId = conversationId,
                    runId = runId,
                    turnId = turnId,
                    reason = "turn_done_$status",
                    candidateRunIds = activeCleanupCandidateRunIds(state, runId),
                )
            }
            if (recordEvent != null) {
                runtimeEventBatcher.enqueue(recordEvent, conversationId)
            }
            if (lossy) {
                Telemetry.event(
                    "AdminChatVM", "ws.turnDone.lossy",
                    "dropCount" to dropCount,
                    "turnId" to turnId,
                    "runId" to runId,
                )
                state.otid?.let { otid ->
                    timelineRepository.reconcileExternalTransportSend(
                        conversationId = conversationId,
                        agentId = agentId,
                        externalConversationId = state.serverConversationId ?: conversationId,
                        otid = otid,
                    )
                }
            }
            if (status is BridgeTurnStatus.Failed && terminalNotice == null) {
                Telemetry.event(
                    "AdminChatVM", "ws.turnDone.failedAfterDelivery",
                    "turnId" to turnId,
                    "runId" to runId,
                    // Sanitized family only — never the raw reason (letta-mobile-o0atv).
                    "reasonKind" to (state.bufferedErrorKind ?: terminalReasonKind(state.bufferedErrorMessage) ?: "<none>"),
                )
            }
            terminalNotice?.let { notice ->
                appendTurnFailureNotice(conversationId, runId, turnId, notice)
            }
        }
        val stopReason = state.stopReason
        val retirementWrites = retireTerminalTurn(state, ConversationId(conversationId), status, terminalNotice)
        Telemetry.event(
            "AdminChatVM", "ws.turnComplete",
            "conversationId" to conversationId,
            "status" to status,
            "turnId" to turnId,
            "runId" to runId,
            "stopReason" to (stopReason ?: ""),
            "lossy" to lossy,
            "reason" to reason,
        )
        releaseTerminalQueue(QueueConversationId(conversationId), listOf(terminalWrites) + retirementWrites)
    }

    /**
     * Called under the lifecycle mutex. Attempt every local retirement write before propagating
     * cancellation; reconciliation and queue dispatch remain outside this NonCancellable boundary.
     */
    private suspend fun retireTerminalTurn(
        state: ConversationTurnState,
        conversationId: ConversationId,
        status: BridgeTurnStatus,
        terminalNotice: TurnFailureNotice?,
    ): List<Result<Unit>> {
        val deadTurn = status is BridgeTurnStatus.Failed && terminalNotice != null
        return withContext(NonCancellable) {
            val localSettlement = runCatching { settlePendingSend(state, conversationId.value, deadTurn) }
            val foregroundPresence = runCatching {
                // Evaluate after suspension: the user may have selected a different conversation.
                if (ownsForegroundUi(conversationId.value)) {
                    ui.onTurnFinished(TurnTerminalPolicy.nextError(state, status, terminalNotice, ui.currentError()))
                } else {
                    reportBackgroundUiSuppressed(conversationId.value, "onTurnFinished")
                }
            }
            // Unconditional: an earlier turn's dangling card may need its cancelled sweep restored.
            val toolSweep = runCatching {
                timelineRepository.turnEnded(agentId, conversationId.value, clean = status is BridgeTurnStatus.Completed)
            }
            state.reachedTerminal = true
            clearActiveTurnState(state, reason = "turnFinished")
            val transportPresence = runCatching { timelineRepository.clearExternalTransportActive(conversationId.value) }
            listOf(localSettlement, foregroundPresence, toolSweep, transportPresence)
        }
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
            clearActiveTurnState(owner, reason = "otherClientBusy")
            timelineRepository.clearExternalTransportActive(bounced.conversationId.value)
            if (ownsForegroundUi(bounced.conversationId.value)) ui.onTurnVisuallyComplete()
            queueDriver.holdForOtherClientLocked(bounced)
            ui.onSendQueued(bounced.conversationId.value)
            return
        }
        owner.recordTerminalIdentity(event)
        finishActiveTurn(
            state = owner,
            status = event.status,
            runId = event.runId,
            turnId = event.turnId,
            lossy = event.lossy,
            dropCount = event.dropCount,
            reason = "turnDone",
            recordEvent = event,
        )
        if (send == null) queueDriver.onForeignTerminalLocked()
    }

    /**
     *  PR2: SCOPED to the conversation whose presence is being
     * healed. The unscoped `hasAnyActiveChatTurn` read meant conversation B's live
     * turn suppressed conversation A's self-heal, so an evicted or orphaned A
     * presence was never recovered. Only a send with no known target (a brand-new
     * conversation) still falls back to the any-turn question.
     */
    private suspend fun healStaleVisualPresence(conversationId: String?) {
        val transportOwnsTurn = if (conversationId != null) {
            wsChatBridge.hasActiveChatTurn(conversationId)
        } else {
            wsChatBridge.hasAnyActiveChatTurn
        }
        if (transportOwnsTurn) return
        if (!ui.isStreaming() && !ui.isAgentTyping()) return
        ui.onTurnVisuallyComplete()
        val state = peekState(conversationId)
        val orphanedOtid = state?.otid
        if (state != null && conversationId != null) {
            // PR2 review, finding 5: the heal must SETTLE what it discards.
            // Dropping a non-null otid without reconciling its optimistic-local
            // row left that row SENDING forever — the next send installs a new
            // otid, so no later terminal can ever settle the old one, and the
            // timeline's streaming gate stays latched. That recreates the exact
            // stuck indicator this heal exists to remove.
            //
            // Settled as SENT, not failed: the transport accepted this send (it
            // minted an otid) and only its TERMINAL went missing, which is the
            // same evidence the turn-started stale-otid path settles on. Marking
            // it failed would show a retry affordance for a message the server
            // most likely has, inviting a duplicate.
            state.otid?.let { otid ->
                retainSettledOtid(otid)
                timelineRepository.markExternalTransportLocalSent(
                    agentId,
                    state.localConversationId ?: conversationId,
                    otid,
                )
            }
            timelineRepository.clearExternalTransportActive(agentId, conversationId)
            clearActiveTurnState(state, reason = "stalePresenceHealed")
        }
        Telemetry.event(
            "IrohTrace", "coordinator.send.stalePresenceHealed",
            "conversationId" to conversationId,
            "settledOtid" to (orphanedOtid != null),
        )
    }

    /**
     * Clears ONE conversation's turn state. Callers must pass the entry they own —
     * the old unkeyed version wiped every conversation's identity, which is how
     * conversation A's terminal froze conversation B's in-flight send.
     */
    private fun clearActiveTurnState(state: ConversationTurnState, reason: String) {
        // SENSING (letta-mobile-or40x): an entry dropped before its terminal.
        if (state.isTracking && !state.reachedTerminal) {
            Telemetry.event(
                "AdminChatVM", "ws.turnState.clearedBeforeTerminal",
                "conversationId" to state.conversationId,
                "reason" to reason,
                "turnId" to (state.turnId ?: ""),
                "runId" to (state.runId ?: ""),
                "otid" to (state.otid ?: ""),
            )
        }
        state.retainSettledRun()
        state.otid?.let { retainSettledOtid(it) }
        state.otid = null
        state.inFlightSend?.let { queuedSends.clearQueuedOnServer(it.conversationId) }
        state.inFlightSend = null
        state.identity.clear()
        state.localConversationId = null
        state.serverConversationId = null
        state.turnId = null
        state.runId = null
        state.resetDeliveryEvidence()
    }

    private suspend fun failActiveTurnForDisconnect(event: WsTimelineEvent.Disconnected) {
        // A terminal disconnect kills EVERY conversation's turn, so this is the
        // one path that legitimately walks all entries — each is failed against
        // its own conversation id instead of one guessed global.
        val states = turnRegistry.statesSnapshot
        preConversationMessageDeltas.clear()
        queueDriver.onDisconnected()
        states.forEach { state -> failConversationForDisconnect(state, event) }
        if (states.isEmpty()) {
            // Nothing was tracked, but the visible conversation still needs its
            // transport-active flag and dangling-tool sweep reset.
            activeConversationId()?.let { conversationId -> endConversationAbnormally(conversationId) }
        }
        ui.onDisconnectFailure(event.reason.ifBlank { "WebSocket disconnected" })
    }

    private suspend fun failConversationForDisconnect(
        state: ConversationTurnState,
        event: WsTimelineEvent.Disconnected,
    ) {
        val conversationId = state.conversationId.takeIf { it.isNotBlank() } ?: activeConversationId()
        failOptimisticLocalForDisconnect(state, conversationId, event)
        if (conversationId != null) {
            cleanupDisconnectedTurnFragments(state, conversationId)
            endConversationAbnormally(conversationId)
        }
        state.reachedTerminal = true
        clearActiveTurnState(state, reason = "disconnect")
    }

    private suspend fun failOptimisticLocalForDisconnect(
        state: ConversationTurnState,
        conversationId: String?,
        event: WsTimelineEvent.Disconnected,
    ) {
        val otid = state.otid ?: return
        val localConversationId = state.localConversationId ?: conversationId
        if (localConversationId == null) {
            Telemetry.event(
                "AdminChatVM", "ws.activeSend.failedWithoutConversation",
                "otid" to otid,
                "disconnectCode" to event.code,
            )
            return
        }
        timelineRepository.markExternalTransportLocalFailed(agentId, localConversationId, otid)
    }

    private suspend fun cleanupDisconnectedTurnFragments(
        state: ConversationTurnState,
        conversationId: String,
    ) {
        val cleanupRunId = state.runId
        val cleanupTurnId = state.turnId
        if (state.otid == null && cleanupRunId == null && cleanupTurnId == null) return
        cleanupAbandonedAssistantFragmentsSafely(
            conversationId = conversationId,
            runId = cleanupRunId,
            turnId = cleanupTurnId,
            reason = "disconnect",
            candidateRunIds = activeCleanupCandidateRunIds(state, cleanupRunId),
        )
    }

    /**
     * Skip abandoned-fragment cleanup for delivered-then-failed turns: a legitimate
     * short reply (for example, "OK") must survive an aux-only failure. An Iroh
     * synthetic terminal is the exception because it can name a placeholder while
     * observed fragments use the promoted App Server run id.
     */
    private fun shouldCleanupAbandonedAssistantFragments(
        status: BridgeTurnStatus,
        deadTurn: Boolean,
        runId: String,
    ): Boolean = status is BridgeTurnStatus.Cancelled ||
        (status is BridgeTurnStatus.Failed && (
            deadTurn || IROH_SYNTHETIC_RUN_ID_PREFIXES.any(runId::startsWith)
        ))

    /**
     * An abnormal end (not a clean completion) — this
     * turn's OWN calls are already settled synchronously by AppServerTurnEngine on
     * disconnect/cancel/error paths, so they won't show up as unresolved here.
     * Still call turnEnded unconditionally: a
     * DIFFERENT, earlier turn's dangling card may have had its sweep cancelled by
     * this turn's turnStarted and needs this call to reschedule it, or it would
     * spin forever.
     */
    private suspend fun endConversationAbnormally(conversationId: String) {
        timelineRepository.clearExternalTransportActive(conversationId)
        runCatching { timelineRepository.turnEnded(agentId, conversationId, clean = false) }
    }

    private fun activeCleanupCandidateRunIds(
        state: ConversationTurnState,
        primaryRunId: String?,
    ): Set<String> = buildSet {
        primaryRunId?.takeIf { it.isNotBlank() }?.let(::add)
        state.runId?.takeIf { it.isNotBlank() }?.let(::add)
        state.activeAssistantMessageRunIds.mapNotNullTo(this) { it.takeIf(String::isNotBlank) }
    }

    private suspend fun markTurnVisuallyComplete(state: ConversationTurnState, reason: String) {
        val conversationId = (state.localConversationId ?: state.conversationId).takeIf { it.isNotBlank() }
            ?: activeConversationId()
            ?: defaultShimConversationId(agentId)
        state.otid?.let { otid ->
            timelineRepository.markExternalTransportLocalSent(agentId, state.localConversationId ?: conversationId, otid)
        }
        timelineRepository.clearExternalTransportActive(agentId, conversationId)
        if (ownsForegroundUi(conversationId)) {
            ui.onTurnVisuallyComplete()
        } else {
            reportBackgroundUiSuppressed(conversationId, "onTurnVisuallyComplete")
        }
        Telemetry.event(
            "AdminChatVM", "ws.turnVisuallyComplete",
            "conversationId" to conversationId,
            "reason" to reason,
            "turnId" to (state.turnId ?: ""),
        )
    }

    /**
     * An Error frame that names a conversation belongs to that conversation's
     * entry — but ONLY if this coordinator owns that conversation.
     *
     * `wsChatBridge.events` is a global flow shared by every per-agent
     * coordinator, and Error frames carry no agentId. Creating an entry for any
     * named conversation (PR2 review, finding 4) let agent B's failure open a
     * conversation inside agent A's map and be recorded through A's runtime sink.
     * Ownership means: an entry we already track, or the conversation currently
     * bound to this coordinator. Anything else is foreign and dropped — this is
     * the old foreign-conversation guard, restored in keyed form.
     */
    private fun resolveStateForError(event: WsTimelineEvent.Error): ConversationTurnState? {
        val named = event.conversationId?.takeIf { it.isNotBlank() }
            ?: return turnRegistry.resolveStateByTurn(TurnTail(event.turnId, event.runId))
        val resolved = resolveConversationId(named) ?: named
        peekState(resolved)?.let { return it }
        if (resolved == activeConversationId()) return stateFor(resolved)
        return null
    }

    private suspend fun drainPreConversationMessages(conversationId: String) {
        val state = peekState(conversationId)
        while (true) {
            val delta = preConversationMessageDeltas.removeFirstOrNull() ?: return
            runtimeEventBatcher.enqueue(delta, conversationId)
            rememberActiveAssistantMessageRunId(
                state = state,
                message = delta.message,
                frameConversationId = delta.conversationId,
                isReplay = delta.isReplay,
            )
            timelineRepository.ingestExternalTransportMessage(agentId, conversationId, delta.message, source = "coordinator.preConversationDrain")
        }
    }

    internal companion object {
        private const val CONNECT_WAIT_MS = 1_500L
        private const val MAX_ACTIVE_ASSISTANT_RUN_IDS = 8

        internal var postSendReconcileDelaysMs = longArrayOf(750L, 2_500L, 6_000L)
        private const val CURSOR_EXPIRED_ERROR_CODE = "cursor_expired"
        private fun defaultShimConversationId(agentId: String): String = "conv-default-$agentId"
    }

}
