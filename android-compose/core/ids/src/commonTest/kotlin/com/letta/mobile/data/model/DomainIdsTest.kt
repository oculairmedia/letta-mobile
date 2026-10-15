package com.letta.mobile.data.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DomainIdsTest {
    @Test
    fun runIdSerializesAsItsBareString() {
        val encoded = Json.encodeToString(RunId.serializer(), RunId("run-123"))

        assertEquals("\"run-123\"", encoded)
        assertEquals(RunId("run-123"), Json.decodeFromString(RunId.serializer(), encoded))
    }

    @Test
    fun requestIdSerializesAsItsBareString() {
        val ids = listOf(RequestId("req-1"), RequestId("approval-2"))
        val encoded = Json.encodeToString(ListSerializer(RequestId.serializer()), ids)

        assertEquals("[\"req-1\",\"approval-2\"]", encoded)
        assertEquals(ids, Json.decodeFromString(ListSerializer(RequestId.serializer()), encoded))
    }

    @Test
    fun messageIdSerializesAsItsBareString() {
        val encoded = Json.encodeToString(MessageId.serializer(), MessageId("message-9"))

        assertEquals("\"message-9\"", encoded)
    }

    @Test
    fun pluginIdSerializesAsItsBareString() {
        val encoded = Json.encodeToString(PluginId.serializer(), PluginId("letta.chart"))

        assertEquals("\"letta.chart\"", encoded)
        assertEquals(PluginId("letta.chart"), Json.decodeFromString(PluginId.serializer(), encoded))
        assertEquals("letta.chart", PluginId("letta.chart").toString())
    }

    @Test
    fun toStringIsTheRawValue() {
        assertEquals("run-1", RunId("run-1").toString())
        assertEquals("req-1", RequestId("req-1").toString())
        assertEquals("message-1", MessageId("message-1").toString())
    }

    @Test
    fun blankRunIdIsRejected() {
        assertFailsWith<IllegalArgumentException> { RunId("") }
        assertFailsWith<IllegalArgumentException> { RunId("   ") }
    }

    @Test
    fun blankRequestIdIsRejected() {
        assertFailsWith<IllegalArgumentException> { RequestId("") }
        assertFailsWith<IllegalArgumentException> { RequestId("\t") }
    }

    @Test
    fun blankRunIdIsRejectedWhenDecoded() {
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString(RunId.serializer(), "\"\"") }
    }

    @Test
    fun equalValuesAreEqualIds() {
        assertEquals(RunId("run-1"), RunId("run-1"))
        assertEquals(RequestId("req-1").hashCode(), RequestId("req-1").hashCode())
    }
}
