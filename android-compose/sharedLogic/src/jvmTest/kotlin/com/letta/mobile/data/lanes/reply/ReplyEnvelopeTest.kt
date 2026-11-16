package com.letta.mobile.data.lanes.reply

import com.letta.mobile.data.transport.iroh.IrohAgentMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyEnvelopeTest {
    @Test
    fun legacyEnvelopeBytesRemainUnchanged() {
        val message = IrohAgentMessage("sender", "recipient", "hello", "message", 1)
        val expected = """{"fromAgentId":"sender","toAgentId":"recipient","body":"hello","msgId":"message","ts":1,""" +
            """"conversationId":null,"lane":null,"correlation":null}"""
        assertEquals(expected, message.encode())
    }

    @Test
    fun replyFieldsSurviveTheActualEnvelopeCodec() {
        val wire = """
            {"fromAgentId":"sender","toAgentId":"recipient","body":"question","msgId":"message","ts":1,
            "correlation_id":"host-id","reply_to":{"correlation_id":"request-id","expires_at":1234}}
        """.trimIndent()
        val encoded = Json.parseToJsonElement(IrohAgentMessage.decode(wire).encode()).jsonObject
        val original = Json.parseToJsonElement(wire).jsonObject
        assertEquals(original["correlation_id"], encoded["correlation_id"])
        assertEquals(original["reply_to"], encoded["reply_to"])
    }
}
