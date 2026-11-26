package com.letta.mobile.data.feed.client

import com.letta.mobile.data.chat.projection.CanvasArtifactReceipts
import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.buildChatRenderModel
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedDelta
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.runtime.admitsParentStreamDelta
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMutation
import com.letta.mobile.data.timeline.TimelineReducerState
import com.letta.mobile.data.timeline.applyPendingToolReturns
import com.letta.mobile.data.timeline.keyedByOwnRecords
import com.letta.mobile.data.timeline.reduceLive
import com.letta.mobile.data.timeline.reduceProductionMutation
import com.letta.mobile.data.timeline.toTimelinePersistentList
import com.letta.mobile.data.timeline.toTimelinePersistentMap
import com.letta.mobile.data.timeline.unresolvedToolCallIds
import com.letta.mobile.data.timeline.withPromptOwnedRunIds
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.transport.iroh.RuntimeEventServerFrameMapper
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Turns the entries of one conversation's feed into the rows the chat list draws, with the reducer and the projection the
 * live overlay already uses (so a row has the id, the run and the key it has everywhere else): a stamped delta names its row by
 * `logical_message_id`, and a later cumulative snapshot of the same row replaces it where it stands. Not thread-safe.
 */
internal class FeedRowProjector(private val agent: AgentId, conversation: ConversationId) {
    private val empty = TimelineReducerState(Timeline(conversationId = conversation.value))
    private var reduction = empty
    private val conversationId = conversation.value

    /**
     * The parent tool calls each still-open turn emitted, oldest turn first. A turn's entry is dropped when its
     * `turn_closed` arrives; turns of one conversation can overlap (the host counts running turns), so a turn whose
     * close never arrives is forgotten once [MAX_OPEN_TURNS] newer turns are open.
     */
    private val toolCallsByTurn = LinkedHashMap<String, OpenTurnCalls>()

    /** Approvals left pending by a non-terminal close, oldest first, at most [MAX_CARRIED_APPROVALS]. */
    private val carriedApprovals = LinkedHashSet<String>()

    /** Turns tracked as open (their `turn_closed` not yet seen). */
    val openTurnCount: Int get() = toolCallsByTurn.size

    /** Entries that were pointers (too large to carry): they name a row that is not loaded, and draw nothing yet. */
    var pointerEntries: Int = 0
        private set

    fun reset() {
        reduction = empty
        pointerEntries = 0
        toolCallsByTurn.clear()
        carriedApprovals.clear()
    }

    /** Folds [entry] into the rows and returns the messages it carried (for a consumer that ingests them itself). */
    fun apply(entry: FeedDelta): List<LettaMessage> {
        if (entry.kind == ConversationFeedWire.KIND_TURN_CLOSED) settleClosedTurn(entry.turn, entry.payload.endedTheTurn())
        if (entry.kind != ConversationFeedWire.KIND_STREAM_DELTA) return emptyList()
        val payload = entry.payload
        if (payload == null) {
            pointerEntries += 1
            return emptyList()
        }
        if (!admitsParentStreamDelta(payload, AppServerRuntimeScope(agent.value, conversationId))) return emptyList()
        return messagesOf(entry, payload).also { messages ->
            messages.forEach { message ->
                reduction = reduction.reduceLive(message, agent.value)
                rememberToolCalls(entry.turn, message)
            }
        }
    }

    /**
     * TLA+ NoPendingRow: the host journals `turn_closed` only after every frame it relayed for
     * the turn (NodeTurnHost.run's finally), so a call of that turn still unanswered there gets no result in this
     * turn, whether the turn ended normally or not. A conversation read through the feed runs no ledger sweep, so
     * the shared projection settles each such call visibly, as the ledger's dangling sweep would ("No tool result
     * recorded", an error). A real result journaled later (an async tool answering in a later turn) still replaces it.
     *
     * An approval request is the exception when [turnEnded] is false: a `turn_closed` with `terminal_observed: false`
     * only says the host stopped watching (it restarted or lost the turn), while the App Server may still be holding the
     * approval for the person. Settling it would hide its approve/deny controls (the UI offers them only while a call has
     * no result). So it stays pending and answerable, and is carried over: the next terminal close in this conversation
     * settles it if it is still unanswered then (the App Server answered it or dropped it by the time a turn ends).
     */
    private fun settleClosedTurn(turn: String, turnEnded: Boolean) {
        val open = toolCallsByTurn.remove(turn) ?: OpenTurnCalls()
        val calls = open.all()
        attachHeldReturns(calls)
        val unresolved = reduction.timeline.unresolvedToolCallIds()
        val settleable = if (turnEnded) calls + carriedApprovals else open.plain
        if (turnEnded) carriedApprovals.clear() else carriedApprovals += open.approvals.intersect(unresolved)
        while (carriedApprovals.size > MAX_CARRIED_APPROVALS) carriedApprovals.remove(carriedApprovals.first())
        val unanswered = unresolved.intersect(settleable)
        if (unanswered.isEmpty()) return
        val settle = TimelineMutation.SettleDanglingToolCalls(
            reduction.danglingSweepGeneration,
            reduction.lifecycleEpoch,
            unanswered,
        )
        reduction = reduceProductionMutation(reduction, settle).next
        Telemetry.event(TAG, "turnClosed.settledUnanswered", "turn" attr turn, "count" attr unanswered.size)
    }

    /**
     * A row is named by its first call (`tc-<first>`), so the return of a later call in the same message cannot join it
     * live and is held. The turn is over: show each held return on its call, with its real status.
     */
    private fun attachHeldReturns(calls: Set<String>) {
        if (reduction.pendingToolReturnsByCallId.keys.none { it in calls }) return
        val held = LinkedHashMap(reduction.pendingToolReturnsByCallId)
        val events = reduction.timeline.events.map<TimelineEvent, TimelineEvent> { event ->
            if (event is TimelineEvent.Confirmed && event.toolCalls.any { it.effectiveId in calls }) {
                applyPendingToolReturns(event, held)
            } else {
                event
            }
        }
        reduction = reduction.copy(
            timeline = reduction.timeline.copy(
                events = events.toTimelinePersistentList(),
                stablePrefixVersion = reduction.timeline.stablePrefixVersion + 1,
            ),
            pendingToolReturnsByCallId = held.toTimelinePersistentMap(),
        )
    }

    private fun rememberToolCalls(turn: String, message: LettaMessage) {
        val calls = when (message) {
            is ToolCallMessage -> message.effectiveToolCalls
            is ApprovalRequestMessage -> message.effectiveToolCalls
            else -> return
        }.map { it.effectiveId }.filter { it.isNotBlank() }
        if (calls.isEmpty()) return
        val open = toolCallsByTurn.getOrPut(turn) { OpenTurnCalls() }
        if (message is ApprovalRequestMessage) open.approvals += calls else open.plain += calls
        while (toolCallsByTurn.size > MAX_OPEN_TURNS) toolCallsByTurn.remove(toolCallsByTurn.keys.first())
    }

    /** Whether a `turn_closed` payload says the turn really ended (a terminal went through), not that the host stopped watching. */
    private fun JsonObject?.endedTheTurn(): Boolean =
        (this?.get(TERMINAL_OBSERVED) as? JsonPrimitive)?.booleanOrNull ?: true

    /** One open turn's calls; approvals are kept apart because only a terminal close may settle them. */
    private class OpenTurnCalls {
        val plain = mutableSetOf<String>()
        val approvals = mutableSetOf<String>()

        fun all(): Set<String> = plain + approvals
    }

    private fun messagesOf(entry: FeedDelta, payload: JsonObject): List<LettaMessage> = RuntimeEventServerFrameMapper.map(
        payload = RuntimeEventPayload.RemoteStreamFrame(
            frameId = "feed-${entry.epoch}-${entry.seq}",
            messageId = payload.text("id"),
            messageType = payload.text("message_type"),
            body = payload.toString(),
        ),
        context = RuntimeEventServerFrameMapper.Context(
            agentId = agent.value, conversationId = conversationId, turnId = entry.turn, runId = "feed-run-${entry.turn}",
        ),
    ).mapNotNull(WsFrameMapper::toLettaMessage)

    /** The rows now, newest first (what the chat list takes). */
    fun items(): List<ChatRenderItem> {
        val events = reduction.timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
        val receipts = CanvasArtifactReceipts.attach(events)
        val messages = events.mapNotNull { event ->
            timelineEventToUiMessage(event, agent.value, receipts[CanvasArtifactReceipts.eventKey(event)].orEmpty())
        }.withPromptOwnedRunIds()
        return buildChatRenderModel(messages, ChatDisplayMode.Interactive, agent.value).renderItems.map { it.keyedByOwnRecords() }
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val TAG = "FeedRows"
        private const val TERMINAL_OBSERVED = "terminal_observed"

        /** Most approvals carried over non-terminal closes; the oldest is forgotten past it. */
        private const val MAX_CARRIED_APPROVALS = 64

        /** Most open turns tracked for close settlement; the oldest is forgotten past it. */
        const val MAX_OPEN_TURNS = 16
    }
}

/** How one publication of rows differs from the one before it. */
data class FeedRowDiff(
    /** The rows to publish: an unchanged row is the very instance the list already held, so only a changed row redraws. */
    val rows: List<ChatRenderItem>,
    /** Rows that kept their key and changed. */
    val changed: Int,
    val added: Int,
    val removed: Int,
    /** Rows both lists hold appear in another order. */
    val reordered: Boolean,
) {
    /** The list is not the old list with rows changed, added at an end or removed: it was replaced. */
    val replacement: Boolean get() = reordered || removed > 0

    /** Rows that draw: every changed and every added one. */
    val redrawn: Int get() = changed + added
}

/** Keeps a row's identity across publications, so the list below it redraws only what changed. */
object FeedRowIdentity {
    /** [next] with each row [previous] already holds, equal, replaced by that instance. */
    fun reconcile(previous: List<ChatRenderItem>, next: List<ChatRenderItem>): FeedRowDiff {
        val before = previous.associateBy { it.key }
        val kept = next.filter { it.key in before }
        val keptKeys = kept.mapTo(HashSet()) { it.key }
        val rows = next.map { row -> before[row.key]?.takeIf { it == row } ?: row }
        return FeedRowDiff(
            rows = rows,
            changed = kept.count { before.getValue(it.key) != it },
            added = next.size - kept.size,
            removed = previous.size - kept.size,
            reordered = previous.map { it.key }.filter { it in keptKeys } != kept.map { it.key },
        )
    }
}
