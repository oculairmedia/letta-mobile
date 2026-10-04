package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.transport.appserver.decodeAppServerMessageList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * One real captured turn (letta-mobile-bglj6.1.15): the raw wire lines a viewer received, the stored
 * rows the turn settled into, and the stored rows of the turn before it (the conversation's history).
 */
internal data class RealTurn(
    val name: String,
    val wire: List<String>,
    val history: List<LettaMessage>,
    val settled: List<LettaMessage>,
) {
    val scope: TimelineScope get() = ObserverFrameReplay.scopeOf(wire.first { it.isNotBlank() }) ?: error("$name names no conversation")
}

internal object RealTurnCaptures {
    private const val ROOT = "/timeline/identity/real-turns"

    /** [model]'s turn 1, and with [bothTurns] turn 2 as well, replayed over turn 1's stored rows. */
    fun model(model: String, bothTurns: Boolean = false): List<RealTurn> {
        val dir = File(File(RealTurnCaptures::class.java.getResource(ROOT)?.toURI() ?: error("missing $ROOT")), model)
        val first = turn(dir, 1, history = emptyList())
        return if (bothTurns) listOf(first, turn(dir, 2, history = first.settled)) else listOf(first)
    }

    private fun turn(dir: File, number: Int, history: List<LettaMessage>) = RealTurn(
        name = "${dir.name}/turn$number",
        wire = sampled(File(dir, "turn$number.wire-viewer.jsonl").readLines().filter(String::isNotBlank)),
        history = history,
        settled = decodeAppServerMessageList(Json.parseToJsonElement(File(dir, "turn$number.message-list.json").readText())).distinctBy { it.id },
    )

    /**
     * Keeps every non-text frame, the last snapshot of each message and one text snapshot in
     * [STRIDE]: a replay costs real time per frame, and a cumulative message that grows by a few
     * characters per frame shows the same violations at any stride.
     */
    private fun sampled(lines: List<String>): List<String> {
        val keys = lines.map(::textKey)
        return lines.filterIndexed { index, _ ->
            keys[index] == null || keys[index] != keys.getOrNull(index + 1) || index % STRIDE == 0
        }
    }

    private fun textKey(line: String): String? {
        val delta = Json.parseToJsonElement(ObserverFrameReplay.wireOf(line)).jsonObject["delta"] as? JsonObject ?: return null
        val type = delta["message_type"]?.jsonPrimitive?.contentOrNull
        return if (type == "assistant_message" || type == "reasoning_message") "$type/${delta["id"]?.jsonPrimitive?.contentOrNull}" else null
    }

    private const val STRIDE = 8
}
