package com.letta.mobile.data.plugin.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The JSON-RPC 2.0 envelope of LCP wire v1 (letta-mobile-s416w.25): round trips, refusals and the size cap. */
class JsonRpcCodecTest {
    private val params = buildJsonObject { put("action", "start") }

    @Test
    fun everyMessageKindRoundTrips() {
        val messages = listOf(
            JsonRpcMessage.Request(JsonPrimitive(7), "action.invoke", params),
            JsonRpcMessage.Request(JsonPrimitive("a-1"), "plugin.health", JsonObject(emptyMap())),
            JsonRpcMessage.Notification("host.log", params),
            JsonRpcMessage.Success(JsonPrimitive(7), params),
            JsonRpcMessage.Failure(JsonPrimitive(7), JsonRpcError(LcpErrorCode.ACTION_FAILED, "no", buildJsonObject { put("code", "busy") })),
            JsonRpcMessage.Failure(null, JsonRpcError(LcpErrorCode.PARSE_ERROR, "not JSON")),
        )
        messages.forEach { message ->
            val text = JsonRpcCodec.encode(message)
            assertFalse('\n' in text, "an encoded message is one line")
            assertEquals(JsonRpcDecoding.Decoded(message), JsonRpcCodec.decode(text))
        }
    }

    @Test
    fun absentParamsReadAsAnEmptyObject() {
        val decoded = assertIs<JsonRpcDecoding.Decoded>(JsonRpcCodec.decode("""{"jsonrpc":"2.0","id":1,"method":"plugin.activate"}"""))
        assertEquals(JsonRpcMessage.Request(JsonPrimitive(1), "plugin.activate", JsonObject(emptyMap())), decoded.message)
    }

    @Test
    fun malformedMessagesAreRefusedWithTheirCode() {
        MALFORMED.forEach { (raw, code, answerable) ->
            val refused = assertIs<JsonRpcDecoding.Malformed>(JsonRpcCodec.decode(raw), raw)
            assertEquals(code, refused.error.code, raw)
            assertEquals(answerable, refused.answer != null, raw)
        }
    }

    @Test
    fun aRefusalKeepsTheIdItCouldRead() {
        val refused = assertIs<JsonRpcDecoding.Malformed>(JsonRpcCodec.decode("""{"jsonrpc":"1.0","id":"x","method":"plugin.health"}"""))
        assertEquals(JsonPrimitive("x"), refused.answer?.id)
        assertNull(assertIs<JsonRpcDecoding.Malformed>(JsonRpcCodec.decode("{")).answer?.id)
    }

    @Test
    fun aMessageOverFourMebibytesIsRefusedUnparsed() {
        val text = """{"jsonrpc":"2.0","method":"host.log","params":{"message":"${"x".repeat(LcpWire.MAX_MESSAGE_BYTES)}"}}"""
        val refused = assertIs<JsonRpcDecoding.Malformed>(JsonRpcCodec.decode(text))
        assertEquals(LcpErrorCode.MESSAGE_TOO_LARGE, refused.error.code)
    }

    @Test
    fun utf8SizeCountsEncodedBytes() {
        listOf("", "abc", "é", "€", "😀", "a😀é").forEach { text ->
            assertEquals(text.encodeToByteArray().size, JsonRpcCodec.utf8Size(text), text)
        }
    }

    @Test
    fun everyErrorCodeHasAMeaning() {
        assertTrue(LcpErrorCode.meanings.keys.containsAll(listOf(LcpErrorCode.PARSE_ERROR, LcpErrorCode.ACTION_FAILED, LcpErrorCode.UPLOAD_REFUSED)))
        assertEquals(LcpErrorCode.meanings.size, LcpErrorCode.meanings.keys.toSet().size)
    }

    private companion object {
        /** A raw text, the code it is refused with, and whether the refusal is answered. */
        val MALFORMED: List<Triple<String, Int, Boolean>> = listOf(
            Triple("not json", LcpErrorCode.PARSE_ERROR, true),
            Triple("""[{"jsonrpc":"2.0","id":1,"method":"plugin.health"}]""", LcpErrorCode.INVALID_REQUEST, true),
            Triple("\"just a string\"", LcpErrorCode.INVALID_REQUEST, true),
            Triple("""{"id":1,"method":"plugin.health"}""", LcpErrorCode.INVALID_REQUEST, true),
            Triple("""{"jsonrpc":"2.0","id":1,"method":42}""", LcpErrorCode.INVALID_REQUEST, true),
            Triple("""{"jsonrpc":"2.0","id":{"n":1},"method":"plugin.health"}""", LcpErrorCode.INVALID_REQUEST, true),
            Triple("""{"jsonrpc":"2.0","id":true,"method":"plugin.health"}""", LcpErrorCode.INVALID_REQUEST, true),
            Triple("""{"jsonrpc":"2.0","id":1,"method":"plugin.health","params":[1]}""", LcpErrorCode.INVALID_PARAMS, true),
            Triple("""{"jsonrpc":"2.0","method":"host.log","params":"x"}""", LcpErrorCode.INVALID_PARAMS, false),
            Triple("""{"jsonrpc":"2.0","id":1,"result":{},"error":{"code":1,"message":"x"}}""", LcpErrorCode.INVALID_REQUEST, false),
            Triple("""{"jsonrpc":"2.0","id":1,"error":{"code":"x","message":"x"}}""", LcpErrorCode.INVALID_REQUEST, false),
            Triple("""{"jsonrpc":"2.0","result":{}}""", LcpErrorCode.INVALID_REQUEST, false),
            Triple("""{"jsonrpc":"2.0","id":1}""", LcpErrorCode.INVALID_REQUEST, true),
        )
    }
}
