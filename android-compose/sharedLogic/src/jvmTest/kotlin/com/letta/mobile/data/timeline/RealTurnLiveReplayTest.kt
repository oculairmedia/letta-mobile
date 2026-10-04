package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.runtime.RealTurnLedgerFixtures
import com.letta.mobile.data.runtime.StreamTextFrameSource
import com.letta.mobile.data.runtime.TurnStreamIdentity
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.transport.iroh.IrohStreamDeltaServerFrameMapper
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.PersistentMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-nbha6: the 20 real captured turns (10 model routes x 2, including grok's two assistant
 * messages per turn, reasoning, and a tool call whose call and return are each emitted more than once)
 * are stamped by the host's [TurnStreamIdentity], mapped by the client and folded by [reduceLiveFrame],
 * frame by frame. After every frame: one row per logical id and text that only grows. At the end: the
 * rows hold exactly the stored `message.list` text and every tool return sits on its `tc-<call id>` row.
 */
class RealTurnLiveReplayTest {

    @Test
    fun everyRealTurnFoldsIntoOneRowPerLogicalIdWithTheStoredText() {
        Telemetry.clear()
        forEachTurn { turn ->
            val label = turn.label
            val replay = Replay().apply { liveMessages(turn).forEach(::feed) }
            val stored = storedTexts(turn)

            val streamedIds = replay.texts.keys
            assertTrue(streamedIds.isNotEmpty(), label)
            assertTrue(streamedIds.all { it in stored }, "$label: every streamed text row is stored")
            streamedIds.forEach { id -> assertEquals(stored.getValue(id), replay.row(id).content, "$label $id") }
            assertEquals(replay.rows().size, replay.rows().map { it.logicalId }.toSet().size, label)
        }
        assertEquals(0, telemetryCount("live.duplicateAppend"))
        assertEquals(0, telemetryCount("live.unstampedFrame"))
    }

    @Test
    fun textGrowsMonotonicallyAndNeverAppendsASecondRow() {
        forEachTurn { turn ->
            val label = turn.label
            val replay = Replay()
            liveMessages(turn).forEach { message ->
                replay.feed(message)
                replay.texts.forEach { (id, history) ->
                    history.zipWithNext().forEach { (before, after) ->
                        assertTrue(after.startsWith(before), "$label $id: text shrank or was rewritten")
                    }
                    assertEquals(1, replay.rows().count { it.logicalId == id }, "$label $id: one row")
                }
            }
        }
    }

    @Test
    fun replayedAndOutOfOrderTextFramesAreDroppedAsStaleAndNeverChangeTheRows() {
        forEachTurn { turn ->
            val label = turn.label
            Telemetry.clear()
            val messages = liveMessages(turn)
            val clean = Replay().apply { messages.forEach(::feed) }
            val textFrames = messages.count { it.isStampedText() }
            val noisy = Replay().apply {
                // Each text frame twice, then the whole turn's text frames again in reverse.
                messages.forEach { feed(it); if (it.isStampedText()) feed(it) }
                messages.filter { it.isStampedText() }.asReversed().forEach(::feed)
            }

            assertEquals(clean.rows().map { it.logicalId to it.content }, noisy.rows().map { it.logicalId to it.content }, label)
            assertEquals(textFrames * 2, telemetryCount("live.staleTextFrame"), label)
            assertEquals(0, telemetryCount("live.duplicateAppend"), label)
        }
    }

    @Test
    fun everyToolReturnAttachesToItsCallRowAndNothingStaysParked() {
        var callsSeen = 0
        forEachTurn { turn ->
            val label = turn.label
            val replay = Replay().apply { liveMessages(turn).forEach(::feed) }
            val calls = replay.rows().filter { it.messageType == TimelineMessageType.TOOL_CALL }
            callsSeen += calls.size
            assertEquals(1, calls.size, label)
            calls.forEach { call ->
                val callId = call.logicalId.removePrefix("tc-")
                assertTrue(call.logicalId.startsWith("tc-"), label)
                assertTrue(call.toolReturnContentByCallId.getValue(callId).isNotBlank(), "$label: return attached to $callId")
            }
            assertTrue(replay.pending.isEmpty(), "$label: no return left parked")
            assertTrue(replay.rows().none { it.messageType == TimelineMessageType.TOOL_RETURN }, label)
        }
        assertEquals(20, callsSeen)
    }

    @Test
    fun grokWritesTwoAssistantMessagesPerTurnAndTheyStayTwoRows() {
        listOf(1, 2).forEach { turn ->
            val replay = Replay().apply { liveMessages(Turn("openrouter-grok-4.7", turn)).forEach(::feed) }
            val assistants = replay.rows().filter { it.messageType == TimelineMessageType.ASSISTANT }
            assertEquals(2, assistants.size, "turn $turn")
            assertEquals(2, assistants.map { it.logicalId }.toSet().size)
        }
    }

    private fun LettaMessage.isStampedText(): Boolean = textSeq != null

    private fun telemetryCount(name: String): Int = Telemetry.events.value.count { it.name == name }

    /** One captured turn: the model route it came from and its number. */
    private data class Turn(val model: String, val number: Int) {
        val label: String get() = "$model/turn$number"
    }

    private class Replay {
        private var state = TimelineReducerState(Timeline("conv"))
        val timeline: Timeline get() = state.timeline
        val pending: PersistentMap<String, ToolReturnMessage> get() = state.pendingToolReturnsByCallId

        /** Every text a logical id has held after a frame, in order. */
        val texts = linkedMapOf<String, MutableList<String>>()

        fun feed(message: LettaMessage) {
            state = state.reduceLive(message, agentId = null)
            rows().filter { it.messageType.isStreamedText() }.forEach { row ->
                val history = texts.getOrPut(row.logicalId) { mutableListOf() }
                if (history.lastOrNull() != row.content) history += row.content
            }
        }

        fun rows(): List<TimelineEvent.Confirmed> = timeline.events.filterIsInstance<TimelineEvent.Confirmed>()

        fun row(logicalId: String): TimelineEvent.Confirmed = rows().single { it.logicalId == logicalId }

        private fun TimelineMessageType.isStreamedText() =
            this == TimelineMessageType.ASSISTANT || this == TimelineMessageType.REASONING
    }

    /** The turn as the client receives it: the viewer wire stamped by the host, then mapped. */
    private fun liveMessages(turn: Turn): List<LettaMessage> {
        var minted = 0
        val tag = turn.label.replace("/", "-")
        val identity = TurnStreamIdentity("turn-$tag") { "lm-${++minted}-$tag" }
        return RealTurnLedgerFixtures.wireFrames(turn.model, turn.number)
            .filter { it.str("type") == "stream_delta" }
            .mapNotNull { identity.stamp(it.toString(), StreamTextFrameSource.CumulativeSnapshot) }
            .flatMap { body ->
                IrohStreamDeltaServerFrameMapper.map(
                    payload = RuntimeEventPayload.RemoteStreamFrame(frameId = "f", messageId = null, messageType = null, body = body),
                    context = CONTEXT,
                )
            }
            .mapNotNull(WsFrameMapper::toLettaMessage)
    }

    /** The stored assistant and reasoning text of the rows `message.list` returned for the turn. */
    private fun storedTexts(turn: Turn): Map<String, String> =
        RealTurnLedgerFixtures.listedRows(turn.model, turn.number)
            .filter { it.str("message_type") == "assistant_message" || it.str("message_type") == "reasoning_message" }
            .associate { it.str("id") to storedText(it) }

    private fun storedText(row: JsonObject): String =
        (row["content"] as? JsonArray)?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            ?: row.str("reasoning")

    private fun forEachTurn(block: (Turn) -> Unit) {
        RealTurnLedgerFixtures.MODELS.forEach { model ->
            listOf(1, 2).forEach { number -> block(Turn(model, number)) }
        }
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private companion object {
        val CONTEXT = IrohStreamDeltaServerFrameMapper.Context(
            agentId = "agent",
            conversationId = "conv",
            turnId = "iroh-observer-turn-conv",
            runId = "iroh-observer-run-conv",
            timestamp = "2026-10-03T00:00:00Z",
        )
    }
}
