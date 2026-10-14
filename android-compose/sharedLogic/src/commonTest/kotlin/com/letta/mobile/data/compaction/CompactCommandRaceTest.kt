package com.letta.mobile.data.compaction

import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** letta-mobile-by4vm.40: which `slash_command_end` answers a command, and what a bare one means. */
class CompactCommandRaceTest {
    private val runtime = AppServerRuntimeScope("agent-1", "conv-1")
    private val compact = AppServerCommand.ExecuteCommand(requestId = "r-1", commandId = "compact", runtime = runtime)

    private fun slashEnd(fields: JsonObjectBuilder.() -> Unit) = AppServerInboundFrame.StreamDelta(
        runtime = runtime,
        eventSeq = 1,
        emittedAt = "",
        idempotencyKey = "k",
        delta = buildJsonObject {
            put("message_type", "slash_command_end")
            fields()
        },
    )

    @Test
    fun aLongerCommandSharingThePrefixDoesNotAnswerIt() {
        val other = slashEnd {
            put("input", "/compactify now")
            put("output", "done")
            put("success", true)
        }

        assertNull(CommandAnswer.fromSlashEnd(other, compact))
    }

    @Test
    fun theCommandByItsInputAloneOrWithArgsAnswersIt() {
        val bare = slashEnd {
            put("input", "  /compact ")
            put("success", true)
        }
        val withArgs = slashEnd {
            put("input", "/compact all")
            put("output", "ok")
            put("success", true)
        }

        assertEquals(CommandAnswer(success = true, output = null), CommandAnswer.fromSlashEnd(bare, compact))
        assertEquals(CommandAnswer(success = true, output = "ok"), CommandAnswer.fromSlashEnd(withArgs, compact))
    }

    @Test
    fun anEndWithoutASuccessFlagIsNotASuccess() {
        val bare = slashEnd {
            put("command_id", "compact")
            put("output", "Compaction completed")
        }

        assertEquals(CommandAnswer(success = false, output = "Compaction completed"), CommandAnswer.fromSlashEnd(bare, compact))
    }
}
