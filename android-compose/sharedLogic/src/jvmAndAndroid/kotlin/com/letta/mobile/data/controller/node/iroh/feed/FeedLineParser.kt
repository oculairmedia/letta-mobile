package com.letta.mobile.data.controller.node.iroh.feed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** What one line of a journal file is: a decoded line, or why it cannot be one. Nothing here throws. */
internal sealed interface FeedLine {
    /** A line that sets the state of [turn] (`missed` or `delivered`). */
    data class Flag(val turn: JournalTurn, val flag: String) : FeedLine

    /** A journal entry; [supersedes] is the seq of the written entry it replaces. */
    data class Entry(val entry: FeedEntry, val supersedes: FeedSeq?) : FeedLine

    /** A line that is not a valid line of the format. */
    data class Invalid(val reason: String) : FeedLine
}

/** Turns journal-file lines into [FeedLine]s and checks each entry's shape. */
internal object FeedLineParser {
    /** The JSON object [line] holds, or null when it is not one. */
    fun parseObject(line: String): JsonObject? = try {
        Json.parseToJsonElement(line).jsonObject
    } catch (expected: IllegalArgumentException) {
        // SerializationException and a non-object line (IllegalArgumentException) are both an undecodable line.
        null
    }

    fun parse(line: String): FeedLine {
        val fields = parseObject(line) ?: return FeedLine.Invalid("undecodable line")
        val turn = fields.text(FeedLogCodec.TURN_FIELD)?.let(::JournalTurn) ?: return FeedLine.Invalid("line without a turn")
        val flag = fields.text(FeedLogCodec.FLAG_FIELD)
        return if (flag != null) FeedLine.Flag(turn, flag) else entry(fields, turn)
    }

    private fun entry(fields: JsonObject, turn: JournalTurn): FeedLine {
        val payload = fields[FeedLogCodec.PAYLOAD_FIELD] as? JsonObject
        val pointer = fields[FeedLogCodec.POINTER_FIELD] as? JsonObject
        val body = EntryBody.of(payload, pointer) ?: return FeedLine.Invalid("entry needs exactly one of payload and pointer")
        val position = position(fields) ?: return FeedLine.Invalid("entry without epoch, seq or kind")
        val draft = EntryDraft(turn, position.kind, body)
        val entry = FeedEntry(position.epoch, position.seq, draft, draft.encodedSize())
        return FeedLine.Entry(entry, fields[FeedLogCodec.SUPERSEDES_FIELD].long()?.let(::FeedSeq))
    }

    /** The three fields every entry line must carry, or null when one is missing. */
    private fun position(fields: JsonObject): Position? {
        val epoch = fields[FeedLogCodec.EPOCH_FIELD].long()?.let(::FeedEpoch) ?: return null
        val seq = fields[FeedLogCodec.SEQ_FIELD].long()?.let(::FeedSeq) ?: return null
        val kind = fields.text(FeedLogCodec.KIND_FIELD) ?: return null
        return Position(epoch, seq, kind)
    }

    /** The header's number [name], or null. */
    fun JsonObject.number(name: String): Long? = this[name].long()

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
}

/** Where an entry line sits: its host start, its seq and its kind. */
private class Position(val epoch: FeedEpoch, val seq: FeedSeq, val kind: String)
