package com.letta.mobile.data.model.view

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeViewIdsTest {
    @Test
    fun viewIdentityKeepsItsPersistedBareStringShape() {
        val encoded = Json.encodeToString(RuntimeViewId.serializer(), RuntimeViewId("reader.detail"))
        assertEquals("\"reader.detail\"", encoded)
        assertEquals(RuntimeViewId("reader.detail"), Json.decodeFromString(RuntimeViewId.serializer(), encoded))
    }

    @Test
    fun mountIdentityKeepsItsPersistedBareStringShape() {
        val encoded = Json.encodeToString(RuntimeViewMountId.serializer(), RuntimeViewMountId("workspace.detail"))
        assertEquals("\"workspace.detail\"", encoded)
        assertEquals(RuntimeViewMountId("workspace.detail"), Json.decodeFromString(RuntimeViewMountId.serializer(), encoded))
    }
    @Test
    fun blankViewIdentityIsRejectedAtConstructionAndDecode() {
        listOf("", "  ").forEach { raw ->
            assertFailsWith<IllegalArgumentException> { RuntimeViewId(raw) }
            assertFailsWith<IllegalArgumentException> { Json.decodeFromString(RuntimeViewId.serializer(), "\"$raw\"") }
        }
    }

    @Test
    fun blankMountIdentityIsRejectedAtConstructionAndDecode() {
        listOf("", "  ").forEach { raw ->
            assertFailsWith<IllegalArgumentException> { RuntimeViewMountId(raw) }
            assertFailsWith<IllegalArgumentException> { Json.decodeFromString(RuntimeViewMountId.serializer(), "\"$raw\"") }
        }
    }

}
