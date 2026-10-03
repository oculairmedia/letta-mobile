package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * letta-mobile-jdcoj: every stream-frame draft is stamped by the turn's [TurnStreamIdentity] inside
 * [TurnDraftProcessor.process]. These tests drive the processor, so reverting that call fails them.
 */
class TurnStreamIdentityTest {

    @Test
    fun appServerDeltasAccumulateInOrderWithGrowingTextSeq() = runTest {
        val turn = StampedTurn(this)
        turn.feed(assistant("Hel", messageId = "m1"), assistant("lo", messageId = "m1"), assistant(" world", messageId = "m1"))
        val deltas = turn.emittedDeltas()
        assertEquals(listOf("Hel", "Hello", "Hello world"), deltas.map { it.field("content") })
        assertEquals(listOf(1, 2, 3), deltas.map { it["text_seq"]?.jsonPrimitive?.intOrNull })
        assertEquals(1, deltas.map { it.field("logical_message_id") }.toSet().size)
        assertEquals(setOf("turn-1"), deltas.map { it.field("turn_id") }.toSet())
    }

    @Test
    fun twoAssistantMessagesInOneRunGetTwoLogicalIds() = runTest {
        val turn = StampedTurn(this)
        turn.feed(
            assistant("First", frameId = "letta-msg-1"),
            toolCall("call-1"),
            assistant("Second", frameId = "letta-msg-2"),
        )
        val assistants = turn.emittedDeltas().filter { it.field("message_type") == "assistant_message" }
        assertEquals(listOf("First", "Second"), assistants.map { it.field("content") })
        assertEquals(listOf(1, 1), assistants.map { it["text_seq"]?.jsonPrimitive?.intOrNull })
        assertNotEquals(assistants[0].field("logical_message_id"), assistants[1].field("logical_message_id"))
        assertEquals(listOf("letta-msg-1", "letta-msg-2"), assistants.map { it.field("id") })
    }

    @Test
    fun messageIdWinsOverSegmentationRule() = runTest {
        val turn = StampedTurn(this)
        turn.feed(
            assistant("Part one", messageId = "m1"),
            toolCall("call-1"),
            assistant(" and two", messageId = "m1"),
            assistant("Other", messageId = "m2"),
        )
        val assistants = turn.emittedDeltas().filter { it.field("message_type") == "assistant_message" }
        assertEquals(listOf("Part one", "Part one and two", "Other"), assistants.map { it.field("content") })
        assertEquals(assistants[0].field("logical_message_id"), assistants[1].field("logical_message_id"))
        assertNotEquals(assistants[1].field("logical_message_id"), assistants[2].field("logical_message_id"))
    }

    @Test
    fun toolCallAndReturnShareTheCallId() = runTest {
        val turn = StampedTurn(this)
        turn.feed(toolCall("call-9"), toolReturn("call-9"))
        val (call, result) = turn.emittedDeltas()
        assertEquals("tc-call-9", call.field("logical_message_id"))
        assertEquals("tr-call-9", result.field("logical_message_id"))
        assertNull(call["text_seq"])
    }

    @Test
    fun userEchoKeepsClientMessageIdAsBothIds() = runTest {
        val turn = StampedTurn(this)
        turn.feed(frame("""{"message_type":"user_message","otid":"cm-android-1","content":"hi"}"""))
        val echo = turn.emittedDeltas().single()
        assertEquals("cm-android-1", echo.field("logical_message_id"))
        assertEquals("cm-android-1", echo.field("turn_id"))
    }

    @Test
    fun replayedFrameIsDropped() = runTest {
        val turn = StampedTurn(this)
        val first = assistant("same", messageId = "m1", key = "replay-key")
        turn.feed(first, first)
        assertEquals(listOf("same"), turn.emittedDeltas().map { it.field("content") })
    }

    @Test
    fun externalTransportSnapshotReplacesText() = runTest {
        val turn = StampedTurn(this)
        turn.feed(
            snapshot("""{"message_type":"assistant_message","message_id":"m1","content":"Hel"}"""),
            snapshot("""{"message_type":"assistant_message","message_id":"m1","content":"Hello"}"""),
        )
        val deltas = turn.emittedDeltas()
        assertEquals(listOf("Hel", "Hello"), deltas.map { it.field("content") })
        assertEquals(listOf(1, 2), deltas.map { it["text_seq"]?.jsonPrimitive?.intOrNull })
    }

    @Test
    fun reasoningAndAssistantTextGetSeparateLogicalIds() = runTest {
        val turn = StampedTurn(this)
        turn.feed(
            frame("""{"message_type":"reasoning_message","reasoning":"think"}"""),
            assistant("answer"),
        )
        val (thought, reply) = turn.emittedDeltas()
        assertEquals("think", thought.field("reasoning"))
        assertNotEquals(thought.field("logical_message_id"), reply.field("logical_message_id"))
    }

    @Test
    fun stopReasonIsForwardedUnstamped() = runTest {
        val turn = StampedTurn(this)
        turn.feed(frame("""{"message_type":"stop_reason","stop_reason":"end_turn"}"""))
        turn.finish()
        val stop = turn.emittedDeltas().single()
        assertNull(stop["logical_message_id"])
        assertNull(stop["turn_id"])
    }

    private fun assistant(
        text: String,
        messageId: String? = null,
        frameId: String? = null,
        key: String? = null,
    ): RuntimeEventDraft {
        val fields = listOfNotNull(
            """"message_type":"assistant_message"""",
            """"run_id":"run-1"""",
            messageId?.let { """"message_id":"$it"""" },
            frameId?.let { """"id":"$it"""" },
            """"content":"$text"""",
        ).joinToString(",")
        return frame("{$fields}", key = key)
    }

    private fun toolCall(callId: String) = frame(
        """{"message_type":"tool_call_message","tool_call":{"tool_call_id":"$callId","name":"bash","arguments":"{}"}}""",
    )

    private fun toolReturn(callId: String) = frame(
        """{"message_type":"tool_return_message","tool_call_id":"$callId","tool_return":"ok","status":"success"}""",
    )

    private fun frame(delta: String, key: String? = null): RuntimeEventDraft {
        val keyField = key?.let { """"idempotency_key":"$it",""" }.orEmpty()
        return streamDraft(
            RuntimeEventPayload.RemoteStreamFrame(frameId = key ?: "f", body = """{"type":"stream_delta",$keyField"delta":$delta}"""),
        )
    }

    private fun snapshot(delta: String) = streamDraft(
        RuntimeEventPayload.ExternalTransportFrame(frameId = "s", body = """{"type":"stream_delta","delta":$delta}"""),
    )
}

internal fun streamDraft(payload: RuntimeEventPayload) = RuntimeEventDraft(
    backendId = BackendId("backend-1"),
    runtimeId = RuntimeId("runtime-1"),
    runId = RunId("run-1"),
    source = RuntimeEventSource.LocalRuntime,
    payload = payload,
)

internal fun JsonObject.field(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** One [TurnDraftProcessor] with a deterministic stamper, recording the drafts it emits. */
private class StampedTurn(scope: TestScope) {
    private val emitted = mutableListOf<RuntimeEventDraft>()
    private var minted = 0

    private val processor = TurnDraftProcessor(
        callbacks = TurnDraftCallbacks(
            autoApprovedDraft = { null },
            track = { _, _ -> },
            clearApprovals = { },
            emit = { emitted += it },
            settle = { _, _ -> },
            completedDraft = { error("no completion expected") },
            recordTerminal = { _, _ -> },
            noteCompleted = { },
            complete = { error("no completion expected") },
            settleDelayMs = 1_500,
        ),
        coroutineScope = scope.backgroundScope,
        identity = TurnStreamIdentity("turn-1") { "lm-${++minted}" },
    )

    suspend fun feed(vararg drafts: RuntimeEventDraft) = drafts.forEach { processor.process(it, frameSeq = null) }

    /** Releases a buffered tail frame (stop_reason) the way the turn's terminal flush does. */
    suspend fun finish() = processor.flushTail()

    fun emittedDeltas(): List<JsonObject> = emitted.map { draft ->
        val body = when (val payload = draft.payload) {
            is RuntimeEventPayload.RemoteStreamFrame -> payload.body
            is RuntimeEventPayload.ExternalTransportFrame -> payload.body
            else -> error("unexpected payload $payload")
        }
        AppServerProtocol.json.parseToJsonElement(body).jsonObject.getValue("delta").jsonObject
    }
}
