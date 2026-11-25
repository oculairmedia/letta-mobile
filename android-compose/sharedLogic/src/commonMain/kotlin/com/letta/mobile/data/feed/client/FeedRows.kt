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
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.runtime.admitsParentStreamDelta
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineReducerState
import com.letta.mobile.data.timeline.keyedByOwnRecords
import com.letta.mobile.data.timeline.reduceLive
import com.letta.mobile.data.timeline.withPromptOwnedRunIds
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.transport.iroh.RuntimeEventServerFrameMapper
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /** Entries that were pointers (too large to carry): they name a row that is not loaded, and draw nothing yet. */
    var pointerEntries: Int = 0
        private set

    fun reset() {
        reduction = empty
        pointerEntries = 0
    }

    /** Folds [entry] into the rows and returns the messages it carried (for a consumer that ingests them itself). */
    fun apply(entry: FeedDelta): List<LettaMessage> {
        if (entry.kind != ConversationFeedWire.KIND_STREAM_DELTA) return emptyList()
        val payload = entry.payload
        if (payload == null) {
            pointerEntries += 1
            return emptyList()
        }
        if (!admitsParentStreamDelta(payload, AppServerRuntimeScope(agent.value, conversationId))) return emptyList()
        return messagesOf(entry, payload).also { messages ->
            messages.forEach { reduction = reduction.reduceLive(it, agent.value) }
        }
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
