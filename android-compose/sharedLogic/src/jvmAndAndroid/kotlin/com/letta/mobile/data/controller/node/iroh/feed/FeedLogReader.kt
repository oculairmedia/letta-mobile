package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.controller.node.iroh.feed.FeedLineParser.number
import com.letta.mobile.data.feed.wire.ConversationFeedWire
import java.nio.file.Path
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** A journal file that does not decode; the store logs it and starts the conversation over. */
internal class FeedLogCorrupt(message: String) : Exception(message)

/**
 * Rebuilds a [FeedLog] from the text of its file (written by [FeedLogCodec]). A last line with no newline that does not
 * decode is a write the process died in and is dropped; any other line that does not decode, or a seq that does not
 * increase, is corruption.
 */
internal object FeedLogReader {
    fun read(text: String, key: JournalKey, file: Path?): FeedLog {
        val lines = text.split('\n')
        val torn = lines.last().takeIf { it.isNotBlank() }
        val complete = lines.dropLast(1)
        val header = readHeader(complete.firstOrNull() ?: torn.orEmpty())
        val log = FeedLog(key, file)
        log.baseSeq = FeedSeq(header.number(FeedLogCodec.BASE_FIELD) ?: 0)
        log.headSeq = log.baseSeq
        replay(log, complete, torn)
        log.headSeq = maxOf(log.headSeq, FeedSeq(header.number(FeedLogCodec.HEAD_FIELD) ?: 0))
        log.fileBytes = ByteCount(text.toByteArray(Charsets.UTF_8).size.toLong())
        return log
    }

    /** The journal's first line, decoded and checked to be a version this reader understands. */
    private fun readHeader(line: String) =
        (FeedLineParser.parseObject(line) ?: corrupt("empty or unreadable header")).also { header ->
            if (header.number(FeedLogCodec.VERSION_FIELD) != FeedLogCodec.VERSION) corrupt("unknown journal version")
        }

    /** Applies every complete line after the header, then the torn last line if there is one. */
    private fun replay(log: FeedLog, complete: List<String>, torn: String?) {
        // A header with no newline after it is itself a torn write: cut it off before anything is appended to it.
        if (complete.isEmpty()) {
            if (torn != null) log.tornTail = true
            return
        }
        complete.drop(1).forEach { line -> apply(log, FeedLineParser.parse(line)) }
        torn?.let { line -> readTornTail(log, line) }
    }

    private fun readTornTail(log: FeedLog, line: String) {
        log.tornTail = true
        val parsed = FeedLineParser.parse(line)
        if (parsed !is FeedLine.Invalid) apply(log, parsed)
    }

    private fun apply(log: FeedLog, line: FeedLine) {
        when (line) {
            is FeedLine.Invalid -> corrupt(line.reason)
            is FeedLine.Flag -> applyFlag(log.turns.getOrPut(line.turn) { FeedTurn() }, line.flag)
            is FeedLine.Entry -> applyEntry(log, line)
        }
    }

    private fun applyEntry(log: FeedLog, line: FeedLine.Entry) {
        val entry = line.entry
        if (entry.seq <= log.headSeq) corrupt("seq does not increase")
        if (line.supersedes != null && log.tail()?.seq == line.supersedes) log.replaceTail(entry) else log.add(entry)
        entry.persisted = true
        log.headSeq = entry.seq
        applyKind(log.turns.getOrPut(entry.turn) { FeedTurn() }, entry)
    }

    private fun applyFlag(state: FeedTurn, flag: String) {
        when (flag) {
            FeedLogCodec.FLAG_MISSED -> state.initiatorMissed = true
            FeedLogCodec.FLAG_DELIVERED -> state.delivered = true
            else -> corrupt("unknown flag")
        }
    }

    /** Any entry of a turn proves it was opened, even when retention has since dropped its `turn_open`. */
    private fun applyKind(state: FeedTurn, entry: FeedEntry) {
        state.opened = true
        when (entry.kind) {
            ConversationFeedWire.KIND_TURN_CLOSED -> {
                state.closed = true
                state.terminalObserved = (entry.payload?.get(FeedLogCodec.TERMINAL_OBSERVED_FIELD) as? JsonPrimitive)?.booleanOrNull == true
            }
        }
    }

    private fun corrupt(reason: String): Nothing = throw FeedLogCorrupt(reason)
}
