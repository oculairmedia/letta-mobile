package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * letta-mobile-qygvv.8: `update_loop_status.loop_status` run-binding fields added in App Server
 * 0.32.17 (`client_message_ids_by_run_id`, `executing_tool_call_ids`) decode, and default to empty
 * for older servers that omit them.
 */
class AppServerLoopStatusDecodingTest {
    @Test
    fun loopStatusWithoutRunBindingFieldsDecodesEmpty() {
        val status = decodeLoopStatus("""{"status": "SOME_FUTURE_STATUS", "active_run_ids": ["run-9"]}""")

        assertEquals(listOf("run-9"), status.activeRunIds)
        assertEquals(emptyMap(), status.clientMessageIdsByRunId)
        assertEquals(emptyList(), status.executingToolCallIds)
    }

    @Test
    fun loopStatusDecodesRunBindingFields() {
        val status = decodeLoopStatus(
            """
                {
                  "status": "EXECUTING_CLIENT_SIDE_TOOL",
                  "active_run_ids": ["local-run-20"],
                  "client_message_ids_by_run_id": {"local-run-1": ["cm-a"], "local-run-20": ["cm-b", "cm-c"]},
                  "executing_tool_call_ids": ["call-1"],
                  "future_loop_field": 1
                }
            """.trimIndent(),
        )

        assertEquals(
            mapOf("local-run-1" to listOf("cm-a"), "local-run-20" to listOf("cm-b", "cm-c")),
            status.clientMessageIdsByRunId,
        )
        assertEquals(listOf("call-1"), status.executingToolCallIds)
    }

    /** Decodes an `update_loop_status` stream frame carrying [loopStatusJson]. */
    private fun decodeLoopStatus(loopStatusJson: String): AppServerLoopStatus {
        val received = AppServerProtocol.decodeFrame(
            rawJson = """
                {
                  "type": "update_loop_status",
                  "runtime": {"agent_id": "agent-1", "conversation_id": "conv-1"},
                  "event_seq": 8,
                  "emitted_at": "2026-09-24T00:00:00Z",
                  "idempotency_key": "evt-8",
                  "loop_status": $loopStatusJson
                }
            """.trimIndent(),
            channel = AppServerChannel.Stream,
        )
        return assertIs<AppServerInboundFrame.UpdateLoopStatus>(received.frame).loopStatus
    }
}
