package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.controller.node.iroh.TurnJournalWriter
import com.letta.mobile.data.feed.wire.ConversationFeedWire
import com.letta.mobile.data.feed.wire.FeedCursor
import com.letta.mobile.data.feed.wire.FeedPage
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The agents and conversations the journal tests use, as typed ids. */
internal object Ids {
    val agent = AgentId("agent-a")
    val otherAgent = AgentId("agent-b")
    val conversation = ConversationId("conv-1")
}

/** A conversation as the tests name it: the owning agent and the conversation id. */
internal data class Conv(val agent: AgentId, val conversation: ConversationId) {
    val key: JournalKey get() = JournalKey(agent, conversation)

    companion object {
        val default = Conv(Ids.agent, Ids.conversation)

        fun of(conversation: String, agent: AgentId = Ids.agent) = Conv(agent, ConversationId(conversation))
    }
}

/** A turn of a test conversation, named by its client message id. */
@JvmInline
internal value class TurnName(val value: String)

/** The id of a tool call in a test conversation. */
@JvmInline
internal value class CallId(val value: String)

/** The text a delta carries: an assistant message so far, or a tool's output. */
@JvmInline
internal value class Text(val value: String)

/** One delta a test feeds the journal; [json] is exactly what the host's fanout would hand it. */
internal class Delta(val json: String) {
    companion object {
        fun toolCall(id: CallId) = of("tool_call_message") { put("tool_call_id", id.value) }

        fun toolReturn(id: CallId, output: Text = Text("ok")) = of("tool_return_message") {
            put("tool_call_id", id.value)
            put("tool_return", output.value)
        }

        /** A cumulative snapshot of the one assistant message the tests stream, holding [text] so far. */
        fun assistant(text: Text) = of("assistant_message") {
            put("logical_message_id", "lm-1")
            put("content", text.value)
        }

        /** A real end of turn. */
        fun stopReason() = of("stop_reason") { put("stop_reason", "end_turn") }

        private fun of(messageType: String, fields: JsonObjectBuilder.() -> Unit) = Delta(
            buildJsonObject {
                put("message_type", messageType)
                fields()
            }.toString(),
        )
    }
}

/** How a test turn ends: a real terminal went through the journal, or none did. */
internal enum class Ending(val terminalObserved: Boolean) { Terminal(true), NoTerminal(false) }

/** Builds a whole turn: its deltas, how it ends and whether its initiator lost the tail. */
internal class TurnScript(val name: TurnName, val deltas: List<Delta>, val ending: Ending = Ending.NoTerminal)

internal fun FeedJournalStore.writerFor(conv: Conv, turn: TurnName): TurnJournalWriter = openTurn(conv.agent, conv.conversation, turn.value)

internal fun FeedJournalStore.knowsTurn(conv: Conv, turn: TurnName) = knowsTurn(conv.agent, conv.conversation, turn.value)

internal fun FeedJournalStore.replayFor(conv: Conv, turn: TurnName) = replayForInitiator(conv.agent, conv.conversation, turn.value)

/** Writes [script] through the journal's writer for [conv]: its deltas, then the close. Returns the writer (for flags set afterwards). */
internal fun FeedJournalStore.write(conv: Conv, script: TurnScript): TurnJournalWriter =
    writerFor(conv, script.name).also { writer ->
        script.deltas.forEach { writer.append(it.json) }
        writer.close(script.ending.terminalObserved)
    }

/** The page of [conv] for a viewer at [from]. */
internal fun FeedJournalStore.pageOf(conv: Conv, from: FeedCursor?, limit: Int = ConversationFeedWire.DEFAULT_LIMIT): FeedPage =
    page(conv.key, from, limit)

/** Records [delta] as the turn's next event. */
internal fun TurnJournalWriter.append(delta: Delta) = append(delta.json)

/** Plays [deltas] through an already open [writer], then closes it with [ending]. */
internal fun TurnJournalWriter.play(deltas: List<Delta>, ending: Ending = Ending.NoTerminal) {
    deltas.forEach { append(it.json) }
    close(ending.terminalObserved)
}

internal fun FeedPage.seqs(): List<Long> = entries.map { it.seq }

internal fun FeedPage.kinds(): List<String> = entries.map { it.kind }

internal fun FeedPage.payloadTypes(): List<String?> = entries.map { entry -> entry.payload?.text("message_type") }

internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

/** Every page of [conv] from [from] until the host says there is no more, in order. */
internal fun FeedJournalStore.drainOf(conv: Conv, from: FeedCursor?, limit: Int = ConversationFeedWire.MAX_LIMIT): List<FeedPage> =
    generateSequence(page(conv.key, from, limit)) { previous ->
        if (previous.hasMore) page(conv.key, previous.cursor, limit) else null
    }.toList()
