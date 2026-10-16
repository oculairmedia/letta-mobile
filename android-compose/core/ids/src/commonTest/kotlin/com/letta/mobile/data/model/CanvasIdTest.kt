package com.letta.mobile.data.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CanvasIdTest {
    @Test
    fun canvasIdSerializesAsTheObjectStoredCanvasesHold() {
        val encoded = Json.encodeToString(CanvasId.serializer(), CanvasId("canvas-12345"))

        assertEquals("{\"value\":\"canvas-12345\"}", encoded)
        assertEquals(CanvasId("canvas-12345"), Json.decodeFromString(CanvasId.serializer(), encoded))
    }

    @Test
    fun canvasIdToStringIsItsValue() {
        assertEquals("canvas-12345", CanvasId("canvas-12345").toString())
    }

    @Test
    fun conversationCanvasIdIsPrefixPlusConversationId() {
        assertEquals(CanvasId("canvas-conversation-conv-1"), CanvasId.forConversation(ConversationId("conv-1")))
    }

    @Test
    fun generatedCanvasIdsArePrefixedAndDistinct() {
        val first = CanvasId.generate()
        val second = CanvasId.generate()

        assertTrue(first.value.startsWith(CanvasId.PREFIX))
        assertNotEquals(first, second)
    }

    @Test
    fun canvasActorIdSerializesAsItsBareString() {
        val encoded = Json.encodeToString(CanvasActorId.serializer(), CanvasActorId("agent-1"))

        assertEquals("\"agent-1\"", encoded)
        assertEquals(CanvasActorId("agent-1"), Json.decodeFromString(CanvasActorId.serializer(), encoded))
        assertEquals("agent-1", CanvasActorId("agent-1").toString())
    }

    @Test
    fun canvasElementNoteAndCheckpointIdsSerializeAsTheirBareStrings() {
        assertEquals("\"el-1\"", Json.encodeToString(CanvasElementId.serializer(), CanvasElementId("el-1")))
        assertEquals("\"doc-1\"", Json.encodeToString(CanvasNoteId.serializer(), CanvasNoteId("doc-1")))
        assertEquals("\"cp-1\"", Json.encodeToString(CanvasCheckpointId.serializer(), CanvasCheckpointId("cp-1")))
        assertEquals(CanvasNoteId("doc-1"), Json.decodeFromString(CanvasNoteId.serializer(), "\"doc-1\""))
        assertEquals("el-1", CanvasElementId("el-1").toString())
    }
}
