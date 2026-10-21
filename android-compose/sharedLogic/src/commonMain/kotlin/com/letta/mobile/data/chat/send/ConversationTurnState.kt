package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.timeline.IROH_SYNTHETIC_RUN_ID_PREFIXES
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry
import kotlin.concurrent.Volatile

/**
 * All per-turn state for ONE conversation. Mutations are serialized by
 * the coordinator mutex (every send / transport-event / redial entry point takes
 * it); the fields are volatile so a read from another dispatcher thread
 * still observes the published value.
 */
internal class ConversationTurnState(val conversationId: String) {
    /** Terminal fence + generation ownership for THIS conversation only. */
    val identity = TurnIdentityLifecycle()
    @Volatile var otid: String? = null

    /** The send behind [otid], kept so a busy bounce can wait and retry it. */
    @Volatile var inFlightSend: QueuedChatSend? = null
    @Volatile var localConversationId: String? = null
    @Volatile var serverConversationId: String? = null

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

    // Set when a non-replay assistant message is authoritatively scoped to this
    // active conversation/run. This is delivery evidence, not completion evidence.
    @Volatile var deliveredAssistantContent: Boolean = false

    /** Set on the terminal path so a clear can tell "settled" from "evicted". */
    @Volatile var reachedTerminal: Boolean = false

    /**
     * Run ids this conversation has already settled. The run-keyed analogue of
     * [TurnIdentityLifecycle]'s turn fence: it must SURVIVE a state clear, or a
     * delayed run-keyed terminal looks like it belongs to whatever send is
     * waiting now (PR2 review, finding 2). Bounded like the assistant run set.
     */
    val settledRunIds = linkedSetOf<String>()

    /** Publish accepted-send fields under the same identity lock as the transport decision.
     * The caller holds the coordinator lifecycle mutex; [onAccepted] may publish its volatile
     * last-active pointer but must not acquire the registry index lock (index -> identity order).
     */
    fun acceptSend(pending: QueuedChatSend, send: () -> Boolean, onAccepted: () -> Unit): Boolean =
        identity.acceptSend(pending.conversationId.value, send) {
            otid = pending.id.value
            inFlightSend = pending
            localConversationId = pending.conversationId.value.takeIf { it.isNotBlank() }
            reachedTerminal = false
            onAccepted()
        }

    fun retainSettledRun() {
        val key = runId?.takeIf { it.isNotBlank() } ?: return
        settledRunIds += key
        while (settledRunIds.size > MAX_SETTLED_RUN_IDS) {
            settledRunIds.remove(settledRunIds.first())
        }
    }

    /** Advance identity first; same-turn run promotion preserves the existing delivery evidence. */
    fun advanceStartedIdentity(event: WsTimelineEvent.TurnStarted): Boolean {
        val transition = identity.turnStarted(localConversationId ?: conversationId, event.turnId, event.runId)
        if (transition !is TurnIdentityTransition.SameTurn || event.turnId != turnId) return false
        Telemetry.event(
            "AdminChatVM", "ws.turnStarted.runPromoted",
            "conversationId" to (localConversationId ?: conversationId),
            "turnId" to event.turnId,
            "previousRunId" to (runId ?: ""),
            "runId" to event.runId,
        )
        runId = event.runId
        return true
    }

    /** New-turn fields change after the projection has scheduled settlement of the prior local row. */
    fun recordStarted(event: WsTimelineEvent.TurnStarted) {
        turnId = event.turnId
        runId = event.runId
        reachedTerminal = false
        resetDeliveryEvidence()
    }

    /** Historical/replayed content never widens this turn's delivery or cleanup evidence. */
    fun recordAssistantEvidence(
        event: WsTimelineEvent.MessageDelta,
        resolvedFrame: ConversationId?,
    ) {
        val message = event.message
        if (message !is AssistantMessage) return
        if (countsAsActiveTurnDelivery(message, event, resolvedFrame)) {
            deliveredAssistantContent = true
        }
        val messageRunId = message.runId?.takeIf { it.isNotBlank() }
        if (messageRunId == null) return
        // A replayed frame describes a run that already finished. This set
        // feeds activeCleanupCandidateRunIds, so latching a historical run id
        // onto the CURRENT turn hands abandoned-fragment cleanup a licence to
        // sweep rows belonging to older completed runs the next time this turn
        // reaches turn_done or the connection drops. The delivery check also
        // rejects replay; neither record may widen this turn's cleanup scope.
        if (event.isReplay) return
        activeAssistantMessageRunIds += messageRunId
        while (activeAssistantMessageRunIds.size > MAX_ACTIVE_ASSISTANT_RUN_IDS) {
            activeAssistantMessageRunIds.remove(activeAssistantMessageRunIds.first())
        }
    }

    private fun countsAsActiveTurnDelivery(
        message: AssistantMessage,
        event: WsTimelineEvent.MessageDelta,
        resolvedFrame: ConversationId?,
    ): Boolean {
        if (event.isReplay) return false
        if (turnId == null) return false
        if (message.content.isBlank()) return false
        if (!frameConversationMatchesActive(event, resolvedFrame)) return false
        return runMatchesActiveTurn(message)
    }

    private fun frameConversationMatchesActive(
        event: WsTimelineEvent.MessageDelta,
        resolvedFrame: ConversationId?,
    ): Boolean {
        val tagged = event.conversationId ?: return true
        val target = localConversationId ?: conversationId
        return tagged == target || tagged == serverConversationId || resolvedFrame?.value == target
    }

    fun acceptsError(event: WsTimelineEvent.Error): Boolean =
        event.turnId == null || turnId == null || event.turnId == turnId

    fun bufferError(event: WsTimelineEvent.Error) {
        bufferedErrorMessage = event.message.ifBlank { event.code }
        bufferedErrorKind = event.code.takeIf { TurnFailureNotices.isKnownKind(it) }
    }

    /** True while this conversation owns a send or a turn that has not settled. */
    val isTracking: Boolean
        get() = identity.active != null || otid != null || turnId != null || runId != null

    fun adoptPassiveDelta(event: WsTimelineEvent.MessageDelta): Boolean {
        if (otid != null) return false
        val observedTurn = event.turnId?.takeIf { it.isNotBlank() }
        val observedRun = event.message.runId?.takeIf { it.isNotBlank() }
        val startsNewTurn = !isTracking || reachedTerminal || changesTurn(observedTurn)
        if (startsNewTurn) {
            observedTurn?.let { bindObservedTurn(it, observedRun.orEmpty()) }
            observedRun?.let { runId = it }
            reachedTerminal = false
            resetDeliveryEvidence()
        } else {
            adoptMissingIdentity(observedTurn, observedRun)
        }
        return true
    }

    private fun changesTurn(observed: String?): Boolean {
        if (observed == null || turnId == null) return false
        return turnId != observed
    }

    private fun bindObservedTurn(turn: String, run: String) {
        identity.turnStarted(localConversationId ?: conversationId, turn, run)
        turnId = turn
    }

    private fun adoptMissingIdentity(turn: String?, run: String?) {
        if (turn != null && turnId == null) bindObservedTurn(turn, run ?: runId.orEmpty())
        if (run == null || run == runId) return
        runId = run
        turnId?.let { bindObservedTurn(it, run) }
    }

    fun resetDeliveryEvidence() {
        activeAssistantMessageRunIds.clear()
        stopReason = null
        usageRecorded = false
        bufferedErrorMessage = null
        bufferedErrorKind = null
        deliveredAssistantContent = false
    }

    fun runMatchesActiveTurn(message: LettaMessage): Boolean {
        val messageRun = message.runId?.takeIf { it.isNotBlank() } ?: return true
        val activeRun = runId ?: return true
        if (IROH_SYNTHETIC_RUN_ID_PREFIXES.any { activeRun.startsWith(it) }) return true
        return messageRun == activeRun
    }

    fun absorbOrphan(orphan: ConversationTurnState, incoming: TurnTail) {
        adoptOrphanIdentity(orphan, incoming)
        stopReason = stopReason ?: orphan.stopReason
        usageRecorded = usageRecorded || orphan.usageRecorded
        absorbBufferedError(orphan)
        deliveredAssistantContent = deliveredAssistantContent || orphan.deliveredAssistantContent
        activeAssistantMessageRunIds.addAll(orphan.activeAssistantMessageRunIds)
        val adoptedTurn = turnId
        val adoptedRun = runId
        if (adoptedTurn != null && adoptedRun != null) bindObservedTurn(adoptedTurn, adoptedRun)
    }

    private fun absorbBufferedError(orphan: ConversationTurnState) {
        bufferedErrorMessage = bufferedErrorMessage ?: orphan.bufferedErrorMessage
        bufferedErrorKind = bufferedErrorKind ?: orphan.bufferedErrorKind
    }

    private fun adoptOrphanIdentity(orphan: ConversationTurnState, incoming: TurnTail) {
        if (turnId == null) turnId = orphan.turnId ?: incoming.turnId
        if (runId == null) runId = orphan.runId ?: incoming.runId
    }

    fun isRetiredRun(tail: TurnTail): Boolean {
        return tail.runId != runId && tail.runId in settledRunIds
    }

    fun canAdoptTerminal(event: WsTimelineEvent.TurnDone): Boolean {
        if (event.runId.isBlank()) return false
        return turnId == null && runId == event.runId
    }

    fun recordTerminalIdentity(event: WsTimelineEvent.TurnDone) {
        if (event.turnId.isNotBlank() && turnId == null) {
            bindObservedTurn(event.turnId, event.runId)
        }
        if (event.runId.isNotBlank() && runId == null) {
            runId = event.runId
        }
    }
}

private const val MAX_SETTLED_RUN_IDS = 8
private const val MAX_ACTIVE_ASSISTANT_RUN_IDS = 8

internal fun shouldAdoptPassiveDelta(
    event: WsTimelineEvent.MessageDelta,
    boundState: ConversationTurnState?,
    retiredTail: Boolean,
): Boolean {
    if (retiredTail || event.isReplay) return false
    if (boundState != null) return false
    return !event.turnId.isNullOrBlank() || !event.message.runId.isNullOrBlank()
}
