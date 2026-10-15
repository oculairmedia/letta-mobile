package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasId
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

    /**
     * The canvas metadata the notebook and desktop stores persist (`Json.encodeToString` with the
     * default config): the id is an object, `{"value":…}`, since before CanvasId moved to core/ids
     * (letta-mobile-by4vm.99). Stored canvases must keep decoding, so the bytes may not change.
     */
    @Test
    fun canvasDocumentKeepsItsStoredBytes() {
        val doc = CanvasDocument(
            id = CanvasId("canvas-1"),
            agentId = "agent-a",
            conversationId = "conv-b",
            title = "T",
            revision = 3L,
            updatedAtEpochMs = 5L,
        )
        val stored = """{"id":{"value":"canvas-1"},"agentId":"agent-a","conversationId":"conv-b","title":"T","revision":3,"updatedAtEpochMs":5}"""

        assertEquals(stored, Json.encodeToString(CanvasDocument.serializer(), doc))
        assertEquals(doc, Json.decodeFromString(CanvasDocument.serializer(), stored))
    }
}
