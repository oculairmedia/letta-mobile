package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets

/** The real turns captured for letta-mobile-bglj6.1.15, read for the turn identity ledger tests. */
internal object RealTurnLedgerFixtures {
    val MODELS = listOf(
        "minimax-m3", "claude-sonnet-5-5",
        "minimax-m3-fallback-from-qwen3.8-max", "minimax-m3-fallback-from-kat-coder",
        "openrouter-deepseek-v4.1-flash", "openrouter-gemini-3.8-flash", "openrouter-glm-5.3-flash",
        "openrouter-gpt-6.1-sol", "openrouter-grok-4.7", "openrouter-qwen3.8-flash",
    )

    /** The viewer wire's `stream_delta` envelopes, in arrival order. */
    fun wireFrames(model: String, turn: Int): List<JsonObject> =
        resource("$model/turn$turn.wire-viewer.jsonl").lines().filter { it.isNotBlank() }
            .map { AppServerProtocol.json.parseToJsonElement(it).jsonObject.getValue("wire").jsonObject }

    /** `message.list` rows after the turn settled, newest first as captured. */
    fun listedRows(model: String, turn: Int): List<JsonObject> =
        AppServerProtocol.json.parseToJsonElement(resource("$model/turn$turn.message-list.json")).jsonArray
            .map { it.jsonObject }

    /** The turn exactly as the host settles it: every wire frame stamped, then collected. */
    fun settledTurn(model: String, turn: Int): SettledTurn {
        var minted = 0
        val clientMessageId = wireFrames(model, turn).firstNotNullOf { frame ->
            frame["delta"]?.jsonObject?.get("otid")?.jsonPrimitive?.contentOrNull
        }
        val identity = TurnStreamIdentity(clientMessageId) { "lm-$model-$turn-${++minted}" }
        val collector = SettledTurnCollector(clientMessageId)
        wireFrames(model, turn).forEach { frame ->
            identity.stamp(frame.toString(), StreamTextFrameSource.CumulativeSnapshot)?.let(collector::observe)
        }
        return checkNotNull(collector.settled())
    }

    private fun resource(path: String): String {
        val stream = checkNotNull(javaClass.getResourceAsStream("/timeline/identity/real-turns/$path")) { "missing $path" }
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
