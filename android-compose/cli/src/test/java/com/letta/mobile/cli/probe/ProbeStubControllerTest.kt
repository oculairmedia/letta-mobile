package com.letta.mobile.cli.probe

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

import kotlin.time.Duration.Companion.milliseconds
class ProbeStubControllerTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun command(conversation: String = "conv-1") = TurnCommand(
        backendId = BackendId("stub"),
        runtimeId = RuntimeId("stub:agent-1:$conversation"),
        agentId = AgentId("agent-1"),
        conversationId = ConversationId(conversation),
        input = TurnInput.UserMessage(localMessageId = "local-1", text = "hello"),
    )

    private fun deltas(drafts: List<com.letta.mobile.runtime.RuntimeEventDraft>): List<JsonObject> =
        drafts.map { draft ->
            val body = (draft.payload as RuntimeEventPayload.RemoteStreamFrame).body
            json.parseToJsonElement(body).jsonObject
        }

    private fun JsonObject.deltaType(): String? =
        this["delta"]?.jsonObject?.get("message_type")?.jsonPrimitive?.contentOrNull

    @Test
    fun `turn emits typed frames with monotonic event_seq and completed terminal`() = runTest {
        val store = ProbeStubStore()
        val controller = ProbeStubController(store, ProbeStubBehavior(assistantDeltas = 2, deltaDelayMs = 1))

        val envelopes = deltas(controller.runTurn(command()).toList())

        val types = envelopes.map { it.deltaType() }
        assertTrue(types.first() == "reasoning_message", "types: $types")
        assertTrue("tool_call_message" in types)
        assertTrue("tool_return_message" in types)
        assertTrue("assistant_message" in types)
        assertEquals("stop_reason", types.last())
        assertTrue(types.none { it == null }, "all frames must carry message_type: $types")

        val seqs = envelopes.mapNotNull { it["event_seq"]?.jsonPrimitive?.longOrNull }
        assertEquals(envelopes.size, seqs.size)
        assertTrue(seqs.zipWithNext().all { (a, b) -> b > a }, "event_seq must be strictly monotonic: $seqs")

        val terminal = envelopes.last()["delta"]!!.jsonObject
        val runId = terminal["run_id"]?.jsonPrimitive?.contentOrNull
        assertNotNull(runId)
        assertEquals("completed", terminal["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("completed", store.runStatuses[runId])
        assertEquals(2, store.messageCount("conv-1"))
    }

    @Test
    fun `abort midstream yields cancelled terminal with real run id and no dangling tool_call`() = runTest {
        val store = ProbeStubStore()
        val controller = ProbeStubController(store, ProbeStubBehavior(assistantDeltas = 50, deltaDelayMs = 20))
        val runtime = com.letta.mobile.data.transport.appserver.AppServerRuntimeScope(
            agentId = "agent-1",
            conversationId = "conv-1",
        )

        val collected = async { controller.runTurn(command()).toList() }
        // Let the turn start streaming, then abort with the active run id.
        var abortResult: com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AbortMessageResponse? = null
        for (attempt in 0 until 200) {
            delay(10.milliseconds)
            val activeRunId = store.runStatuses.entries.firstOrNull { it.value == "running" }?.key
            if (activeRunId != null) {
                abortResult = controller.abort(runtime, activeRunId)
                break
            }
        }
        val envelopes = deltas(collected.await())

        assertNotNull(abortResult)
        assertTrue(abortResult!!.aborted)

        val types = envelopes.map { it.deltaType() }
        assertEquals("stop_reason", types.last())
        val terminal = envelopes.last()["delta"]!!.jsonObject
        assertEquals("cancelled", terminal["status"]?.jsonPrimitive?.contentOrNull)
        val runId = terminal["run_id"]?.jsonPrimitive?.contentOrNull
        assertNotNull(runId)
        assertTrue(!runId!!.startsWith("cancelled-"), "terminal must carry the REAL run id, not a synthetic one")
        assertEquals("cancelled", store.runStatuses[runId])

        // Every tool_call has a matching tool_return before the terminal frame.
        val openToolCalls = mutableSetOf<String>()
        envelopes.forEach { envelope ->
            val delta = envelope["delta"]!!.jsonObject
            val toolCallId = delta["tool_call"]?.jsonObject?.get("tool_call_id")?.jsonPrimitive?.contentOrNull
                ?: delta["tool_call_id"]?.jsonPrimitive?.contentOrNull
            when (delta["message_type"]?.jsonPrimitive?.contentOrNull) {
                "tool_call_message" -> toolCallId?.let { openToolCalls += it }
                "tool_return_message" -> toolCallId?.let { openToolCalls -= it }
            }
        }
        assertTrue(openToolCalls.isEmpty(), "dangling tool_calls: $openToolCalls")

        // Exactly one terminal.
        assertEquals(1, types.count { it == "stop_reason" })
    }

    @Test
    fun `suppress terminal red path omits stop_reason`() = runTest {
        val store = ProbeStubStore()
        val controller = ProbeStubController(
            store,
            ProbeStubBehavior(assistantDeltas = 1, deltaDelayMs = 1, suppressTerminal = true),
        )

        val types = deltas(controller.runTurn(command()).toList()).map { it.deltaType() }

        assertTrue(types.isNotEmpty())
        assertTrue(types.none { it == "stop_reason" }, "suppress-terminal must drop the terminal frame: $types")
    }

    @Test
    fun `untyped frames red path strips message_type from assistant deltas`() = runTest {
        val store = ProbeStubStore()
        val controller = ProbeStubController(
            store,
            ProbeStubBehavior(assistantDeltas = 2, deltaDelayMs = 1, untypedFrames = true, emitToolCall = false),
        )

        val envelopes = deltas(controller.runTurn(command()).toList())
        val untyped = envelopes.count { it["delta"]!!.jsonObject["message_type"] == null }

        assertEquals(2, untyped)
    }

    @Test
    fun `abort with mismatched run id does not cancel`() = runTest {
        val store = ProbeStubStore()
        val controller = ProbeStubController(store, ProbeStubBehavior(assistantDeltas = 2, deltaDelayMs = 1))
        val runtime = com.letta.mobile.data.transport.appserver.AppServerRuntimeScope(
            agentId = "agent-1",
            conversationId = "conv-1",
        )

        val response = controller.abort(runtime, "run-does-not-exist")

        assertTrue(!response.aborted)
    }
}
