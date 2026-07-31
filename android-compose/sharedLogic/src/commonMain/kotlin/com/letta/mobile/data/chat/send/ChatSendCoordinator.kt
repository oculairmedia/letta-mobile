package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.runtime.TurnFailureNotice
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.runtime.terminalReasonKind
import com.letta.mobile.data.timeline.IROH_SYNTHETIC_RUN_ID_PREFIXES
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.transport.api.RedialWhileTurnActive
import com.letta.mobile.util.Telemetry
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

import kotlin.time.Duration.Companion.milliseconds
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
    // Send acceptance, transport events, and cleanup all mutate one ownership graph. Serializing
    // their suspend paths makes the lifecycle decision and the matching UI/OTID mutation atomic.
    private val turnStateMutex = Mutex()

    // letta-mobile-or40x PR2: turn identity is PER CONVERSATION.
    //
    // This used to be ONE process-wide set of `activeWs*` fields plus a single
    // [TurnIdentityLifecycle]. With conversation A streaming, a send into
    // conversation B overwrote A's otid/turn/run identity, and A's terminal then
    // wiped B's in-flight send identity — the observed "both conversations
    // freeze" defect. Every one of those fields now lives inside a
    // [ConversationTurnState] keyed by conversationId, and each conversation
    // owns its own terminal fence ([TurnIdentityLifecycle]).
    private val turnStateLock = SynchronizedObject()
    private val turnStates = linkedMapOf<String, ConversationTurnState>()

    // Genuinely ambiguous fallback: TurnDone/SubscribeDone/StopReason frames carry
    // no conversationId. When neither the turn id nor the run id identifies an
    // owner we fall back to the conversation that most recently owned turn
    // activity — the honest approximation of the old single global, used ONLY
    // where the wire gives us nothing to key on (see [resolveStateByTurnId]).
    @Volatile private var lastActiveConversationId: String? = null
    private val preConversationMessageDeltas = ArrayDeque<WsTimelineEvent.MessageDelta>()
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
        scope.launch {
            wsChatBridge.redialWhileTurnActive.collect { event -> handleRedialWhileTurnActive(event) }
        }
    }

    /**
     * All per-turn state for ONE conversation. Mutations are serialized by
     * [turnStateMutex] (every send / transport-event / redial entry point takes
     * it); the fields are volatile so a read from another dispatcher thread
     * still observes the published value.
     */
    private class ConversationTurnState(val conversationId: String) {
        /** Terminal fence + generation ownership for THIS conversation only. */
        val identity = TurnIdentityLifecycle()
        @Volatile var otid: String? = null
        @Volatile var localConversationId: String? = null

        // letta-mobile-i8iw: lcp-cv3 contract — stop_reason and usage_statistics
        // are first-wins per turn on the shim. We capture once and drop later
        // duplicates defensively (drop with telemetry rather than overwriting).
        @Volatile var turnId: String? = null
        @Volatile var runId: String? = null
        val activeAssistantMessageRunIds = linkedSetOf<String>()
        @Volatile var stopReason: String? = null
        @Volatile var usageRecorded: Boolean = false

        // lcp-axv: failed turns emit `error` THEN `turn_done(status="failed")`
        // in lock-step. We buffer the error message and only flip UI state when
        // TurnDone arrives, so the "agent typing" indicator clears in sync with
        // the actual end-of-turn signal.
        @Volatile var bufferedErrorMessage: String? = null

        // letta-mobile-br5g0 (codex review): the mapper's Error frame already
        // carries the sanitized failure family in `code`; reclassifying the fixed
        // copy in `message` downgraded content_filter to provider_error. Buffer the
        // wire family (when it is a known one) alongside the message.
        @Volatile var bufferedErrorKind: String? = null

        // letta-mobile-br5g0: set when this turn ingested assistant content. Drives
        // the dead-turn vs delivered-then-failed split in [finishActiveTurn].
        @Volatile var deliveredAssistantContent: Boolean = false

        /** Set on the terminal path so a clear can tell "settled" from "evicted". */
        @Volatile var reachedTerminal: Boolean = false

        /** True while this conversation owns a send or a turn that has not settled. */
        val isTracking: Boolean
            get() = identity.active != null || otid != null || turnId != null
    }

    private fun stateFor(conversationId: String): ConversationTurnState = synchronized(turnStateLock) {
        val state = turnStates.getOrPut(conversationId) { ConversationTurnState(conversationId) }
        evictOverflowStatesLocked(keep = conversationId)
        state
    }

    private fun peekState(conversationId: String?): ConversationTurnState? =
        conversationId?.let { synchronized(turnStateLock) { turnStates[it] } }

    private fun snapshotStates(): List<ConversationTurnState> =
        synchronized(turnStateLock) { turnStates.values.toList() }

    private fun trackedConversationCount(): Int = synchronized(turnStateLock) { turnStates.size }

    /**
     * SENSING (letta-mobile-or40x): a turn entry that is dropped while it still
     * owns a send/turn is exactly the clobber this PR exists to kill, so it is
     * never silent.
     */
    private fun evictOverflowStatesLocked(keep: String) {
        while (turnStates.size > MAX_TRACKED_CONVERSATION_TURN_STATES) {
            val victimKey = turnStates.keys.firstOrNull { it != keep } ?: return
            val victim = turnStates.remove(victimKey) ?: return
            if (victim.isTracking && !victim.reachedTerminal) {
                Telemetry.event(
                    "AdminChatVM", "ws.turnState.evictedBeforeTerminal",
                    "conversationId" to victim.conversationId,
                    "turnId" to (victim.turnId ?: ""),
                    "otid" to (victim.otid ?: ""),
                    "tracked" to turnStates.size,
                )
            }
        }
    }

    /**
     * Only for frames that carry NO conversation key at all. Prefers the most
     * recently active conversation, then the single tracked conversation.
     */
    private fun fallbackState(): ConversationTurnState? = synchronized(turnStateLock) {
        lastActiveConversationId?.let { turnStates[it] } ?: turnStates.values.singleOrNull()
    }

    /**
     * Resolves the conversation that owns a turn-keyed frame.
     *
     * Exact turn match wins. Failing that, an accepted send that has not yet seen
     * its `TurnStarted` is the only entry the frame can belong to — but only when
     * exactly ONE such entry exists, otherwise we would be guessing across
     * conversations (which is the bug). When some other conversation already owns
     * an identified turn and nothing here matches, the frame is foreign: return
     * null so the caller can drop it instead of clobbering an unrelated turn.
     */
    private fun resolveStateByTurnId(turnId: String?): ConversationTurnState? {
        val key = turnId?.takeIf { it.isNotBlank() }
        val states = snapshotStates()
        if (key != null) {
            states.firstOrNull { it.turnId == key }?.let { return it }
            val awaitingTurnStarted = states.filter { it.turnId == null && it.identity.active != null }
            if (awaitingTurnStarted.size == 1) return awaitingTurnStarted.single()
            if (states.any { it.turnId != null }) return null
        }
        return fallbackState()
    }

    /** Run-keyed variant for frames (SubscribeDone) that carry a run id but no turn id. */
    private fun resolveStateByRunId(runId: String?): ConversationTurnState? {
        val key = runId?.takeIf { it.isNotBlank() } ?: return fallbackState()
        val states = snapshotStates()
        states.firstOrNull { it.runId == key }?.let { return it }
        val awaitingTurnStarted = states.filter { it.runId == null && it.identity.active != null }
        if (awaitingTurnStarted.size == 1) return awaitingTurnStarted.single()
        if (states.any { it.runId != null }) return null
        return fallbackState()
    }

    /**
     * SENSING (letta-mobile-or40x): a send starting for one conversation while
     * ANOTHER conversation's identity is still non-terminal is precisely the
     * overlap that used to evict state. Legal now — but recorded.
     */
    private fun reportCrossConversationSend(conversationId: String) {
        val others = snapshotStates().filter {
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
     * SENSING (letta-mobile-or40x): a frame that matches no tracked conversation
     * entry. Before keying, these silently landed on whatever the single global
     * happened to hold — i.e. they were misattributed rather than dropped.
     */
    private fun reportUnmatchedFrame(event: WsTimelineEvent, turnId: String?, runId: String?) {
        Telemetry.event(
            "AdminChatVM", "ws.event.unmatchedConversation",
            "eventType" to (event::class.simpleName ?: ""),
            "turnId" to (turnId ?: ""),
            "runId" to (runId ?: ""),
            "trackedConversations" to trackedConversationCount(),
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
            return
        }
        // Only the legacy admin-shim WS actually needs a bearer token. The Iroh
        // transport authenticates the paired peer by NodeID and ignores the
        // token entirely (IrohChannelTransport sends its auth frame even with a
        // blank token and only fails on an explicit auth rejection). Gating Iroh
        // sends on a token was the sole reason paired devices had to carry one —
        // relaxing it here is the client half of retiring the bearer token
        // (d6e8g.9). Token-carrying devices are unaffected; this only stops the
        // client from self-rejecting a BLANK token on an iroh:// backend.
        if (config.accessToken.isNullOrBlank() && !config.isIrohBackend()) {
            ui.onSendFailed("Admin-shim WebSocket requires an API token")
            return
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
        val currentConversationId = targetConversationId?.takeIf { it.isNotBlank() }
        val startNewConversation = false
        val conversationId = when {
            currentConversationId != null -> currentConversationId
            else -> runCatching {
                conversationRepository.createConversation(AgentId(agentId)).id.value
            }.getOrElse { err ->
                Telemetry.error("AdminChatVM", "ws.send.createConversationFailed", err)
                ui.onSendFailed("Failed to create a new conversation: ${err.message ?: "unknown"}")
                timer.stop("accepted" to false, "reason" to "create_failed")
                return
            }
        }
        stateFor(conversationId)
        reportCrossConversationSend(conversationId)
        if (!startNewConversation) {
            lastActiveConversationId = conversationId
            setActiveConversationId(conversationId)
            startTimelineObserver(conversationId)
        }

        Telemetry.event("IrohTrace", "coordinator.ensureConnected.begin", "conversationId" to conversationId)
        val connected = ensureConnected(config)
        Telemetry.event("IrohTrace", "coordinator.ensureConnected.done", "conversationId" to conversationId, "connected" to connected)
        if (!connected) {
            ui.onSendFailed("Admin-shim WebSocket is not connected")
            timer.stop("accepted" to false, "reason" to "not_connected")
            return
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
            return
        }
        if (!accepted && !enqueuePendingSend(pending)) {
            ui.onError("WebSocket send queue is full; wait for the current turn to finish")
            timer.stop("accepted" to false, "reason" to "busy")
            return
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
        val conversationId = activeConversationId() ?: lastActiveConversationId ?: return false
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
        val state = stateFor(pending.conversationId)
        val accepted = state.identity.acceptSend(
            conversationId = pending.conversationId,
            send = {
                wsChatBridge.send(
                    agentId = agentId,
                    conversationId = pending.conversationId,
                    text = pending.text,
                    otid = pending.otid,
                    attachments = pending.attachments,
                    startNewConversation = pending.startNewConversation,
                )
            },
            onAccepted = {
                state.otid = pending.otid
                state.localConversationId = pending.conversationId.takeIf { it.isNotBlank() }
                state.reachedTerminal = false
                pending.conversationId.takeIf { it.isNotBlank() }?.let { lastActiveConversationId = it }
            },
        )
        Telemetry.event(
            "IrohTrace", "dispatchPendingSend.bridgeSend.done",
            "conversationId" to pending.conversationId,
            "otid" to pending.otid,
            "accepted" to accepted,
        )
        if (!accepted) return false

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
                delay(delayMs.milliseconds)
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
            delay(DEQUEUE_RETRY_DELAY_MS.milliseconds)
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
        val attrs = buildList {
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
        return withTimeoutOrNull(CONNECT_WAIT_MS.milliseconds) {
            wsChatBridge.awaitConnected()
            true
        } ?: false
    }

    suspend fun handleEvent(event: WsTimelineEvent) = turnStateMutex.withLock {
        handleEventLocked(event)
    }

    private suspend fun handleEventLocked(event: WsTimelineEvent) {
        // letta-mobile-sfex6: strict agent scoping. wsChatBridge.events is a
        // GLOBAL flow — every per-(agentId,conversationId) coordinator collects
        // it, so a frame for one agent reaches every coordinator. When two
        // agents share the bare conversation id "default" (main + a subagent),
        // a foreign agent's TurnStarted would otherwise open a turn entry in THIS
        // coordinator and its deltas would ingest into our timeline — the
        // cross-conversation leak. Drop any event that carries an explicit
        // agentId not matching ours BEFORE it can mutate any turn entry.
        // (MessageDelta/StopReason/etc. carry no agentId; they are scoped
        // transitively because a conversation entry only ever gains a turn id
        // from a TurnStarted that passed this gate.)
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
            "lastActiveConversationId" to lastActiveConversationId,
            "trackedConversations" to trackedConversationCount(),
        )
        if (dropDuplicateBridgeEvent(event)) return
        when (event) {
            is WsTimelineEvent.TurnStarted -> handleTurnStarted(event)
            is WsTimelineEvent.MessageDelta -> {
                val conversationId = event.conversationId ?: lastActiveConversationId ?: activeConversationId()
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
                recordRuntimeEvent(event, conversationId)
                rememberActiveAssistantMessageRunId(
                    state = peekState(conversationId),
                    message = event.message,
                    frameConversationId = event.conversationId,
                    isReplay = event.isReplay,
                )
                timelineRepository.ingestExternalTransportMessage(agentId, conversationId, event.message, source = "coordinator")
                if (!event.isReplay) {
                    rememberLiveIngest(conversationId)
                    ui.onMessageDelta(conversationId)
                }
            }
            is WsTimelineEvent.StopReason -> {
                val state = resolveStateByTurnId(event.turnId)
                if (state == null) {
                    reportUnmatchedFrame(event, event.turnId, event.runId)
                    return
                }
                recordRuntimeEvent(event, state.conversationId)
                if (ignoreForeignTurnStop(state, event)) return
                recordStopReasonForTurn(state, event)
                markTurnVisuallyComplete(state, reason = "stopReason")
            }
            is WsTimelineEvent.UsageStatistics -> {
                val state = resolveStateByTurnId(event.turnId)
                if (state == null) {
                    reportUnmatchedFrame(event, event.turnId, event.runId)
                    return
                }
                recordRuntimeEvent(event, state.conversationId)
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
            is WsTimelineEvent.TurnDone -> {
                // Lifecycle ownership is the terminal fence, now resolved to the
                // OWNING conversation first. It rejects delayed terminals from
                // older generations without touching the accepted send or the
                // newer turn that currently owns UI presence — and, since PR2,
                // without touching a DIFFERENT conversation's live turn.
                val owner = resolveStateByTurnId(event.turnId)
                if (owner == null || !owner.identity.acceptsTerminal(event.turnId)) {
                    if (owner == null) reportUnmatchedFrame(event, event.turnId, event.runId)
                    if (event.status == "failed" || event.status == "cancelled") {
                        val conversationId = (owner ?: fallbackState())?.conversationId
                            ?: lastActiveConversationId
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
                    return
                }
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
            }
            is WsTimelineEvent.SubscribeDone -> {
                // SubscribeDone carries a run id only; resolve the owning
                // conversation from it before finalizing anything.
                val state = resolveStateByRunId(event.runId)
                if (state != null && (state.otid != null || ui.isStreaming())) {
                    finishActiveTurn(
                        state = state,
                        status = event.status,
                        runId = event.runId,
                        turnId = state.turnId.orEmpty(),
                        lossy = false,
                        dropCount = 0L,
                        reason = "subscribeDone",
                        recordEvent = null,
                    )
                }
            }
            is WsTimelineEvent.Error -> {
                if (event.code == CURSOR_EXPIRED_ERROR_CODE) {
                    val conversationId = event.conversationId ?: lastActiveConversationId ?: activeConversationId()
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
                // An Error frame belongs to the conversation it names. Buffering
                // it into THAT conversation's state (instead of a single global)
                // is what stops a foreign failure from poisoning another
                // conversation's turn.
                val state = resolveStateForError(event)
                if (state == null) {
                    reportUnmatchedFrame(event, event.turnId, event.runId)
                    return
                }
                // Still ignore a frame for a different TURN inside the same
                // conversation so a superseded turn cannot poison the live one.
                if (event.turnId != null &&
                    state.turnId != null &&
                    event.turnId != state.turnId
                ) {
                    return
                }
                recordRuntimeEvent(event, state.conversationId)
                // lcp-axv: stash the error and wait for the immediately-
                // following TurnDone to flip the UI. Surfacing the error
                // here would race with TurnDone and could leave isStreaming
                // / isAgentTyping stuck if TurnDone is delayed.
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
            is WsTimelineEvent.Disconnected -> {
                if (event.willReconnect && !event.isAuthFailure) {
                    Telemetry.event(
                        "AdminChatVM", "ws.disconnected.transient",
                        "code" to event.code,
                        "attempt" to event.reconnectAttempt,
                    )
                    ui.onTransientDisconnect(hasActiveSend = snapshotStates().any { it.otid != null })
                    return
                }
                failActiveTurnForDisconnect(event)
            }
            is WsTimelineEvent.GoalsUpdated -> Unit
            is WsTimelineEvent.AgentUpdated -> Unit
            is WsTimelineEvent.UserActionOutcome ->
                recordRuntimeEvent(event, event.conversationId ?: lastActiveConversationId)
        }
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
        runCatching {
            timelineRepository.reconcileRecentMessages(
                agentId = agentId,
                conversationId = event.conversationId,
                reason = "redial-recovery",
                forceRefresh = true,
            )
        }.onFailure { error ->
            Telemetry.error(
                "AdminChatVM", "ws.redialRecovery.reconcileFailed", error,
                "conversationId" to event.conversationId,
                "turnId" to event.turnId,
                "runId" to event.runId,
            )
        }
        finishActiveTurn(
            state = state,
            status = "completed",
            runId = event.runId,
            turnId = event.turnId,
            lossy = false,
            dropCount = 0L,
            reason = "redial-recovery",
            recordEvent = WsTimelineEvent.TurnDone(
                turnId = event.turnId,
                runId = event.runId,
                status = "completed",
            ),
        )
    }

    private suspend fun handleTurnStarted(event: WsTimelineEvent.TurnStarted) {
        val state = stateFor(event.conversationId)
        val identityTransition = state.identity.turnStarted(
            conversationId = event.conversationId,
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
                "conversationId" to event.conversationId,
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
            val staleLocalConv = state.localConversationId ?: event.conversationId
            scope.launch {
                runCatching {
                    timelineRepository.markExternalTransportLocalSent(agentId, staleLocalConv, staleOtid)
                }
            }
            Telemetry.event(
                "AdminChatVM", "ws.turnStarted.staleOtidSettled",
                "staleOtid" to staleOtid,
                "newTurnId" to event.turnId,
                "conversationId" to event.conversationId,
            )
        }
        lastActiveConversationId = event.conversationId
        state.turnId = event.turnId
        state.runId = event.runId
        state.reachedTerminal = false
        state.activeAssistantMessageRunIds.clear()
        state.stopReason = null
        state.usageRecorded = false
        state.bufferedErrorMessage = null
        state.bufferedErrorKind = null
        state.deliveredAssistantContent = false
        // letta-mobile-dangling-tool: a fresh turn on this conversation
        // supersedes whatever the previous turn's post-turn dangling-
        // tool-call sweep left pending.
        runCatching { timelineRepository.turnStarted(agentId, event.conversationId) }
        recordRuntimeEvent(event, event.conversationId)
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

    private fun dropDuplicateBridgeEvent(event: WsTimelineEvent): Boolean {
        val key = bridgeEventKey(event) ?: return false
        val isDuplicate = if (event is WsTimelineEvent.MessageDelta) {
            synchronized(sharedMessageEventLock) {
                if (key in sharedMessageEventKeySet) {
                    true
                } else {
                    sharedMessageEventKeySet.add(key)
                    sharedMessageEventKeys.addLast(key)
                    while (sharedMessageEventKeys.size > MAX_SEEN_BRIDGE_EVENTS) {
                        sharedMessageEventKeySet.remove(sharedMessageEventKeys.removeFirst())
                    }
                    false
                }
            }
        } else {
            synchronized(seenBridgeEventLock) {
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
            val conversationId = event.conversationId
                ?: lastActiveConversationId
                ?: activeConversationId().orEmpty()
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

    /**
     * letta-mobile-br5g0: renders a dead turn as a visible ERROR row in the
     * chat timeline (the same TimelineMessageType.ERROR surface letta-mobile-5s1n
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
     * letta-mobile-br5g0: "did this turn deliver assistant content" is read
     * from observed turn state (an assistant delta with non-blank text was
     * ingested), never inferred from timing.
     *
     * codex review (P1): the evidence must BELONG to the active turn.
     * Server-side Iroh fanout and replay push assistant deltas from other
     * conversations/turns through this same flow; counting those marked a
     * failing turn as delivered and suppressed its failure notice. Scope:
     * never from replay, only while a turn is active, only when the frame's
     * conversation (when tagged — r3i1z) matches, and only when the message's
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
        return runMatchesActiveTurn(state, message.runId)
    }

    /** Untagged frames (r3i1z) fall back to active-turn scoping. */
    private fun frameConversationMatchesActive(
        state: ConversationTurnState,
        frameConversationId: String?,
    ): Boolean {
        val tagged = frameConversationId ?: return true
        return tagged == state.conversationId
    }

    /**
     * Synthetic iroh-run-* placeholders are treated as unknown (run-id
     * promotion may not have landed yet); a message without a run id falls
     * back to active-turn scoping.
     */
    private fun runMatchesActiveTurn(state: ConversationTurnState, runId: String?): Boolean {
        val messageRunId = runId?.takeIf { it.isNotBlank() } ?: return true
        val activeRun = state.runId ?: return true
        if (IROH_SYNTHETIC_RUN_ID_PREFIXES.any { activeRun.startsWith(it) }) return true
        return messageRunId == activeRun
    }

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

    /**
     * lcp-cv3: stop_reason is first-wins for telemetry, but intermediate stops
     * (requires_approval) may still be followed by a completed main-reply stop —
     * upgrade so Failed-after-delivered suppression uses the right signal.
     */
    /**
     * letta-mobile-br5g0 (codex review P1): a stop_reason from a DIFFERENT
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

    private suspend fun finishActiveTurn(
        state: ConversationTurnState,
        status: String,
        runId: String,
        turnId: String,
        lossy: Boolean,
        dropCount: Long,
        reason: String,
        recordEvent: WsTimelineEvent.TurnDone?,
    ) {
        val conversationId = state.conversationId.takeIf { it.isNotBlank() }
            ?: defaultShimConversationId(agentId)
        // letta-mobile-br5g0: a Failed terminal has two very different user
        // meanings. Only a turn that delivered NO completed assistant reply is a
        // dead turn worth a hard error state; a Failed terminal that lands after
        // the reply completed (non-error stop_reason) is a trailing aux-step
        // failure and must not be painted like a dead turn.
        val mainReplyCompleted = state.deliveredAssistantContent &&
            TurnFailureNotices.isCompletedMainReplyStopReason(state.stopReason)
        val failureNotice = if (status == "failed") {
            TurnFailureNotices.forFailedTerminal(
                reason = state.bufferedErrorMessage,
                deliveredAssistantContent = state.deliveredAssistantContent,
                mainReplyCompleted = mainReplyCompleted,
                kindHint = state.bufferedErrorKind,
            )
        } else {
            null
        }
        val deadTurn = failureNotice != null
        // Skip abandoned-fragment cleanup for delivered-then-failed turns: a
        // legitimate short reply (e.g. "OK") must not be purged before we
        // classify the failure as aux-only.
        if (status == "cancelled" || (status == "failed" && deadTurn)) {
            cleanupAbandonedAssistantFragmentsSafely(
                conversationId = conversationId,
                runId = runId,
                turnId = turnId,
                reason = "turn_done_$status",
                candidateRunIds = activeCleanupCandidateRunIds(state, runId),
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
            state.otid?.let { otid ->
                timelineRepository.reconcileExternalTransportSend(
                    conversationId = conversationId,
                    agentId = agentId,
                    externalConversationId = conversationId,
                    otid = otid,
                )
            }
        }
        if (status == "failed" && failureNotice == null) {
            Telemetry.event(
                "AdminChatVM", "ws.turnDone.failedAfterDelivery",
                "turnId" to turnId,
                "runId" to runId,
                // Sanitized family only — never the raw reason (letta-mobile-o0atv).
                "reasonKind" to (state.bufferedErrorKind ?: terminalReasonKind(state.bufferedErrorMessage) ?: "<none>"),
            )
        }
        failureNotice?.let { notice ->
            appendTurnFailureNotice(conversationId, runId, turnId, notice)
        }
        state.otid?.let { otid ->
            val localConversationId = state.localConversationId ?: conversationId
            if (deadTurn) {
                timelineRepository.markExternalTransportLocalFailed(agentId, localConversationId, otid)
            } else {
                timelineRepository.markExternalTransportLocalSent(agentId, localConversationId, otid)
            }
        }
        val stopReasonError = state.stopReason.equals("error", ignoreCase = true)
        val nextError = when (status) {
            "completed" -> state.bufferedErrorMessage
                ?: if (stopReasonError) BARE_STOP_REASON_ERROR_MESSAGE else ui.currentError()
            "cancelled" -> ui.currentError()
            // Delivered-then-failed keeps whatever error state was already on
            // screen (normally none) — the user got their answer.
            "failed" -> if (deadTurn) {
                state.bufferedErrorMessage ?: failureNotice!!.message
            } else {
                ui.currentError()
            }
            else -> state.bufferedErrorMessage
                ?: if (stopReasonError) BARE_STOP_REASON_ERROR_MESSAGE else "Turn ended unexpectedly ($status)"
        }
        ui.onTurnFinished(nextError)
        Telemetry.event(
            "AdminChatVM", "ws.turnComplete",
            "conversationId" to conversationId,
            "status" to status,
            "turnId" to turnId,
            "runId" to runId,
            "stopReason" to (state.stopReason ?: ""),
            "lossy" to lossy,
            "reason" to reason,
        )
        // letta-mobile-dangling-tool: this is the seam that observes every
        // turn-end path (turnDone, subscribeDone, redial-recovery all funnel
        // through finishActiveTurn). `clean` (status == "completed") is
        // passed through for telemetry only — per Codex #902 review finding
        // 3, turnEnded schedules its sweep unconditionally, on clean AND
        // abnormal endings alike. That's safe: THIS turn's own calls are
        // already settled synchronously in AppServerTurnEngine on non-clean
        // endings and so never show up as unresolved, but an EARLIER turn's
        // still-dangling card (whose sweep this turn's own turnStarted()
        // cancelled) needs exactly this unconditional reschedule or it would
        // never resolve.
        runCatching { timelineRepository.turnEnded(agentId, conversationId, clean = status == "completed") }
        state.reachedTerminal = true
        clearActiveTurnState(state, reason = "turnFinished")
        timelineRepository.clearExternalTransportActive(conversationId)
        drainPendingSend()
    }

    /**
     * letta-mobile-or40x PR2: SCOPED to the conversation whose presence is being
     * healed. The unscoped `hasAnyActiveChatTurn` read meant conversation B's live
     * turn suppressed conversation A's self-heal, so an evicted or orphaned A
     * presence was never recovered. Only a send with no known target (a brand-new
     * conversation) still falls back to the any-turn question.
     */
    private fun healStaleVisualPresence(conversationId: String?) {
        val transportOwnsTurn = if (conversationId != null) {
            wsChatBridge.hasActiveChatTurn(conversationId)
        } else {
            wsChatBridge.hasAnyActiveChatTurn
        }
        if (transportOwnsTurn) return
        if (!ui.isStreaming() && !ui.isAgentTyping()) return
        ui.onTurnVisuallyComplete()
        peekState(conversationId)?.let { clearActiveTurnState(it, reason = "stalePresenceHealed") }
        Telemetry.event(
            "IrohTrace", "coordinator.send.stalePresenceHealed",
            "conversationId" to conversationId,
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
        state.otid = null
        state.identity.clear()
        state.localConversationId = null
        state.turnId = null
        state.runId = null
        state.activeAssistantMessageRunIds.clear()
        state.stopReason = null
        state.usageRecorded = false
        state.bufferedErrorMessage = null
        state.bufferedErrorKind = null
        state.deliveredAssistantContent = false
    }

    private suspend fun failActiveTurnForDisconnect(event: WsTimelineEvent.Disconnected) {
        // A terminal disconnect kills EVERY conversation's turn, so this is the
        // one path that legitimately walks all entries — each is failed against
        // its own conversation id instead of one guessed global.
        val states = snapshotStates()
        preConversationMessageDeltas.clear()
        clearPendingSends("disconnect")
        for (state in states) {
            val conversationId = state.conversationId.takeIf { it.isNotBlank() } ?: activeConversationId()
            state.otid?.let { otid ->
                val localConversationId = state.localConversationId ?: conversationId
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
            if (conversationId != null) {
                val cleanupRunId = state.runId
                val cleanupTurnId = state.turnId
                if (state.otid != null || cleanupRunId != null || cleanupTurnId != null) {
                    cleanupAbandonedAssistantFragmentsSafely(
                        conversationId = conversationId,
                        runId = cleanupRunId,
                        turnId = cleanupTurnId,
                        reason = "disconnect",
                        candidateRunIds = activeCleanupCandidateRunIds(state, cleanupRunId),
                    )
                }
                timelineRepository.clearExternalTransportActive(conversationId)
                // letta-mobile-dangling-tool: an abnormal end (not a clean
                // completion) — this turn's OWN calls are already settled
                // synchronously by AppServerTurnEngine on disconnect/cancel/error
                // paths, so they won't show up as unresolved here. Still call
                // turnEnded unconditionally (Codex #902 review finding 3): a
                // DIFFERENT, earlier turn's dangling card may have had its sweep
                // cancelled by this turn's turnStarted() and needs this call to
                // reschedule it, or it would spin forever.
                runCatching { timelineRepository.turnEnded(agentId, conversationId, clean = false) }
            }
            state.reachedTerminal = true
            clearActiveTurnState(state, reason = "disconnect")
        }
        if (states.isEmpty()) {
            // Nothing was tracked, but the visible conversation still needs its
            // transport-active flag and dangling-tool sweep reset.
            activeConversationId()?.let { conversationId ->
                timelineRepository.clearExternalTransportActive(conversationId)
                runCatching { timelineRepository.turnEnded(agentId, conversationId, clean = false) }
            }
        }
        ui.onDisconnectFailure(event.reason.ifBlank { "WebSocket disconnected" })
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
        val conversationId = state.conversationId.takeIf { it.isNotBlank() }
            ?: activeConversationId()
            ?: defaultShimConversationId(agentId)
        state.otid?.let { otid ->
            timelineRepository.markExternalTransportLocalSent(agentId, state.localConversationId ?: conversationId, otid)
        }
        timelineRepository.clearExternalTransportActive(agentId, conversationId)
        ui.onTurnVisuallyComplete()
        Telemetry.event(
            "AdminChatVM", "ws.turnVisuallyComplete",
            "conversationId" to conversationId,
            "reason" to reason,
            "turnId" to (state.turnId ?: ""),
        )
    }

    /**
     * An Error frame that names a conversation belongs to that conversation's
     * entry (created if this is the first thing we have seen for it — buffering
     * there is harmless and strictly better than poisoning a live turn). Untagged
     * frames fall back to turn-id resolution.
     */
    private fun resolveStateForError(event: WsTimelineEvent.Error): ConversationTurnState? {
        event.conversationId?.takeIf { it.isNotBlank() }?.let { return stateFor(it) }
        return resolveStateByTurnId(event.turnId)
    }

    private suspend fun drainPreConversationMessages(conversationId: String) {
        val state = peekState(conversationId)
        while (true) {
            val delta = preConversationMessageDeltas.removeFirstOrNull() ?: return
            recordRuntimeEvent(delta, conversationId)
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
        private const val MAX_PENDING_SENDS = 10
        private const val MAX_SEEN_BRIDGE_EVENTS = 512
        private val sharedMessageEventLock = SynchronizedObject()
        private val sharedMessageEventKeys = ArrayDeque<String>()
        private val sharedMessageEventKeySet = mutableSetOf<String>()
        private const val MAX_ACTIVE_ASSISTANT_RUN_IDS = 8

        // letta-mobile-or40x PR2: bound on per-conversation turn entries. Real
        // usage keeps a handful live; the cap only stops an unbounded map when
        // frames arrive for many conversations. Overflow eviction is telemetered.
        private const val MAX_TRACKED_CONVERSATION_TURN_STATES = 32
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

/**
 * True when the active backend is an `iroh://` node (bare, or a corrupted
 * `https://iroh://` saved config). Mirrors `IrohChannelTransport.isIrohUrl` /
 * the ShimBackendDetector check, kept commonMain-local so the send path can tell
 * an Iroh backend (authenticates by paired NodeID, no bearer token needed) from
 * the legacy admin-shim WS (which does require a token).
 */
internal fun LettaConfig.isIrohBackend(): Boolean =
    serverUrl.trimStart()
        .removePrefix("https://")
        .removePrefix("http://")
        .startsWith("iroh://")
