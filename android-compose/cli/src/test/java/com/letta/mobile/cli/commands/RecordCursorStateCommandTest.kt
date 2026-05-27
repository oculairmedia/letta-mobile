package com.letta.mobile.cli.commands

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecordCursorStateCommandTest {
    @Test
    fun `cursor snapshot keeps highest observed seq per conversation run`() {
        val snapshot = buildCursorStateSnapshot(
            listOf(
                """{"kind":"cursor","conversation_id":"conv-1","run_id":"run-1","seq":2}""",
                """{"kind":"ws_frame","frame":{"type":"assistant_message","conversation_id":"conv-1","run_id":"run-1","seq_id":3}}""",
                """{"type":"assistant_message","conversation_id":"conv-1","run_id":"run-2","seq":1}""",
                """{"kind":"cursor","conversation_id":"conv-1","seq":99}""",
                "not json",
            )
        )

        val conversation = snapshot["conv-1"]?.jsonObject

        assertEquals("3", conversation?.get("run-1")?.jsonPrimitive?.contentOrNull)
        assertEquals("1", conversation?.get("run-2")?.jsonPrimitive?.contentOrNull)
        assertNull(snapshot["conv-2"])
    }
}
