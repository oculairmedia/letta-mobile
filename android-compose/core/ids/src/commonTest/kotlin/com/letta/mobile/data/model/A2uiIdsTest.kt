package com.letta.mobile.data.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class A2uiIdsTest {
    @Test
    fun surfaceIdSerializesAsItsBareString() {
        val encoded = Json.encodeToString(A2uiSurfaceId.serializer(), A2uiSurfaceId("confirm-1"))

        assertEquals("\"confirm-1\"", encoded)
        assertEquals(A2uiSurfaceId("confirm-1"), Json.decodeFromString(A2uiSurfaceId.serializer(), encoded))
    }

    @Test
    fun componentIdSerializesAsItsBareString() {
        val encoded = Json.encodeToString(A2uiComponentId.serializer(), A2uiComponentId("cta"))

        assertEquals("\"cta\"", encoded)
        assertEquals(A2uiComponentId("cta"), Json.decodeFromString(A2uiComponentId.serializer(), encoded))
    }

    @Test
    fun idsToStringIsTheirValue() {
        assertEquals("confirm-1", A2uiSurfaceId("confirm-1").toString())
        assertEquals("cta", A2uiComponentId("cta").toString())
    }

    @Test
    fun blankIdsAreAllowedBecauseAgentOutputIsUntrusted() {
        assertEquals("", A2uiSurfaceId("").value)
        assertEquals("", A2uiComponentId("").value)
    }
}
