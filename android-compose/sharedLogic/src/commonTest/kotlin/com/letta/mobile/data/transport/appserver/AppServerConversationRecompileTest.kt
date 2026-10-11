package com.letta.mobile.data.transport.appserver

import com.letta.mobile.data.chat.recompile.ConversationRecompileRequest
import com.letta.mobile.data.repository.appserver.AppServerLocalAdminGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * letta-mobile-zxk2n.5 (G92): `conversation_recompile` wire shape against letta-code's
 * `ConversationRecompileCommand` / `conversation_recompile_response` (protocol_v2.ts).
 */
class AppServerConversationRecompileTest {
    @Test
    fun theAgentTravelsInTheBodyAndUnsetOptionsAreNotSent() {
        val encoded = encode(
            AppServerConversationRecompile("rc-1", "conv-1", AppServerConversationRecompileBody(agentId = "agent-1")),
        )

        assertEquals("conversation_recompile", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("conv-1", encoded["conversation_id"]?.jsonPrimitive?.content)
        val body = encoded.getValue("body").jsonObject
        assertEquals("agent-1", body["agent_id"]?.jsonPrimitive?.content)
        assertFalse("dry_run" in body, "unset options are not sent: $body")
    }

    @Test
    fun theResponseIsReadFromItsRawEnvelope() {
        val ok = AppServerProtocol.decodeFrame("""{"type":"conversation_recompile_response","request_id":"rc-1","success":true,"result":"prompt"}""")
        assertEquals(AppServerConversationRecompileResponse("rc-1", true, null), AppServerConversationRecompileResponse.from(ok.frame))

        val failed = AppServerProtocol.decodeFrame(
            """{"type":"conversation_recompile_response","request_id":"rc-2","success":false,"result":null,"error":"Prompt recompile is not supported by this backend yet"}""",
        )
        assertEquals("Prompt recompile is not supported by this backend yet", AppServerConversationRecompileResponse.from(failed.frame)?.error)

        val other = AppServerProtocol.decodeFrame("""{"type":"conversation_fork_response","request_id":"x","success":true}""")
        assertNull(AppServerConversationRecompileResponse.from(other.frame))
    }

    @Test
    fun aRecompileIsNeverReplayedBlindly() {
        assertIs<AppServerCommandRetryClass.AmbiguousMutation>(AppServerCommandRetryClass.of(AppServerConversationRecompile("rc-1", "conv-1")))
    }

    @Test
    fun theGatewaySendsTheCommandAndSurfacesTheServersRefusal() = runTest(UnconfinedTestDispatcher()) {
        val transport = AnsweringTransport()
        val gateway = AppServerLocalAdminGateway(DefaultAppServerClient(transport, parentScope = backgroundScope)) { "rc-$it" }

        transport.success = true
        gateway.recompileConversation(ConversationRecompileRequest("conv-1", "agent-1"))
        val sent = transport.sent.single() as AppServerConversationRecompile
        assertEquals("conv-1", sent.conversationId)
        assertEquals("agent-1", sent.body?.agentId)

        transport.success = false
        val refused = assertFailsWith<IllegalStateException> {
            gateway.recompileConversation(ConversationRecompileRequest("conv-1", "agent-1"))
        }
        assertEquals("not supported here", refused.message)
    }

    private fun encode(command: AppServerCommand): JsonObject =
        AppServerProtocol.json.parseToJsonElement(AppServerProtocol.encodeCommand(command)).jsonObject

    private class AnsweringTransport : AppServerTransport {
        override val controlFrames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 8)
        override val streamFrames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 8)
        override val isConnected = MutableStateFlow(true)
        val sent = mutableListOf<AppServerCommand>()
        var success = true

        override suspend fun sendControl(command: AppServerCommand) {
            sent += command
            val id = (command as AppServerConversationRecompile).requestId
            val answer = if (success) {
                """{"type":"conversation_recompile_response","request_id":"$id","success":true,"result":"p"}"""
            } else {
                """{"type":"conversation_recompile_response","request_id":"$id","success":false,"result":null,"error":"not supported here"}"""
            }
            controlFrames.emit(AppServerProtocol.decodeFrame(answer))
        }
    }
}
