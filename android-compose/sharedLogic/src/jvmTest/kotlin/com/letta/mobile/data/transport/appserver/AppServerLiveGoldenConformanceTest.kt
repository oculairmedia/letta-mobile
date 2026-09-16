package com.letta.mobile.data.transport.appserver

import com.letta.mobile.data.runtime.lifecycleStatusFromTerminal
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Conformance against frames captured from a live App Server (letta-code 0.32.3, whose protocol
 * unions equal 0.32.10) with the official TS helper `@letta-ai/letta-code/app-server-client`.
 *
 * Captures (see `golden/letta-code-0.32.3-live.jsonl`, one row per sent/received frame):
 * - `model_error`: turns whose provider call fails (loop_error, error_message, stop_reason=error).
 * - `standard`: HTTP discovery, legacy `?channel=` rejection, info, runtime_start, an end_turn
 *   turn with input_accepted, a requires_approval stop that the server's classifier auto-approved
 *   mid-turn, sync and an idle abort.
 * - `strict_approval`: change_device_state to strict, then control_request → deny → end_turn.
 *
 * Only `device_status.current_available_skills` is trimmed (host skill listing), marked per row.
 */
class AppServerLiveGoldenConformanceTest {
    private val rows: List<JsonObject> = javaClass.getResourceAsStream("/appserver/golden/letta-code-0.32.3-live.jsonl")
        .let { assertNotNull(it, "missing golden capture") }
        .bufferedReader(StandardCharsets.UTF_8).use { it.readLines() }
        .filter { it.isNotBlank() }
        .map { AppServerProtocol.json.parseToJsonElement(it).jsonObject }

    private fun frames(direction: String) = rows.filter { it.str("direction") == direction }.map { it["frame"]!!.jsonObject to it }

    @Test
    fun everyServerFrameDecodesToATypedFrame() {
        val server = frames("server_to_client")
        assertTrue(server.size > 150, "capture unexpectedly small: ${server.size}")
        server.forEach { (frame, row) ->
            val decoded = AppServerProtocol.decodeFrame(frame.toString()).frame
            assertFalse(decoded is AppServerInboundFrame.Unknown, "unmodelled server frame ${frame.str("type")} in ${row.str("scenario")}")
            assertFalse(decoded is AppServerInboundFrame.DecodeFailure, "decode failure for ${frame.str("type")}: ${(decoded as? AppServerInboundFrame.DecodeFailure)?.diagnostic}")
        }
        val types = server.map { it.first.str("type") }.toSet()
        assertTrue(types.containsAll(setOf("input_accepted", "turn_finished", "control_request", "stream_delta", "runtime_start_response")), "capture lost coverage: $types")
    }

    @Test
    fun everyClientCommandRoundTripsWithoutInventingOrDroppingFields() {
        frames("client_to_server").forEach { (frame, row) ->
            val command = AppServerProtocol.json.decodeFromJsonElement(AppServerCommand.serializer(), frame)
            val reencoded = AppServerProtocol.json.parseToJsonElement(AppServerProtocol.encodeCommand(command)).jsonObject
            assertEquals(frame, reencoded, "${frame.str("type")} in ${row.str("scenario")} must round-trip exactly")
        }
    }

    @Test
    fun inputRequestIdIsAnsweredByInputAcceptedNotByTheTurnEnd() {
        // request_id is connection-local, so each capture (one connection) is matched on its own.
        rows.groupBy { it.str("capture") }.forEach { (capture, captureRows) ->
            fun framesOf(direction: String) = captureRows.filter { it.str("direction") == direction }.map { it["frame"]!!.jsonObject }
            framesOf("client_to_server")
                .filter { it.str("type") == "input" && it["request_id"] != null }
                .forEach { input ->
                    val requestId = input.str("request_id")
                    val acks = framesOf("server_to_client").filter { (it["request_id"] as? JsonElement)?.jsonPrimitive?.content == requestId }
                    val ack = assertIs<AppServerInboundFrame.InputAccepted>(AppServerProtocol.decodeFrame(acks.single().toString()).frame, "$capture $requestId")
                    assertTrue(ack.accepted)
                    assertEquals("started", ack.disposition)
                }
        }
    }

    @Test
    fun requiresApprovalNeverSettlesAndEachTurnEndsOnceWithTurnFinishedAfterTheStop() {
        rows.groupBy { it.str("capture") to it.str("scenario") }
            .filterKeys { (_, scenario) -> scenario.startsWith("turn_") }
            .forEach { (key, scenarioRows) ->
                val received = scenarioRows.filter { it.str("direction") == "server_to_client" }
                    .map { AppServerProtocol.decodeFrame(it["frame"]!!.toString()) }
                val stops = received.mapNotNull { r ->
                    (r.frame as? AppServerInboundFrame.StreamDelta)?.delta?.jsonObject
                        ?.takeIf { it.str("message_type") == "stop_reason" }
                        ?.let { r to it.str("stop_reason") }
                }
                stops.filter { it.second == AppServerStopReason.REQUIRES_APPROVAL }.forEach { (frame, _) ->
                    assertEquals(null, frame.lifecycleStatusFromTerminal(), "$key: requires_approval settled the turn")
                }
                val terminalStops = stops.filter { AppServerStopReason.isTerminal(it.second) }
                assertEquals(1, terminalStops.size, "$key: expected exactly one terminal stop, got ${stops.map { it.second }}")

                val finished = received.map { it.frame }.filterIsInstance<AppServerInboundFrame.TurnFinished>().single()
                assertEquals(terminalStops.single().second, finished.stopReason, "$key: turn_finished disagrees with the stop delta")
                val stopIndex = received.indexOf(terminalStops.single().first)
                val finishedIndex = received.indexOfFirst { it.frame is AppServerInboundFrame.TurnFinished }
                assertTrue(finishedIndex > stopIndex, "$key: turn_finished arrived before the terminal stop delta")
            }
    }

    @Test
    fun strictModeApprovalIsAControlRequestAnsweredByTheApprovalRequestId() {
        val strict = rows.filter { it.str("capture") == "strict_approval" }
        val control = strict.map { it["frame"] }.filterIsInstance<JsonObject>().single { it.str("type") == "control_request" }
        val decoded = assertIs<AppServerInboundFrame.ControlRequest>(AppServerProtocol.decodeFrame(control.toString()).frame)
        val reply = strict.map { it["frame"] }.filterIsInstance<JsonObject>()
            .single { it.str("type") == "input" && it["payload"]!!.jsonObject.str("kind") == "approval_response" }
        val payload = assertIs<AppServerInputPayload.ApprovalResponse>(
            AppServerProtocol.json.decodeFromJsonElement(AppServerCommand.serializer(), reply).let { (it as AppServerCommand.Input).payload },
        )
        assertEquals(decoded.requestId, payload.requestId, "approval_response carries the control_request id, not the input's request_id")
        assertIs<AppServerApprovalResponseDecision.Deny>(payload.decision)
    }

    @Test
    fun envelopeSequenceIsMonotonicAndIdempotencyKeysAreUniquePerConnection() {
        rows.groupBy { it.str("capture") }.forEach { (capture, captureRows) ->
            val envelopes = captureRows.filter { it.str("direction") == "server_to_client" }
                .map { it["frame"]!!.jsonObject }
                .filter { it["event_seq"] != null }
            val seqs = envelopes.map { it["event_seq"]!!.jsonPrimitive.long }
            assertEquals(seqs.sorted(), seqs, "$capture: event_seq went backwards")
            val keys = envelopes.map { it.str("idempotency_key") }
            assertEquals(keys.size, keys.toSet().size, "$capture: duplicate idempotency_key")
        }
    }

    @Test
    fun httpDiscoveryAndLegacyChannelRejectionMatchTheProtocol() {
        val http = rows.filter { it.str("capture") == "standard" && it.str("direction") == "http" }
            .associateBy { it["request"]!!.jsonObject.str("path") }
        assertEquals(200, http.getValue("/readyz").int("status"))
        assertEquals(200, http.getValue("/healthz").int("status"))
        assertEquals(426, http.getValue("/ws?channel=stream").int("status"), "legacy split channels must be refused with 426")
        val info = assertIs<AppServerInboundFrame.AppServerInfoResponse>(
            AppServerProtocol.decodeFrame(http.getValue("/app-server-info")["body"].toString()).frame,
        )
        assertEquals(1, info.protocolVersion)
        assertFalse(info.info!!.splitChannels)
    }

    private fun JsonObject.str(key: String): String = (this[key] as JsonElement).jsonPrimitive.content
    private fun JsonObject.int(key: String): Int = (this[key] as JsonElement).jsonPrimitive.content.toInt()
}
