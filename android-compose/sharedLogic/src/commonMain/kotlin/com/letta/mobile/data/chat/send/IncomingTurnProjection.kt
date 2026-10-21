package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.api.TimelineIngestSources
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Incoming turn projection under the coordinator's lifecycle mutex.
 * The registry resolves ownership once; this owner records delivery evidence and timeline writes.
 * Buffered legacy deltas and the existing stale-local launch retain the caller's lifecycle/scope.
 * No independent collector, mutex, index or scope is created here.
 */
internal class IncomingTurnProjection(
    private val agent: AgentId,
    private val timelineRepository: TimelineExternalTransportWriter,
    private val turnRegistry: ConversationTurnRegistry,
    private val runtimeEventBatcher: RuntimeEventBatcher,
    private val foreground: ConversationChatUi,
) {
    private val agentId: String get() = agent.value
    private val preConversationMessageDeltas = ArrayDeque<WsTimelineEvent.MessageDelta>()

    suspend fun messageDelta(event: WsTimelineEvent.MessageDelta): ConversationId? {
        val otid = event.message.otid
        val boundState = if (otid != null && !event.isReplay) {
            val binding = turnRegistry.bindInboundTurnByOtid(
                QueuedSendId(otid),
                event.conversationId?.let(::ConversationId),
                TurnTail(event.turnId, event.message.runId),
            )
            binding.rekeyedConversationId?.let(foreground::started)
            binding.state
        } else {
            null
        }
        val resolution = turnRegistry.resolveDelta(event, boundState, foreground.visibleConversation)
        Telemetry.event(
            "IrohGate", "gate3.coordinatorMessageDelta",
            "resolvedConversationId" to resolution?.conversation?.value,
            "messageId" to event.message.id,
            "messageType" to event.message.messageType,
            "isReplay" to event.isReplay,
        )
        if (resolution == null) {
            preConversationMessageDeltas.addLast(event)
            return null
        }
        val conversationId = resolution.conversation.value
        runtimeEventBatcher.enqueue(event, conversationId)
        val retiredTail = resolution.retired
        rememberActiveAssistantMessageRunId(resolution.state, event)
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
            return null
        }
        return resolution.conversation.takeUnless { event.isReplay }
    }

    suspend fun started(event: WsTimelineEvent.TurnStarted, scope: CoroutineScope): ConversationTurnState? {
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
            return null
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
        return state
    }

    /** Only non-replay, nonblank assistant content from this conversation's live run is delivery.
     * A missing/synthetic run retains the existing active-turn fallback; historical runs never
     * widen the set that terminal cleanup may touch.
     */
    private fun countsAsActiveTurnDelivery(
        state: ConversationTurnState,
        message: AssistantMessage,
        event: WsTimelineEvent.MessageDelta,
    ): Boolean {
        if (event.isReplay) return false
        if (state.turnId == null) return false
        if (message.content.isBlank()) return false
        if (!frameConversationMatchesActive(state, event.conversationId?.let(::ConversationId))) return false
        return state.runMatchesActiveTurn(message)
    }

    private fun frameConversationMatchesActive(
        state: ConversationTurnState,
        frameConversationId: ConversationId?,
    ): Boolean {
        val tagged = frameConversationId?.value ?: return true
        val targetConv = state.localConversationId ?: state.conversationId
        return tagged == targetConv ||
            tagged == state.serverConversationId ||
            turnRegistry.resolveConversationId(ConversationId(tagged))?.value == targetConv
    }

    private fun rememberActiveAssistantMessageRunId(
        state: ConversationTurnState?,
        event: WsTimelineEvent.MessageDelta,
    ) {
        if (state == null) return
        val message = event.message
        if (message !is AssistantMessage) return
        if (countsAsActiveTurnDelivery(state, message, event)) {
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
        if (event.isReplay) return
        state.activeAssistantMessageRunIds += messageRunId
        while (state.activeAssistantMessageRunIds.size > MAX_ACTIVE_ASSISTANT_RUN_IDS) {
            state.activeAssistantMessageRunIds.remove(state.activeAssistantMessageRunIds.first())
        }
    }

    suspend fun drainBuffered(conversation: ConversationId) {
        val conversationId = conversation.value
        val state = turnRegistry.peekState(conversation)
        while (true) {
            val delta = preConversationMessageDeltas.removeFirstOrNull() ?: return
            runtimeEventBatcher.enqueue(delta, conversationId)
            rememberActiveAssistantMessageRunId(state, delta)
            timelineRepository.ingestExternalTransportMessage(
                agentId, conversationId, delta.message, source = "coordinator.preConversationDrain",
            )
        }
    }

    suspend fun error(event: WsTimelineEvent.Error): Boolean {
        if (event.code == CURSOR_EXPIRED_ERROR_CODE && repairExpiredCursor(event)) return true
        // An Error frame belongs to the conversation it names. Buffering it into
        // THAT conversation's state (instead of a single global) is what stops a
        // foreign failure from poisoning another conversation's turn.
        val state = turnRegistry.resolveError(event, foreground.visibleConversation) ?: return false
        if (!state.acceptsError(event)) return true
        runtimeEventBatcher.enqueue(event, state.conversationId)
        // lcp-axv: stash the error and wait for the immediately-following TurnDone
        // to flip the UI. Surfacing the error here would race with TurnDone and
        // could leave isStreaming / isAgentTyping stuck if TurnDone is delayed.
        state.bufferError(event)
        Telemetry.event(
            "AdminChatVM", "ws.error.buffered",
            "conversationId" to state.conversationId,
            "code" to event.code,
            "message" to (event.message),
            "turnId" to (event.turnId ?: ""),
            "runId" to (event.runId ?: ""),
        )
        return true
    }

    /** The cursor belongs to its named conversation, even while its repair suspends. */
    private suspend fun repairExpiredCursor(event: WsTimelineEvent.Error): Boolean {
        val conversation = event.conversationId?.let(::ConversationId)
            ?: turnRegistry.lastActiveConversationId?.let(::ConversationId)
            ?: foreground.visibleConversation
            ?: return false
        val repaired = runCatching {
            timelineRepository.repairExpiredConversationCursorScoped(
                agentId = agentId,
                conversationId = conversation.value,
                fallbackSeq = event.lastSeq,
                expectedWatermark = event.afterSeq,
            )
        }
        reportCursorRepair(conversation, event, repaired)
        return true
    }

    private fun reportCursorRepair(
        conversation: ConversationId,
        event: WsTimelineEvent.Error,
        repaired: Result<Unit>,
    ) {
        repaired.onSuccess {
            Telemetry.event(
                "AdminChatVM", "ws.cursorExpired.repaired",
                "conversationId" to conversation.value,
                "afterSeq" to (event.afterSeq ?: -1L),
                "oldestSeq" to (event.oldestSeq ?: -1L),
                "lastSeq" to (event.lastSeq ?: -1L),
            )
            foreground.error(conversation, null)
        }.onFailure { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            Telemetry.error(
                "AdminChatVM", "ws.cursorExpired.repairFailed", failure,
                "conversationId" to conversation.value,
            )
            foreground.error(conversation, "Timeline repair failed: ${failure.message ?: "unknown"}")
        }
    }

    fun clearBuffered() = preConversationMessageDeltas.clear()

    private companion object {
        const val CURSOR_EXPIRED_ERROR_CODE = "cursor_expired"
        const val MAX_ACTIVE_ASSISTANT_RUN_IDS = 8
    }
}
