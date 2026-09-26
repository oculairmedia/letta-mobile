package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-qygvv.27: one tool_call_message per call, lifecycle frames as lifecycle frames. */
class RelayedToolProjectionTest {

    @Test
    fun autoApprovedArgumentsReachThePhoneDecodedOnce() {
        val projection = RelayedToolProjection()
        val encoded = "\"{\\\"command\\\": \\\"ls\\\"}\""

        val delta = projection.toolCall(call(arguments = encoded), RunId(RUN))!!

        assertEquals("tool_call_message", delta.string("message_type"))
        assertEquals("{\"command\": \"ls\"}", (delta["tool_call"] as JsonObject).string("arguments"))
        assertEquals(RUN, delta.string("run_id"))
    }

    @Test
    fun objectArgumentsAreLeftAlone() {
        assertEquals("{\"a\":1}", RelayedToolProjection.decodedArguments("{\"a\":1}"))
        assertEquals("{}", RelayedToolProjection.decodedArguments(null))
    }

    @Test
    fun clientToolStartIsALifecycleSignalNotASecondCall() {
        val projection = RelayedToolProjection()
        projection.toolCall(call(arguments = "{\"a\":1}"), RunId(RUN))

        val start = projection.toolCall(call(arguments = null), RunId(RUN))!!
        val externalRequest = projection.toolCall(call(arguments = "{\"a\":1}"), null)

        assertEquals("client_tool_start", start.string("message_type"))
        assertEquals(CALL, start.string("tool_call_id"))
        assertEquals("Bash", start.string("tool_name"))
        assertNull(externalRequest, "a call the phone already has is not announced again")
    }

    @Test
    fun clientToolEndIsALifecycleSignalNotAToolReturn() {
        val projection = RelayedToolProjection()
        val lifecycleBody = buildJsonObject {
            put("message_type", "client_tool_end")
            put("tool_call_id", CALL)
            put("status", "success")
        }.toString()

        val end = projection.toolReturn(toolReturn(lifecycleBody), RunId(RUN))!!

        assertEquals("client_tool_end", end.string("message_type"))
        assertEquals("success", end.string("status"))
        assertNull(end["output"], "the lifecycle JSON is never a tool result")
        assertNull(projection.toolReturn(toolReturn(lifecycleBody), RunId(RUN)), "one end per call")
    }

    @Test
    fun settledDanglingCallStillGetsItsToolReturn() {
        val projection = RelayedToolProjection()

        val settled = projection.toolReturn(toolReturn("interrupted", ToolExecutionStatus.Failed), runId = null)!!

        assertEquals("tool_return_message", settled.string("message_type"))
        assertEquals("error", settled.string("status"))
        assertEquals("interrupted", settled.string("tool_return"))
    }

    @Test
    fun aRelayedToolCallMessageCountsAsAnnounced() {
        val projection = RelayedToolProjection()
        projection.noteRelayed(
            buildJsonObject {
                put("message_type", "tool_call_message")
                put(
                    "tool_call",
                    buildJsonObject {
                        put("tool_call_id", CALL)
                        put("name", "Bash")
                        put("arguments", "{}")
                    },
                )
            },
        )

        assertNull(projection.toolCall(call(arguments = "{}"), RunId(RUN)), "the relayed delta already announced it")
    }

    private fun JsonObject.string(key: String): String? = parityString(key)

    private fun call(arguments: String?) =
        RuntimeEventPayload.ToolCallObserved(ToolCallId(CALL), ToolName("Bash"), arguments)

    private fun toolReturn(body: String, status: ToolExecutionStatus = ToolExecutionStatus.Succeeded) =
        RuntimeEventPayload.ToolReturnObserved(ToolCallId(CALL), status, body)

    private companion object {
        const val CALL = "call-1"
        const val RUN = "run-1"
    }
}
