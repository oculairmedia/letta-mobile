package com.letta.mobile.data.transport.appserver

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Protocol compatibility suite (letta-mobile-lgns8.4).
 *
 * Drives the pinned upstream fixtures captured by the contract baseline
 * (letta-mobile-lgns8.1) to prove the tolerant codec stays faithful:
 * - every outgoing command round-trips to the pinned wire fixture exactly, and
 * - every inbound frame decodes to its concrete typed frame with the raw
 *   envelope preserved (never [AppServerInboundFrame.Unknown] or
 *   [AppServerInboundFrame.DecodeFailure]).
 */
class AppServerProtocolCompatibilityTest {

    @Test
    fun everyPinnedCommandFixtureRoundTripsToUpstreamWire() {
        val commands = protocolFixtures().filter { typeOf(it) in COMMAND_TYPES }
        assertTrue(commands.isNotEmpty(), "expected command fixtures in protocol-frames.jsonl")

        commands.forEach { fixture ->
            val command = AppServerProtocol.json.decodeFromJsonElement(
                AppServerCommand.serializer(),
                fixture,
            )
            val reencoded = AppServerProtocol.json.parseToJsonElement(
                AppServerProtocol.encodeCommand(command),
            )
            assertEquals(
                fixture,
                reencoded,
                "command ${typeOf(fixture)} must re-encode to the pinned upstream fixture",
            )
        }
    }

    @Test
    fun everyPinnedInboundFixtureDecodesToTypedFrameWithRawPreserved() {
        val inbound = protocolFixtures().filter { typeOf(it) in INBOUND_TYPES }
        assertTrue(inbound.isNotEmpty(), "expected inbound fixtures in protocol-frames.jsonl")

        inbound.forEach { fixture ->
            AppServerChannel.entries.forEach { channel ->
                val decoded = AppServerProtocol.decodeFrame(fixture.toString(), channel)
                assertEquals(typeOf(fixture), decoded.frame.type)
                assertFalse(
                    decoded.frame is AppServerInboundFrame.Unknown,
                    "${typeOf(fixture)} must decode to a typed frame, not Unknown",
                )
                assertFalse(
                    decoded.frame is AppServerInboundFrame.DecodeFailure,
                    "${typeOf(fixture)} must decode cleanly, not DecodeFailure",
                )
                assertEquals(fixture, decoded.raw, "${typeOf(fixture)} raw envelope must be preserved")
            }
        }
    }

    @Test
    fun everyPinnedFixtureTypeIsClassifiedAsCommandOrInbound() {
        // Guards against an upstream fixture drift adding a type this suite does
        // not exercise on either the command or inbound path.
        protocolFixtures().forEach { fixture ->
            val type = typeOf(fixture)
            assertTrue(
                type in COMMAND_TYPES || type in INBOUND_TYPES,
                "fixture type '$type' is neither a known command nor inbound frame",
            )
        }
    }

    private fun protocolFixtures(): List<JsonObject> =
        fixtureText("protocol-frames.jsonl")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { AppServerProtocol.json.parseToJsonElement(it).jsonObject }
            .toList()

    private fun typeOf(row: JsonObject): String =
        assertNotNull(row["type"], "fixture missing type").jsonPrimitive.content

    private fun fixtureText(name: String): String {
        val stream = assertNotNull(
            javaClass.getResourceAsStream("/appserver/$name"),
            "missing fixture $name",
        )
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private companion object {
        val COMMAND_TYPES = setOf(
            "auth",
            "runtime_start",
            "input",
            "sync",
            "abort_message",
            "external_tool_call_response",
            "admin_rpc",
        )

        val INBOUND_TYPES = setOf(
            "auth_response",
            "runtime_start_response",
            "sync_response",
            "abort_message_response",
            "external_tool_call_request",
            "control_request",
            "admin_rpc_response",
            "stream_delta",
            "update_loop_status",
            "update_device_status",
            "update_queue",
            "update_subagent_state",
        )
    }
}
