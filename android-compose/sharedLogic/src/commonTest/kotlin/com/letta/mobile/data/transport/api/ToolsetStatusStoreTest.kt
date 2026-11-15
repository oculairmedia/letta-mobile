package com.letta.mobile.data.transport.api

import com.letta.mobile.data.toolsets.ToolsetRuntime
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ToolsetStatusStoreTest {
    @Test
    fun projectsChoicesAndPreferenceRatherThanTheResolvedPreset() {
        val store = ToolsetStatusStore()
        store.observe(status(), generation = 2)
        val state = store.states.value.getValue(ToolsetRuntime("agent-1", "conv-1"))
        assertEquals(listOf("auto", "codex"), state.available.map { it.id })
        assertEquals(listOf("Automatic", "Codex tools"), state.available.map { it.displayName })
        assertEquals("auto", state.preference)
        assertNull(store.states.value[ToolsetRuntime("agent-1", "other")])
        store.clear(generation = 3)
        assertTrue(store.states.value.isEmpty())
        store.observe(status(generation = 1), generation = 2)
        store.observe(status(generation = 2), generation = 2)
        assertTrue(store.states.value.isEmpty(), "a prior connection cannot repopulate cleared choices")
        store.observe(status(generation = 4), generation = 4)
        assertEquals("auto", store.states.value[ToolsetRuntime("agent-1", "conv-1")]?.preference)
    }

    @Test
    fun anOmittedPreferenceIsUnknownAndAnEmptyCatalogRemovesPriorChoices() {
        val store = ToolsetStatusStore()
        store.observe(status())
        store.observe(status(payload = "{}"))
        val state = store.states.value.getValue(ToolsetRuntime("agent-1", "conv-1"))
        assertTrue(state.available.isEmpty())
        assertNull(state.preference)
    }

    private fun status(generation: Long = 2, payload: String = PAYLOAD) = AppServerReceivedFrame(
        channel = AppServerChannel.Stream,
        frame = AppServerInboundFrame.UpdateDeviceStatus(
            runtime = AppServerRuntimeScope("agent-1", "conv-1"),
            eventSeq = 1, emittedAt = "2026-11-15T00:00:00Z", idempotencyKey = "status-1",
            deviceStatus = Json.parseToJsonElement(payload).jsonObject,
        ),
        raw = JsonObject(emptyMap()), connectionGeneration = generation,
    )

    private companion object {
        const val PAYLOAD = """{"current_toolset":"codex","current_toolset_preference":"auto",
            "available_toolsets":[{"id":"auto","display_name":"Automatic"},{"id":"codex","label":"Codex tools"}]}"""
    }
}
