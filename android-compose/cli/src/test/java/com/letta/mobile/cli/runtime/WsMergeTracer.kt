package com.letta.mobile.cli.runtime

import com.letta.mobile.bot.protocol.BotStreamChunk

/**
 * Mirror of `TimelineSyncLoop`'s merge logic specifically for the
 * **lettabot WS path** (Client Mode).
 *
 * Key facts about how chunks arrive on this surface:
 *
 *  - Each `WsStreamEventMessage` becomes a `BotStreamChunk` with a `text`
 *    field, an `event` discriminator (assistant / tool_call / tool_result
 *    / reasoning), and a `uuid` that should identify the *target message*
 *    being assembled.
 *  - Multiple chunks with the **same uuid** belong to the same logical
 *    message and should be merged.
 *  - The bug we're hunting (`letta-mobile-6p4o`): mid-stream chunks render
 *    "garbled" briefly before being replaced by the final response. The
 *    suspect is the merge heuristic in `TimelineSyncLoop` (1132-1138) when
 *    incoming `text` is sometimes cumulative ("Hello", "Hello world") and
 *    sometimes a delta ("Hello", " world"), and the heuristic guesses
 *    wrong.
 *
 * For each chunk we determine the merge BRANCH:
 *   - INIT       — first chunk for this uuid
 *   - EQUAL      — incoming text matches stored exactly (no-op)
 *   - CUMULATIVE — incoming startsWith(stored) → REPLACE stored with incoming
 *   - PREFIX-OF  — stored startsWith(incoming) → STALE, ignore (incoming is older)
 *   - APPEND     — incoming length <= stored, neither prefix → APPEND (suspect!)
 *   - GARBLE-RISK — incoming and stored disagree on a shared prefix
 */
class WsMergeTracer {
    private data class State(
        var text: String = "",
        var frames: Int = 0,
        var event: String? = null,
    )

    private val byUuid = linkedMapOf<String, State>()
    private var frameIdx = 0

    fun onChunk(chunk: BotStreamChunk) {
        frameIdx++

        if (chunk.done) {
            println("[$frameIdx] DONE  conv=${chunk.conversationId} agent=${chunk.agentId} aborted=${chunk.aborted} req=${chunk.requestId}")
            return
        }

        val uuid = chunk.uuid ?: "<no-uuid>"
        val incomingRaw = chunk.text ?: ""
        val state = byUuid.getOrPut(uuid) { State(event = chunk.event?.name) }
        val before = state.text

        val branch: String
        val out: String
        when {
            state.frames == 0 -> {
                branch = "INIT"
                out = incomingRaw
            }
            incomingRaw == before -> {
                branch = "EQUAL"
                out = before
            }
            incomingRaw.startsWith(before) -> {
                branch = "CUMULATIVE"
                out = incomingRaw
            }
            before.startsWith(incomingRaw) -> {
                branch = "PREFIX-OF (STALE)"
                out = before
            }
            else -> {
                // Neither side is a prefix of the other. If they
                // share *any* prefix the suspect-merge happens — same
                // as `TimelineSyncLoop` lines 1132-1138, which append
                // a delta even when streams disagree.
                val shared = sharedPrefixLen(before, incomingRaw)
                if (shared > 0 && shared < incomingRaw.length && shared < before.length) {
                    branch = "GARBLE-RISK (shared=$shared)"
                    // What the heuristic would do: append incoming
                    // verbatim → garble.
                    out = before + incomingRaw
                } else {
                    branch = "APPEND"
                    out = before + incomingRaw
                }
            }
        }

        state.text = out
        state.frames++

        println("[$frameIdx] event=${chunk.event?.name ?: "?"}  uuid=${uuid.take(8)}  branch=$branch  len(in)=${incomingRaw.length}  len(out)=${out.length}  req=${chunk.requestId?.take(8)}")
        if (branch != "EQUAL") {
            println("    IN:  ${quote(incomingRaw)}")
            if (branch.startsWith("GARBLE")) {
                println("    OLD: ${quote(before)}")
                println("    OUT: ${quote(out)}     ⚠️  shared prefix mismatch — visible garbled text expected here")
            }
        }
        if (chunk.toolName != null) {
            println("    tool=${chunk.toolName}  call=${chunk.toolCallId}  input=${chunk.toolInput?.toString()?.take(120)}")
        }
        if (chunk.isError) {
            println("    ERROR chunk")
        }
    }

    fun printSummary() {
        println()
        println("=== WS STREAM SUMMARY ===")
        println("frames received: $frameIdx")
        println("distinct uuids:  ${byUuid.size}")
        byUuid.forEach { (uuid, state) ->
            println()
            println("--- uuid=$uuid event=${state.event} frames=${state.frames} chars=${state.text.length} ---")
            println(state.text)
        }
        println("=== END SUMMARY ===")
    }

    private fun sharedPrefixLen(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\u0000", "\\0") + "\""
}
