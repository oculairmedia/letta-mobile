package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * letta-mobile-zxk2n.1: letta-code 0.34.8 frames decode on a client whose contract baseline is
 * 0.34.8, and the 0.34 command additions encode in the exact upstream wire shape. Nothing here
 * may become a DecodeFailure.
 */
class LettaCode034CompatibilityTest {
    private fun decode(json: String) = AppServerProtocol.decodeFrame(json.trimIndent())

    private fun encode(command: AppServerCommand): JsonObject =
        AppServerProtocol.json.parseToJsonElement(AppServerProtocol.encodeCommand(command)).jsonObject

    private val runtime = AppServerRuntimeScope("agent-1", "conv-1")

    @Test
    fun turnFinishedCarriesItsTerminalConsumers() {
        val finished = assertIs<AppServerInboundFrame.TurnFinished>(decode(LettaCode0348Frames.TURN_FINISHED_WITH_CONSUMERS).frame)
        assertEquals(listOf("letta-mobile:device-a", "desktop:b"), finished.terminalConsumerIds)
        assertEquals("turn_finished:30:a", finished.idempotencyKey)
    }

    @Test
    fun aPre034TurnFinishedHasNoConsumers() {
        val finished = assertIs<AppServerInboundFrame.TurnFinished>(decode(LettaCode0336Frames.AGENT_FREE_TURN_FINISHED).frame)
        assertEquals(emptyList(), finished.terminalConsumerIds)
    }

    @Test
    fun theAckForAnAgentFreeTerminalSendsAnExplicitNullAgent() {
        val finished = assertIs<AppServerInboundFrame.TurnFinished>(
            decode(LettaCode0348Frames.AGENT_FREE_TURN_FINISHED_WITH_CONSUMER).frame,
        )
        val ack = encode(AppServerCommand.TurnFinishedAck.of(finished, consumerId = "letta-mobile:device-a"))
        // Upstream isRuntimeScope rejects an absent agent_id; only null or a non-empty id pass.
        assertEquals(
            """{"type":"turn_finished_ack","runtime":{"agent_id":null,"conversation_id":"conv-free"},""" +
                """"idempotency_key":"turn_finished:31:a","consumer_id":"letta-mobile:device-a"}""",
            ack.toString(),
        )
    }

    @Test
    fun theAckForAnAgentTerminalNamesTheAgent() {
        val finished = assertIs<AppServerInboundFrame.TurnFinished>(decode(LettaCode0348Frames.TURN_FINISHED_WITH_CONSUMERS).frame)
        val ack = encode(AppServerCommand.TurnFinishedAck.of(finished, consumerId = "desktop:b"))
        assertEquals("agent-1", ack["runtime"]!!.jsonObject["agent_id"]!!.jsonPrimitive.content)
        val decoded = AppServerProtocol.json.decodeFromJsonElement(AppServerCommand.serializer(), ack)
        assertEquals(AppServerCommand.TurnFinishedAck.of(finished, "desktop:b"), decoded)
    }

    @Test
    fun anInputCanNameATerminalConsumerAndPinItsToolset() {
        val input = encode(
            AppServerCommand.Input(
                runtime = runtime,
                payload = AppServerInputPayload.CreateMessage(
                    messages = listOf(AppServerInputMessage.userText("hi", clientMessageId = "cm-1")),
                    clientPreferences = AppServerClientPreferences(AppServerToolsetPreference(listOf("Read", "Grep"))),
                ),
                terminalConsumerId = "letta-mobile:device-a",
            ),
        )
        assertEquals("letta-mobile:device-a", input["terminal_consumer_id"]!!.jsonPrimitive.content)
        assertEquals(
            """{"toolset":{"include":["Read","Grep"]}}""",
            input["payload"]!!.jsonObject["client_preferences"].toString(),
        )
    }

    @Test
    fun omittedPreferencesInheritAndAnEmptyObjectClears() {
        val inherit = encode(
            AppServerCommand.Input(runtime, AppServerInputPayload.CreateMessage(listOf(AppServerInputMessage.userText("a")))),
        )
        assertFalse("client_preferences" in inherit["payload"]!!.jsonObject)
        assertFalse("terminal_consumer_id" in inherit)

        val clear = encode(
            AppServerCommand.Input(
                runtime,
                AppServerInputPayload.CreateMessage(
                    messages = listOf(AppServerInputMessage.userText("b")),
                    clientPreferences = AppServerClientPreferences.CLEAR,
                ),
            ),
        )
        assertEquals("{}", clear["payload"]!!.jsonObject["client_preferences"].toString())
    }

    @Test
    fun aTeleportContinuationCarriesPreferences() {
        val input = encode(
            AppServerCommand.Input(
                runtime = runtime,
                payload = AppServerInputPayload.TeleportContinue(
                    teleportId = "tp-1",
                    source = AppServerTeleportSource(deviceId = "dev-1", connectionName = "laptop"),
                    clientPreferences = AppServerClientPreferences(AppServerToolsetPreference(listOf("Read"))),
                ),
            ),
        )
        assertEquals(
            """{"kind":"teleport_continue","teleport_id":"tp-1","source":{"device_id":"dev-1",""" +
                """"connection_name":"laptop"},"client_preferences":{"toolset":{"include":["Read"]}}}""",
            input["payload"].toString(),
        )
    }

    @Test
    fun terminalConsumerIdsFollowTheUpstreamGrammar() {
        assertTrue(AppServerTerminalConsumerId.isValid("letta-mobile:device-a"))
        assertTrue(AppServerTerminalConsumerId.isValid("a.b_c~d%2F"))
        assertFalse(AppServerTerminalConsumerId.isValid(""))
        assertFalse(AppServerTerminalConsumerId.isValid("has space"))
        assertFalse(AppServerTerminalConsumerId.isValid("slash/no"))
        assertTrue(AppServerTerminalConsumerId.isValid("x".repeat(AppServerTerminalConsumerId.MAX_LENGTH)))
        assertFalse(AppServerTerminalConsumerId.isValid("x".repeat(AppServerTerminalConsumerId.MAX_LENGTH + 1)))
    }

    @Test
    fun theAckReplaysSafelyAfterADisconnect() {
        val ack = AppServerCommand.TurnFinishedAck(AppServerTerminalAckScope(null, "c"), "k", "consumer")
        assertTrue(AppServerCommandRetryClass.isRetryableAfterAmbiguousDisconnect(ack))
    }

    @Test
    fun workflowProgressIsTyped() {
        val status = assertIs<AppServerInboundFrame.UpdateDeviceStatus>(decode(LettaCode0348Frames.DEVICE_STATUS_WORKFLOW_PROGRESS).frame)
        val snapshot = status.snapshot
        val (withProgress, without) = snapshot.backgroundProcesses
        val progress = withProgress.progress!!
        assertEquals("workflow", withProgress.type)
        assertEquals(AppServerWorkflowProgressCounts(5, 2, 1, 2, 48_000), progress.counts)
        assertEquals(listOf("Explore", "Write"), progress.phases.map { it.title })
        assertEquals(2, progress.phases[1].counts.agentsRunning)
        assertNull(without.progress)
        assertEquals("memory_palace", snapshot.experiments.single().id)
    }

    @Test
    fun theUntypedNewFramesStayUnknownNotFailures() {
        listOf(LettaCode0348Frames.GET_EXPERIMENTS_RESPONSE, LettaCode0348Frames.TELEPORT_READY).forEach { json ->
            assertIs<AppServerInboundFrame.Unknown>(decode(json).frame)
        }
    }

    @Test
    fun everyFixtureDecodesToATypedOrUnknownFrame() {
        listOf(
            LettaCode0348Frames.TURN_FINISHED_WITH_CONSUMERS,
            LettaCode0348Frames.AGENT_FREE_TURN_FINISHED_WITH_CONSUMER,
            LettaCode0348Frames.DEVICE_STATUS_WORKFLOW_PROGRESS,
            LettaCode0348Frames.GET_EXPERIMENTS_RESPONSE,
            LettaCode0348Frames.TELEPORT_READY,
        ).forEach { json ->
            val frame = decode(json).frame
            assertFalse(frame is AppServerInboundFrame.DecodeFailure, "decode failed: ${(frame as? AppServerInboundFrame.DecodeFailure)?.diagnostic}")
        }
    }
}
