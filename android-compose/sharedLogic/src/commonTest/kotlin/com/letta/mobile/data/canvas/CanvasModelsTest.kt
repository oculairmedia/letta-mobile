package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CanvasModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    @Test
    fun canvasIdSerializationRoundTrip() {
        val id = CanvasId("canvas-12345")
        val serialized = json.encodeToString(CanvasId.serializer(), id)
        val deserialized = json.decodeFromString(CanvasId.serializer(), serialized)

        assertEquals(id, deserialized)
        assertEquals("canvas-12345", deserialized.value)
        assertEquals("canvas-12345", deserialized.toString())
    }

    @Test
    fun canvasDocumentSerializationRoundTrip() {
        val doc = CanvasDocument(
            id = CanvasId("canvas-999"),
            agentId = "agent-alpha",
            conversationId = "conv-beta",
            title = "System Architecture Diagram",
            revision = 42L,
            sceneJson = """{"bgColor":-1,"elements":[]}""",
            updatedAtEpochMs = 1716000000000L,
        )

        val serialized = json.encodeToString(CanvasDocument.serializer(), doc)
        val deserialized = json.decodeFromString(CanvasDocument.serializer(), serialized)

        assertEquals(doc, deserialized)
        assertEquals(CanvasId("canvas-999"), deserialized.id)
        assertEquals("agent-alpha", deserialized.agentId)
        assertEquals("conv-beta", deserialized.conversationId)
        assertEquals("System Architecture Diagram", deserialized.title)
        assertEquals(42L, deserialized.revision)
        assertEquals("""{"bgColor":-1,"elements":[]}""", deserialized.sceneJson)
        assertEquals(1716000000000L, deserialized.updatedAtEpochMs)
    }

    @Test
    fun canvasDocumentWithNullOptionalsSerializationRoundTrip() {
        val doc = CanvasDocument(
            id = CanvasId("canvas-minimal"),
            agentId = null,
            conversationId = null,
            title = "Scratchpad",
            revision = 1L,
            sceneJson = "",
            updatedAtEpochMs = 1000L,
        )

        val serialized = json.encodeToString(CanvasDocument.serializer(), doc)
        val deserialized = json.decodeFromString(CanvasDocument.serializer(), serialized)

        assertEquals(doc, deserialized)
        assertNull(deserialized.agentId)
        assertNull(deserialized.conversationId)
    }
}
