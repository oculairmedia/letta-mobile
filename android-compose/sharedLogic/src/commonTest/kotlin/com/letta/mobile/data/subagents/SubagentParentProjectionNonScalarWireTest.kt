package com.letta.mobile.data.subagents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * letta-mobile-fkpd4: reading a raw wire field must never throw.
 *
 * Reproduces the m6oa1.6 production failure. [SubagentParentProjection] read
 * wire fields with the THROWING `.jsonPrimitive` accessor. On a frame whose
 * `status` or `content` was array-valued it raised
 * "Element class kotlinx.serialization.json.JsonArray ... is not a
 * JsonPrimitive", which escaped into AppServerTurnEngine's turn collect loop
 * and settled the parent turn as "Tool execution interrupted by stream error".
 * The child had SUCCEEDED; the user saw a failed turn.
 */
class SubagentParentProjectionNonScalarWireTest {

    private fun obj(raw: String) = Json.parseToJsonElement(raw) as JsonObject

    @Test
    fun `array valued status does not throw and is not read as failed`() {
        val out = SubagentParentProjection.sanitizedAgentReturn(
            obj(
                """{"message_type":"tool_return_message","tool_call_id":"tool-call-1",
                   "status":["error"],"tool_return":"AGENT_TOOL_CAPTURED"}""",
            ),
            "parent-conversation-1",
        )
        // A non-scalar status is simply not a status here — it must not be
        // silently coerced into the FAILED branch.
        assertNull(out["subagent_error_tail"])
        assertNotNull(out["tool_return"])
    }

    @Test
    fun `object valued status does not throw`() {
        SubagentParentProjection.sanitizedAgentReturn(
            obj(
                """{"message_type":"tool_return_message","tool_call_id":"tool-call-1",
                   "status":{"code":"error"},"tool_return":"x"}""",
            ),
            "parent-conversation-1",
        )
    }

    @Test
    fun `array valued content does not throw and its text blocks are recovered`() {
        val line = SubagentParentProjection.activityLine(
            obj(
                """{"message_type":"assistant_message",
                   "content":[{"type":"text","text":"Reading the reducer"}]}""",
            ),
        )
        assertEquals("Reading the reducer", line)
    }

    @Test
    fun `content blocks with no text yield no activity line rather than throwing`() {
        assertNull(
            SubagentParentProjection.activityLine(
                obj("""{"message_type":"assistant_message","content":[{"type":"image"}]}"""),
            ),
        )
    }

    @Test
    fun `scalar content still works`() {
        assertEquals(
            "Reading the reducer",
            SubagentParentProjection.activityLine(
                obj("""{"message_type":"assistant_message","content":"Reading the reducer"}"""),
            ),
        )
    }

    @Test
    fun `array valued message_type is ignored rather than throwing`() {
        assertNull(
            SubagentParentProjection.activityLine(
                obj("""{"message_type":["assistant_message"],"content":"hi"}"""),
            ),
        )
    }
}
