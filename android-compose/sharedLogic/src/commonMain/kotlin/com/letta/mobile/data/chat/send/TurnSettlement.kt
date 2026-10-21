package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.IdFormats
import com.letta.mobile.data.runtime.TurnFailureNotice
import com.letta.mobile.data.runtime.terminalReasonKind
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.transport.BridgeTurnStatus
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/** Ordered terminal effects, invoked only while the coordinator retains its lifecycle mutex.
 * Optional reconciliation stays cancellable. Mandatory local retirement attempts finish under
 * NonCancellable; the caller observes failures before queue release. This class owns no scope/lock.
 * Disconnect and visual completion retain their distinct policies rather than impersonating TurnDone.
 */
internal class TurnSettlement(
    private val agent: AgentId,
    private val timelineRepository: TimelineExternalTransportWriter,
    private val runtimeEventBatcher: RuntimeEventBatcher,
    private val ownership: TurnSettlementOwnership,
    private val foreground: ConversationChatUi,
) {
    private val agentId: String get() = agent.value
    private val ui: ChatSendUiSink get() = foreground.sink

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
        conversation: ConversationId,
        tail: TurnTail,
        notice: TurnFailureNotice,
    ) {
        val conversationId = conversation.value
        val runId = tail.runId.orEmpty()
        val turnId = tail.turnId.orEmpty()
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

    suspend fun cleanupAbandonedAssistantFragmentsSafely(
        conversation: ConversationId,
        tail: TurnTail,
        reason: String,
        candidateRunIds: Set<String> = emptySet(),
    ) {
        val conversationId = conversation.value
        val runId = tail.runId
        val turnId = tail.turnId
        runCatching {
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
        }.onFailure { error ->
            if (error is CancellationException) throw error
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
     * The turn answered for this send, so its otid is settled: fence it against a replayed echo
     * rebinding a finished turn, and record on the timeline how it ended.
     */
    private suspend fun settlePendingSend(
        state: ConversationTurnState,
        conversation: ConversationId,
        deadTurn: Boolean,
    ) {
        val conversationId = conversation.value
        val otid = state.otid ?: return
        ownership.retainSettledOtid(QueuedSendId(otid))
        val localConversationId = state.localConversationId ?: conversationId
        if (deadTurn) {
            timelineRepository.markExternalTransportLocalFailed(agentId, localConversationId, otid)
        } else {
            timelineRepository.markExternalTransportLocalSent(agentId, localConversationId, otid)
        }
    }

    suspend fun finish(
        state: ConversationTurnState,
        request: TurnSettlementRequest,
        cancelledNotice: TurnFailureNotice?,
    ): List<Result<Unit>> {
        val event = request.event
        val conversationId = state.ownerConversationId.takeIf { it.isNotBlank() } ?: defaultConversationId
        val outcome = TurnTerminalPolicy.decide(TurnTerminalEvidence.from(state), request, cancelledNotice)
        val terminalWrites = runCatching { writeTerminal(state, request, outcome, ConversationId(conversationId)) }
        val stopReason = state.stopReason
        val retirementWrites = retireTerminalTurn(state, ConversationId(conversationId), request, outcome)
        Telemetry.event(
            "AdminChatVM", "ws.turnComplete",
            "conversationId" to conversationId,
            "status" to event.status,
            "turnId" to event.turnId,
            "runId" to event.runId,
            "stopReason" to (stopReason ?: ""),
            "lossy" to event.lossy,
            "reason" to request.reason,
        )
        return listOf(terminalWrites) + retirementWrites
    }

    private suspend fun writeTerminal(
        state: ConversationTurnState,
        request: TurnSettlementRequest,
        outcome: TurnTerminalOutcome,
        conversation: ConversationId,
    ) {
        val tail = TurnTail(request.event.turnId, request.event.runId)
        if (outcome.cleanupFragments) {
            cleanupAbandonedAssistantFragmentsSafely(
                conversation, tail, "turn_done_${request.event.status}", activeCleanupCandidateRunIds(state, tail),
            )
        }
        if (request.recordEvent) {
            runtimeEventBatcher.enqueue(request.event, conversation.value)
        }
        if (request.event.lossy) reconcileLossySend(state, request.event, conversation)
        if (request.event.status is BridgeTurnStatus.Failed && outcome.notice == null) {
            Telemetry.event(
                "AdminChatVM", "ws.turnDone.failedAfterDelivery",
                "turnId" to request.event.turnId,
                "runId" to request.event.runId,
                // Sanitized family only — never the raw reason (letta-mobile-o0atv).
                "reasonKind" to (state.bufferedErrorKind ?: terminalReasonKind(state.bufferedErrorMessage) ?: "<none>"),
            )
        }
        outcome.notice?.let { notice ->
            appendTurnFailureNotice(conversation, tail, notice)
        }
    }

    private suspend fun reconcileLossySend(
        state: ConversationTurnState,
        event: WsTimelineEvent.TurnDone,
        conversation: ConversationId,
    ) {
        Telemetry.event(
            "AdminChatVM", "ws.turnDone.lossy",
            "dropCount" to event.dropCount,
            "turnId" to event.turnId,
            "runId" to event.runId,
        )
        state.otid?.let { otid ->
            timelineRepository.reconcileExternalTransportSend(
                conversationId = conversation.value,
                agentId = agentId,
                externalConversationId = state.serverConversationId ?: conversation.value,
                otid = otid,
            )
        }
    }

    /**
     * Called under the lifecycle mutex. Attempt every local retirement write before propagating
     * cancellation; reconciliation and queue dispatch remain outside this NonCancellable boundary.
     */
    private suspend fun retireTerminalTurn(
        state: ConversationTurnState,
        conversationId: ConversationId,
        request: TurnSettlementRequest,
        outcome: TurnTerminalOutcome,
    ): List<Result<Unit>> {
        val status = request.event.status
        return withContext(NonCancellable) {
            val localSettlement = runCatching { settlePendingSend(state, conversationId, outcome.deadTurn) }
            val foregroundPresence = runCatching {
                // Evaluate after suspension: the user may have selected a different conversation.
                foreground.turnFinished(conversationId) {
                    TurnTerminalPolicy.nextError(TurnTerminalEvidence.from(state), status, outcome.notice, ui.currentError())
                }
            }
            // Unconditional: an earlier turn's dangling card may need its cancelled sweep restored.
            val toolSweep = runCatching {
                timelineRepository.turnEnded(agentId, conversationId.value, clean = status is BridgeTurnStatus.Completed)
            }
            state.reachedTerminal = true
            ownership.clear(state, reason = "turnFinished")
            val transportPresence = runCatching { timelineRepository.clearExternalTransportActive(conversationId.value) }
            listOf(localSettlement, foregroundPresence, toolSweep, transportPresence)
        }
    }

    suspend fun failConversationForDisconnect(
        state: ConversationTurnState,
        event: WsTimelineEvent.Disconnected,
    ) {
        val conversationId = state.conversationId.takeIf { it.isNotBlank() } ?: foreground.visibleConversation?.value
        failOptimisticLocalForDisconnect(state, conversationId?.let(::ConversationId), event)
        if (conversationId != null) {
            cleanupDisconnectedTurnFragments(state, ConversationId(conversationId))
            endConversationAbnormally(ConversationId(conversationId))
        }
        state.reachedTerminal = true
        ownership.clear(state, reason = "disconnect")
    }

    private suspend fun failOptimisticLocalForDisconnect(
        state: ConversationTurnState,
        conversation: ConversationId?,
        event: WsTimelineEvent.Disconnected,
    ) {
        val otid = state.otid ?: return
        val localConversationId = state.localConversationId ?: conversation?.value
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

    private suspend fun cleanupDisconnectedTurnFragments(state: ConversationTurnState, conversation: ConversationId) {
        if (listOf(state.otid, state.runId, state.turnId).all { it == null }) return
        val tail = TurnTail(state.turnId, state.runId)
        cleanupAbandonedAssistantFragmentsSafely(conversation, tail, "disconnect", activeCleanupCandidateRunIds(state, tail))
    }

    /**
     * An abnormal end (not a clean completion) — this
     * turn's OWN calls are already settled synchronously by AppServerTurnEngine on
     * disconnect/cancel/error paths, so they won't show up as unresolved here.
     * Still call turnEnded unconditionally: a
     * DIFFERENT, earlier turn's dangling card may have had its sweep cancelled by
     * this turn's turnStarted and needs this call to reschedule it, or it would
     * spin forever.
     */
    suspend fun endConversationAbnormally(conversation: ConversationId) {
        val conversationId = conversation.value
        timelineRepository.clearExternalTransportActive(conversationId)
        runCatching { timelineRepository.turnEnded(agentId, conversationId, clean = false) }
    }

    suspend fun markTurnVisuallyComplete(state: ConversationTurnState, reason: String) {
        val conversationId = state.ownerConversationId.takeIf { it.isNotBlank() }
            ?: foreground.visibleConversation?.value
            ?: defaultConversationId
        state.otid?.let { otid ->
            timelineRepository.markExternalTransportLocalSent(agentId, state.localConversationId ?: conversationId, otid)
        }
        timelineRepository.clearExternalTransportActive(agentId, conversationId)
        foreground.visuallyComplete(ConversationId(conversationId))
        Telemetry.event(
            "AdminChatVM", "ws.turnVisuallyComplete",
            "conversationId" to conversationId,
            "reason" to reason,
            "turnId" to (state.turnId ?: ""),
        )
    }

    /** Heal only after the facade establishes that the transport no longer owns this turn.
     * Settle accepted optimistic content as sent before discarding its identity, preserving retry safety.
     */
    suspend fun healStalePresence(state: ConversationTurnState?, conversation: ConversationId?) {
        ui.onTurnVisuallyComplete()
        val orphanedOtid = state?.otid
        if (state != null && conversation != null) {
            settlePendingSend(state, conversation, deadTurn = false)
            timelineRepository.clearExternalTransportActive(agentId, conversation.value)
            ownership.clear(state, reason = "stalePresenceHealed")
        }
        Telemetry.event(
            "IrohTrace", "coordinator.send.stalePresenceHealed",
            "conversationId" to conversation?.value,
            "settledOtid" to (orphanedOtid != null),
        )
    }

    /** A delayed terminal may clean its old run only; it never retires the live owner. */
    suspend fun staleTerminal(owner: ConversationTurnState?, event: WsTimelineEvent.TurnDone) {
        if (event.status is BridgeTurnStatus.Failed || event.status is BridgeTurnStatus.Cancelled) {
            val conversationId = ownership.staleConversation(owner)?.value ?: defaultConversationId
            cleanupAbandonedAssistantFragmentsSafely(
                conversation = ConversationId(conversationId),
                tail = TurnTail(event.turnId, event.runId),
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
    }

    private val defaultConversationId: String get() = IdFormats.defaultConversationIdFor(agent.value)
}

private fun activeCleanupCandidateRunIds(
    state: ConversationTurnState,
    primary: TurnTail,
): Set<String> = buildSet {
    primary.runId?.takeIf { it.isNotBlank() }?.let(::add)
    state.runId?.takeIf { it.isNotBlank() }?.let(::add)
    state.activeAssistantMessageRunIds.mapNotNullTo(this) { it.takeIf(String::isNotBlank) }
}
