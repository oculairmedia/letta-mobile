package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry

/** Incoming identity and delivery transitions on the existing conversation state, under the lifecycle mutex. */
/** Advance identity first; same-turn run promotion preserves the existing delivery evidence. */
internal fun ConversationTurnState.advanceStartedIdentity(event: WsTimelineEvent.TurnStarted): Boolean {
    val transition = identity.turnStarted(ownerConversationId, event.turnId, event.runId)
    if (transition !is TurnIdentityTransition.SameTurn || event.turnId != turnId) return false
    Telemetry.event(
        "AdminChatVM", "ws.turnStarted.runPromoted",
        "conversationId" to ownerConversationId,
        "turnId" to event.turnId,
        "previousRunId" to (runId ?: ""),
        "runId" to event.runId,
    )
    runId = event.runId
    return true
}

/** New-turn fields change after the projection has scheduled settlement of the prior local row. */
internal fun ConversationTurnState.recordStarted(event: WsTimelineEvent.TurnStarted) {
    turnId = event.turnId
    runId = event.runId
    reachedTerminal = false
    resetDeliveryEvidence()
}

/** Replayed content never widens this turn's delivery or cleanup evidence. */
internal fun ConversationTurnState.recordAssistantEvidence(
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

private fun ConversationTurnState.countsAsActiveTurnDelivery(
    message: AssistantMessage,
    event: WsTimelineEvent.MessageDelta,
    resolvedFrame: ConversationId?,
): Boolean {
    if (event.isReplay || turnId == null) return false
    if (message.content.isBlank()) return false
    if (!frameConversationMatchesActive(event, resolvedFrame)) return false
    return runMatchesActiveTurn(message)
}

private fun ConversationTurnState.frameConversationMatchesActive(
    event: WsTimelineEvent.MessageDelta,
    resolvedFrame: ConversationId?,
): Boolean {
    val tagged = event.conversationId ?: return true
    val target = ownerConversationId
    return tagged == target || tagged == serverConversationId || resolvedFrame?.value == target
}

/** letta-mobile-zrode: an error for a turn or run this conversation already settled is that turn's
 * history; buffering it would fail whichever turn comes next. */
internal fun ConversationTurnState.acceptsError(event: WsTimelineEvent.Error): Boolean {
    val tail = TurnTail(event.turnId, event.runId)
    if (isRetiredTurn(tail) || isRetiredRun(tail)) return false
    return event.turnId == null || turnId == null || event.turnId == turnId
}

internal fun ConversationTurnState.bufferError(event: WsTimelineEvent.Error) {
    bufferedErrorMessage = event.message.ifBlank { event.code }
    bufferedErrorKind = event.code.takeIf { TurnFailureNotices.isKnownKind(it) }
}

private const val MAX_ACTIVE_ASSISTANT_RUN_IDS = 8

/**
 * True when [tail] names a turn this conversation already settled. Such a frame is history: only
 * the TurnDone stale path still wants it; stop/usage/error frames must not mutate a cleared entry.
 */
internal fun ConversationTurnState.isRetiredTurn(tail: TurnTail): Boolean {
    val turn = tail.turnId?.takeIf { it.isNotBlank() } ?: return false
    return turnId != turn && identity.isFenced(turn)
}

/** A retired tail cannot relight live presence or passively adopt live identity. A frame without a
 * turn id still names its run; a settled run that is not the live one is a finished turn's tail. */
internal fun ConversationTurnState.isRetiredDelta(event: WsTimelineEvent.MessageDelta): Boolean =
    if (!event.turnId.isNullOrBlank()) isRetiredTurn(TurnTail(event.turnId, null))
    else isRetiredRun(TurnTail(null, event.message.runId))
