package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * letta-mobile-bzvro.15 (F15): `conversation_fork` wire shape against letta-code's
 * `ConversationForkCommand` / `ConversationForkResponseMessage` (protocol_v2.ts).
 */
class AppServerConversationForkTest {
    @Test
    fun forkOptionsTravelInTheBodyNotAtTheTopLevel() {
        val encoded = encode(
            AppServerConversationFork(
                requestId = "fork-1",
                conversationId = "conv-1",
                body = AppServerConversationForkBody(agentId = "agent-1", messageId = "message-9"),
            ),
        )

        assertEquals("conversation_fork", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("fork-1", encoded["request_id"]?.jsonPrimitive?.content)
        assertEquals("conv-1", encoded["conversation_id"]?.jsonPrimitive?.content)
        val body = encoded.getValue("body").jsonObject
        assertEquals("message-9", body["message_id"]?.jsonPrimitive?.content)
        assertEquals("agent-1", body["agent_id"]?.jsonPrimitive?.content)
        assertFalse("hidden" in body, "unset options are not sent: $body")
        assertFalse("message_id" in encoded, "a top-level message_id is ignored upstream: $encoded")
    }

    @Test
    fun aWholeConversationForkSendsNoBody() {
        val encoded = encode(AppServerConversationFork(requestId = "fork-2", conversationId = "conv-1"))
        assertFalse("body" in encoded)
    }

    @Test
    fun theResponseIsReadFromItsRawEnvelope() {
        val received = AppServerProtocol.decodeFrame(
            """{"type":"conversation_fork_response","request_id":"fork-1","success":true,"conversation":{"id":"conv-2"}}""",
        )
        val response = AppServerConversationForkResponse.from(received.frame)

        assertEquals(AppServerConversationForkResponse("fork-1", true, "conv-2", null), response)
        assertEquals(AppServerChannel.Control, received.channel)
    }

    @Test
    fun aFailedForkCarriesItsError() {
        val received = AppServerProtocol.decodeFrame(
            """{"type":"conversation_fork_response","request_id":"fork-1","success":false,"conversation":null,"error":"Message not found"}""",
        )
        val response = AppServerConversationForkResponse.from(received.frame)

        assertEquals(false, response?.success)
        assertNull(response?.conversationId)
        assertEquals("Message not found", response?.error)
    }

    @Test
    fun otherFramesAreNotForkResponses() {
        val received = AppServerProtocol.decodeFrame("""{"type":"conversation_recompile_response","request_id":"x","success":true}""")
        assertNull(AppServerConversationForkResponse.from(received.frame))
    }

    @Test
    fun aForkIsNeverReplayedBlindlyAfterADisconnect() {
        val retry = AppServerCommandRetryClass.of(AppServerConversationFork("fork-1", "conv-1"))
        assertIs<AppServerCommandRetryClass.AmbiguousMutation>(retry)
    }

    @Test
    fun theClientCorrelatesTheResponseByRequestId() = runTest(UnconfinedTestDispatcher()) {
        val transport = ForkTransport()
        val client = DefaultAppServerClient(transport, parentScope = backgroundScope)
        transport.answer = { command ->
            val fork = command as AppServerConversationFork
            """{"type":"conversation_fork_response","request_id":"${fork.requestId}","success":true,"conversation":{"id":"conv-forked"}}"""
        }

        val response = client.conversationFork(
            AppServerConversationFork("fork-7", "conv-1", AppServerConversationForkBody(messageId = "m-1")),
        )

        assertTrue(response.success)
        assertEquals("conv-forked", response.conversationId)
        assertEquals(listOf("fork-7"), transport.sent.map { (it as AppServerConversationFork).requestId })
    }

    private fun encode(command: AppServerCommand): JsonObject =
        AppServerProtocol.json.parseToJsonElement(AppServerProtocol.encodeCommand(command)).jsonObject

    private class ForkTransport : AppServerTransport {
        override val controlFrames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 8)
        override val streamFrames = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 8)

        // A live connection: the client's disconnect watcher waits on it instead of completing.
        override val isConnected = kotlinx.coroutines.flow.MutableStateFlow(true)
        val sent = mutableListOf<AppServerCommand>()
        var answer: (AppServerCommand) -> String = { error("no answer") }

        override suspend fun sendControl(command: AppServerCommand) {
            sent += command
            controlFrames.emit(AppServerProtocol.decodeFrame(answer(command)))
        }
    }
}
