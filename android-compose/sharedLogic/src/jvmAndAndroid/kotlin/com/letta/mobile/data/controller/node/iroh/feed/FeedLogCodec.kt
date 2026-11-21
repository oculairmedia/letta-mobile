package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Writes a journal file's lines: one JSON object each, a header, then entries and flags in the order they happened. An
 * entry that supersedes an earlier written one (`u`) is how a cumulative text snapshot replaces its predecessor without
 * rewriting the file. [FeedLogReader] reads them back.
 */
internal object FeedLogCodec {
    /** What an entry costs beyond its payload text: the envelope fields of its line. */
    const val ENTRY_OVERHEAD_BYTES: Int = 96

    const val VERSION = 1L
    const val VERSION_FIELD = "v"
    const val BASE_FIELD = "base"
    const val HEAD_FIELD = "head"
    const val AGENT_FIELD = "agent_id"
    const val CONVERSATION_FIELD = "conversation_id"
    const val EPOCH_FIELD = "e"
    const val SEQ_FIELD = "s"
    const val KIND_FIELD = "k"
    const val TURN_FIELD = "t"
    const val PAYLOAD_FIELD = "p"
    const val POINTER_FIELD = "x"
    const val SUPERSEDES_FIELD = "u"
    const val FLAG_FIELD = "f"
    const val FLAG_MISSED = "missed"
    const val FLAG_DELIVERED = "delivered"
    const val TERMINAL_OBSERVED_FIELD = "terminal_observed"
    const val HOST_RESTARTED_FIELD = "host_restarted"

    fun header(key: JournalKey, baseSeq: FeedSeq, headSeq: FeedSeq): String = buildJsonObject {
        put(VERSION_FIELD, VERSION)
        put(AGENT_FIELD, key.agentId.value)
        put(CONVERSATION_FIELD, key.conversationId.value)
        put(BASE_FIELD, baseSeq.value)
        put(HEAD_FIELD, headSeq.value)
    }.toString()

    fun entry(entry: FeedEntry): String = buildJsonObject {
        put(EPOCH_FIELD, entry.epoch.value)
        put(SEQ_FIELD, entry.seq.value)
        put(KIND_FIELD, entry.kind)
        put(TURN_FIELD, entry.turn.value)
        entry.payload?.let { put(PAYLOAD_FIELD, it) }
        entry.pointer?.let { put(POINTER_FIELD, it) }
        entry.supersedes?.let { put(SUPERSEDES_FIELD, it.value) }
    }.toString()

    fun missedFlag(turn: JournalTurn): String = flag(FLAG_MISSED, turn)

    fun deliveredFlag(turn: JournalTurn): String = flag(FLAG_DELIVERED, turn)

    private fun flag(name: String, turn: JournalTurn): String = buildJsonObject {
        put(FLAG_FIELD, name)
        put(TURN_FIELD, turn.value)
    }.toString()

    /** The payload of a `turn_closed` entry: whether a real terminal went through the journal. */
    fun closedPayload(terminalObserved: Boolean, hostRestarted: Boolean): JsonObject = buildJsonObject {
        put(TERMINAL_OBSERVED_FIELD, terminalObserved)
        if (hostRestarted) put(HOST_RESTARTED_FIELD, true)
    }

    /** What a rewritten file holds: the header, the live entries, then the flags of the turns that still have entries. */
    fun snapshot(log: FeedLog): String = buildString {
        append(header(log.key, log.baseSeq, log.headSeq)).append('\n')
        log.entries.forEach { entry ->
            entry.supersedes = null
            append(entry(entry)).append('\n')
        }
        val kept = log.entries.mapTo(HashSet()) { it.turn }
        log.turns.filterKeys { it in kept }.forEach { (turn, state) ->
            if (state.initiatorMissed) append(missedFlag(turn)).append('\n')
            if (state.delivered) append(deliveredFlag(turn)).append('\n')
        }
    }

    /**
     * The journal a file holds and the newest seq it issued: its header's head, raised by the last entry written after the
     * header (the header is only rewritten on compaction). Null when the file does not decode.
     */
    fun headOf(text: String): Pair<JournalKey, FeedSeq>? {
        val key = keyOf(text) ?: return null
        val header = FeedLineParser.parseObject(text.substringBefore('\n')) ?: return null
        val declared = FeedSeq(with(FeedLineParser) { header.number(HEAD_FIELD) } ?: 0)
        val last = with(FeedLineParser) {
            text.lineSequence().mapNotNull { parseObject(it) }.mapNotNull { line -> line.number(SEQ_FIELD) }.lastOrNull()
        }
        return key to maxOf(declared, FeedSeq(last ?: 0))
    }

    /** The key a journal file declares in its header; null when it does not decode. */
    fun keyOf(text: String): JournalKey? {
        val header = FeedLineParser.parseObject(text.substringBefore('\n')) ?: return null
        val agent = (header[AGENT_FIELD] as? JsonPrimitive)?.contentOrNull ?: return null
        val conversation = (header[CONVERSATION_FIELD] as? JsonPrimitive)?.contentOrNull ?: return null
        return JournalKey(AgentId(agent), ConversationId(conversation))
    }
}
