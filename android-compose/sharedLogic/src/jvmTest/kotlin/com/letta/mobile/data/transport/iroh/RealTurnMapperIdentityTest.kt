package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.runtime.StreamTextFrameSource
import com.letta.mobile.data.runtime.TurnStreamIdentity
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.toTimelineEvent
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-ys9it: replays the REAL captured turns (letta-mobile-bglj6.1.15, 20 turns across 12 model
 * routes) through the host stamper and then the client mapper, and checks the identity the timeline gets.
 * The context an observer would use (one fixed turn and run id per conversation) is passed to every
 * frame: none of it may leak into a row.
 */
class RealTurnMapperIdentityTest {

    @Test
    fun oneLogicalIdPerAssistantMessageIncludingTwoMessagesSharingAnOtid() {
        forEachTurn { model, wire ->
            val wireIds = wire.deltas("assistant_message").map { it.str("id") }.toSet()
            val rows = mapTurn(wire).filterIsInstance<ServerFrame.AssistantMessage>()
            assertEquals(wireIds.size, rows.map { it.id }.toSet().size, model)
            assertTrue(rows.all { it.id == it.logicalMessageId }, model)
        }
        val grok = mapTurn(wireOf("openrouter-grok-4.7", 1)).filterIsInstance<ServerFrame.AssistantMessage>()
        assertTrue(grok.map { it.id }.toSet().size >= 2, "grok writes two assistant messages in one turn")
    }

    @Test
    fun reasoningKeepsTheStampedIdAndNeverAClientMintedOne() {
        var reasoningTurns = 0
        forEachTurn { model, wire ->
            val wireIds = wire.deltas("reasoning_message").map { it.str("id") }.toSet()
            if (wireIds.isEmpty()) return@forEachTurn
            reasoningTurns++
            val rows = mapTurn(wire).filterIsInstance<ServerFrame.ReasoningMessage>()
            assertEquals(wireIds.size, rows.map { it.id }.toSet().size, model)
            assertTrue(rows.all { it.id == it.logicalMessageId && !it.id.startsWith("iroh-reasoning_message-") }, model)
        }
        assertTrue(reasoningTurns > 0, "the captures include reasoning")
    }

    @Test
    fun oneToolRowPerToolCallId() {
        forEachTurn { model, wire ->
            val events = mapTurn(wire).mapNotNull { WsFrameMapper.toLettaMessage(it)?.toTimelineEvent(1.0) }
            val callIds = wire.deltas("tool_call_message").map { it.getValue("tool_call").jsonObject.str("tool_call_id") }.toSet()
            val calls = events.filter { it.messageType == TimelineMessageType.TOOL_CALL }
            val returns = events.filter { it.messageType == TimelineMessageType.TOOL_RETURN }
            assertEquals(callIds.map { "tc-$it" }.toSet(), calls.map { it.logicalId }.toSet(), model)
            assertEquals(callIds.map { "tr-$it" }.toSet(), returns.map { it.logicalId }.toSet(), model)
            assertTrue(calls.all { it.otid == it.logicalId }, model)
        }
    }

    @Test
    fun observerContextIdsDoNotLeakIntoTwoRepliesOfOneConversation() {
        listOf("minimax-m3", "claude-sonnet-5-5", "openrouter-gpt-6.1-sol").forEach { model ->
            val replies = listOf(1, 2).map { turn ->
                mapTurn(wireOf(model, turn)).filterIsInstance<ServerFrame.AssistantMessage>()
            }
            val ids = replies.map { turnRows -> turnRows.map { it.id }.toSet() }
            assertTrue(ids[0].intersect(ids[1]).isEmpty(), model)
            val turnIds = replies.map { turnRows -> turnRows.map { it.turnId }.toSet() }
            assertNotEquals(turnIds[0], turnIds[1], model)
            assertTrue(replies.flatten().none { it.otid?.contains("observer") == true }, model)
        }
    }

    private fun mapTurn(wire: List<JsonObject>): List<ServerFrame> {
        var minted = 0
        val tag = wire.hashCode()
        val identity = TurnStreamIdentity("turn-$tag") { "lm-${++minted}-$tag" }
        return wire.mapNotNull { identity.stamp(it.toString(), StreamTextFrameSource.CumulativeSnapshot) }
            .flatMap { body ->
                IrohStreamDeltaServerFrameMapper.map(
                    payload = RuntimeEventPayload.RemoteStreamFrame(frameId = "f", messageId = null, messageType = null, body = body),
                    context = OBSERVER_CONTEXT,
                )
            }
    }

    private fun List<JsonObject>.deltas(type: String): List<JsonObject> =
        map { it.getValue("delta").jsonObject }.filter { it.str("message_type") == type }

    private fun forEachTurn(block: (String, List<JsonObject>) -> Unit) {
        val root = File(checkNotNull(javaClass.getResource("/timeline/identity/real-turns/README.md")).toURI()).parentFile
        root.listFiles { f -> f.isDirectory }!!.sortedBy { it.name }.forEach { dir ->
            dir.listFiles { f -> f.name.endsWith(".wire-viewer.jsonl") }!!.sortedBy { it.name }.forEach { file ->
                block("${dir.name}/${file.name}", parse(file.readText(StandardCharsets.UTF_8)))
            }
        }
    }

    private fun wireOf(model: String, turn: Int): List<JsonObject> {
        val path = "/timeline/identity/real-turns/$model/turn$turn.wire-viewer.jsonl"
        return parse(checkNotNull(javaClass.getResourceAsStream(path)).bufferedReader().readText())
    }

    private fun parse(text: String): List<JsonObject> =
        text.lines().filter { it.isNotBlank() }
            .map { AppServerProtocol.json.parseToJsonElement(it).jsonObject.getValue("wire").jsonObject }
            .filter { it.str("type") == "stream_delta" }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private companion object {
        val OBSERVER_CONTEXT = IrohStreamDeltaServerFrameMapper.Context(
            agentId = "agent",
            conversationId = "conv",
            turnId = "iroh-observer-turn-conv",
            runId = "iroh-observer-run-conv",
            timestamp = "2026-10-03T00:00:00Z",
        )
    }
}
