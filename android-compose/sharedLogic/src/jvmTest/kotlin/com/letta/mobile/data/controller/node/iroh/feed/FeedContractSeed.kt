package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Builds the journal a `feed.subscribe` fixture case describes (`host_state.feed.turns`) by writing each turn through the
 * journal's own writer, as the host does, on a fixed clock so the epoch is [EPOCH].
 */
internal object FeedContractSeed {
    const val EPOCH = 1000L

    /** The journal of [hostState] (null or without a feed: empty), kept in [directory], or in memory when null. */
    fun journal(hostState: JsonObject?, directory: Path? = null): FeedJournalStore {
        val store = FeedJournalStore(directory, FeedRetention()) { EPOCH }
        hostState?.get("feed")?.jsonObject?.get("turns")?.jsonArray?.forEach { write(store, it.jsonObject) }
        return store
    }

    private fun write(store: FeedJournalStore, turn: JsonObject) {
        val conv = Conv(AgentId(text(turn, "agent_id")), ConversationId(text(turn, "conversation_id")))
        val ending = if (turn.getValue("terminal_observed").jsonPrimitive.boolean) Ending.Terminal else Ending.NoTerminal
        val deltas = turn.getValue("deltas").jsonArray.map { Delta(delta(it.jsonObject).toString()) }
        val writer = store.write(conv, TurnScript(TurnName(text(turn, "turn")), deltas, ending))
        if (turn.getValue("initiator_missed").jsonPrimitive.boolean) writer.noteInitiatorMissedTail()
    }

    private fun text(turn: JsonObject, name: String): String = turn.getValue(name).jsonPrimitive.content

    /** A `synthetic_tool_return_bytes` delta stands for a tool return of that many bytes, so the fixture file stays small. */
    private fun delta(spec: JsonObject): JsonObject {
        val size = (spec["synthetic_tool_return_bytes"] as? JsonPrimitive)?.int ?: return spec
        return buildJsonObject {
            put("message_type", "tool_return_message")
            put("tool_call_id", spec.getValue("tool_call_id").jsonPrimitive.content)
            put("tool_return", "z".repeat(size))
        }
    }
}
