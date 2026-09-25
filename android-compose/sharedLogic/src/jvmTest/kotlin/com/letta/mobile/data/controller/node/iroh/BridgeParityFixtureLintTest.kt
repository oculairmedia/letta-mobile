package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.14: every bridge-parity fixture frame is a known App Server message, so a
 * typo cannot decode as Unknown and pass the gate silently, and every recording is replayed.
 */
class BridgeParityFixtureLintTest {

    @Test
    fun everyFixtureFrameIsAKnownAppServerMessage() {
        val problems = fixtureFiles().flatMap { file ->
            val lines = file.readLines().filter { it.isNotBlank() }
            val runtime = runtimeOf(lines.first())
            lines.flatMapIndexed { index, line ->
                BridgeParityFixtureLint.problems(line, runtime).map { "${file.name}:${index + 1}: $it" }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun everyRecordingIsReplayedByTheGateOrItsOwnTest() {
        val onDisk = fixtureFiles().map { "$DIR/${it.name}" }.toSet()
        val replayed = BridgeParityFixtures.ALL.map { it.resource }.toSet() + BridgeParityFixtures.PARTIAL
        assertEquals(onDisk, replayed, "a fixture on disk is not in BridgeParityFixtures, or one listed is missing")
    }

    @Test
    fun lintRejectsTypos() {
        val runtime = AppServerProtocol.json.parseToJsonElement("""{"agent_id":"a","conversation_id":"c"}""")
        val cases = mapOf(
            """{"type":"stream_delat","delta":{"message_type":"assistant_message"}}""" to "unknown frame type",
            """{"type":"stream_delta","delta":{"message_type":"assistant_mesage","run_id":"r","seq_id":1,"type":"message"},"runtime":{"agent_id":"a","conversation_id":"c"},"event_seq":1,"emitted_at":"t","idempotency_key":"k"}""" to "unknown delta message_type",
            """{"type":"update_loop_status","loop_status":{"status":"WAITING_ON_INPT","active_run_ids":[],"executing_tool_call_ids":[]},"runtime":{"agent_id":"a","conversation_id":"c"},"event_seq":1,"emitted_at":"t","idempotency_key":"k"}""" to "unknown loop status",
            """{"type":"turn_finished","turn_id":"t","stop_reason":"end_trun","runtime":{"agent_id":"a","conversation_id":"c"},"event_seq":1,"emitted_at":"t","idempotency_key":"k"}""" to "unclassified stop_reason",
            """{"type":"turn_finished","turn_id":"t","stop_reason":"end_turn","runtime":{"agent_id":"a","conversation_id":"other"},"event_seq":1,"emitted_at":"t","idempotency_key":"k"}""" to "is not the turn's",
            """{"type":"turn_finished","stop_reason":"end_turn","runtime":{"agent_id":"a","conversation_id":"c"}}""" to "does not decode",
        )
        for ((line, expected) in cases) {
            val problems = BridgeParityFixtureLint.problems(line, runtime)
            assertTrue(problems.any { expected in it }, "expected '$expected' for $line, got $problems")
        }
    }

    private fun runtimeOf(ack: String): JsonElement =
        AppServerProtocol.json.parseToJsonElement(ack).jsonObject.getValue("runtime")

    private fun fixtureFiles(): List<File> {
        val dir = requireNotNull(javaClass.classLoader.getResource(DIR)) { "missing $DIR" }
        return File(dir.toURI()).listFiles { file -> file.extension == "jsonl" }.orEmpty().sortedBy { it.name }
    }

    private companion object {
        const val DIR = "appserver/bridge-parity"
    }
}
