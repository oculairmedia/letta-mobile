package com.letta.mobile.cli.runtime

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.timeline.mergeStreamText

/**
 * Uses the production stream text merge helper so we can observe, frame by
 * frame, exactly what the timeline state would look like in the app.
 *
 * The whole point of this class is to be a transparent observer over the
 * production merge helper, not a second copy of that logic.
 *
 * Output shape per frame:
 *
 *     [seq=N type=assistant_message id=...] +42 chars  branch=APPEND
 *     OLD: "The logcat ring"
 *     NEW: "The logcat ring buffer is filling"
 *
 * Branches:
 *   - INIT          first frame for this serverId
 *   - EQUAL         exact dup; no change
 *   - CUMULATIVE    incoming starts with existing; replace (server sent full snapshot)
 *   - STALE         existing starts with incoming; skip (older delta)
 *   - SUFFIX_DUPLICATE existing ends with incoming; skip duplicate suffix
 *   - APPEND        unrelated; concat (delta on top of accumulated text)
 *   - GARBLE-RISK   non-empty old AND non-empty new AND new is shorter than old AND
 *                   neither prefix matches — this is the suspicious case where
 *                   we'd append a fragment that may not belong on the end. Flagged
 *                   loudly.
 */
class MergeTracer(private val verbose: Boolean = true) {

    private val byServerId = LinkedHashMap<String, AssemblyState>()
    private var frameCount = 0

    fun onFrame(frame: LettaMessage) {
        frameCount++
        val type = frame.messageType
        val serverId = frame.id

        // Extract content. Different message types carry their text in
        // different fields; we only really care about the streaming
        // merge for assistant_message and reasoning_message — those are
        // the chatty ones.
        val (incomingText, summary) = when (frame) {
            is AssistantMessage -> frame.content to "assistant"
            is ReasoningMessage -> (frame.reasoning ?: "") to "reasoning"
            is ToolCallMessage -> describeToolCall(frame) to "tool_call"
            is ToolReturnMessage -> (runCatching { frame.toolReturn.funcResponse ?: "" }.getOrDefault("")) to "tool_return"
            else -> "" to type
        }

        val prior = byServerId[serverId]
        val priorText = prior?.text.orEmpty()

        val branch: String
        val mergedText: String
        if (prior == null) {
            branch = "INIT"
            mergedText = incomingText
        } else {
            val merge = mergeStreamText(
                existing = priorText,
                incoming = incomingText,
                canUseSnapshotMerge = prior.seqId != null && frame.seqId != null,
            )
            branch = if (merge.garbleRisk) "GARBLE-RISK" else merge.branch.name
            mergedText = merge.text
        }
        byServerId[serverId] = AssemblyState(
            text = mergedText,
            messageType = type,
            summary = summary,
            frames = (prior?.frames ?: 0) + 1,
            seqId = frame.seqId,
        )

        if (!verbose) {
            println("[$frameCount] type=$type id=${serverId.take(12)} len=${incomingText.length} content=${incomingText.take(120).debug()}")
            return
        }

        val flag = if (branch == "GARBLE-RISK") " ⚠️" else ""
        println("[$frameCount] $summary  serverId=${serverId.take(12)}  branch=$branch$flag  +${incomingText.length}")
        println("    NEW: ${incomingText.take(120).debug()}")
        if (branch != "INIT") {
            println("    OLD: ${priorText.take(120).debug()}  (was ${priorText.length} chars)")
            println("    OUT: ${mergedText.take(140).debug()}  (now ${mergedText.length} chars)")
        }
    }

    fun printSummary() {
        println()
        println("=== STREAM SUMMARY ===")
        println("frames received: $frameCount")
        println("distinct messages: ${byServerId.size}")
        for ((id, state) in byServerId) {
            println()
            println("--- ${state.summary} (id=$id, ${state.frames} frame(s), ${state.text.length} chars) ---")
            println(state.text)
        }
        println()
        println("=== END SUMMARY ===")
    }

    private fun describeToolCall(t: ToolCallMessage): String {
        val name = t.toolCall?.name ?: "?"
        val args = t.toolCall?.arguments?.take(60) ?: ""
        return "$name($args)"
    }

    private data class AssemblyState(
        val text: String,
        val messageType: String?,
        val summary: String,
        val frames: Int,
        val seqId: Int?,
    )
}

// Render whitespace and control chars visibly so the user can SEE if the
// wire is sending junk like raw newlines mid-frame.
private fun String.debug(): String = buildString {
    append('"')
    for (c in this@debug) {
        append(when (c) {
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            '"' -> "\\\""
            else -> c.toString()
        })
    }
    append('"')
}
