package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.api.TimelineIngestSources
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Incoming turn projection under the coordinator's lifecycle mutex.
 * The registry resolves ownership once; turn state records evidence and this owner writes the timeline.
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
        val binding = turnRegistry.bindDelta(event)
        binding?.rekeyedConversationId?.let(foreground::started)
        val resolution = turnRegistry.resolveDelta(event, binding?.state, foreground.visibleConversation)
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
        val resolvedFrame = turnRegistry.resolveConversationId(event.conversationId?.let(::ConversationId))
        resolution.state?.recordAssistantEvidence(event, resolvedFrame)
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
        if (state.advanceStartedIdentity(event)) return null
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
        state.recordStarted(event)
        // letta-mobile-dangling-tool: a fresh turn on this conversation
        // supersedes whatever the previous turn's post-turn dangling-
        // tool-call sweep left pending.
        runCatching {
            timelineRepository.turnStarted(agentId, effectiveConversationId, event.runId, event.turnId)
        }
        runtimeEventBatcher.enqueue(event, effectiveConversationId)
        return state
    }

    suspend fun drainBuffered(conversation: ConversationId) {
        val conversationId = conversation.value
        val state = turnRegistry.peekState(conversation)
        while (true) {
            val delta = preConversationMessageDeltas.removeFirstOrNull() ?: return
            runtimeEventBatcher.enqueue(delta, conversationId)
            val resolvedFrame = turnRegistry.resolveConversationId(delta.conversationId?.let(::ConversationId))
            state?.recordAssistantEvidence(delta, resolvedFrame)
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


}

private const val CURSOR_EXPIRED_ERROR_CODE = "cursor_expired"
