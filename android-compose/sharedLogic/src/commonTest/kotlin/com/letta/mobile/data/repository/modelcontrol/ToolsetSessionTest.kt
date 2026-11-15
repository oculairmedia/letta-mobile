package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.toolsets.ToolsetOption
import com.letta.mobile.data.toolsets.ToolsetRuntime
import com.letta.mobile.data.toolsets.ToolsetState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ToolsetSessionTest {
    @Test
    fun aHostReplacementRequiresReopeningThePicker() = runTest {
        val runtime = ToolsetRuntime("agent-1", "conv-1")
        val host = ToolsetState(listOf(ToolsetOption("auto", "Auto"), ToolsetOption("codex", "Codex")), "auto")
        var statuses = MutableStateFlow(mapOf(runtime to host))
        var calls = 0
        val session = ModelControlSession(AdminRpcInvoker { _, _ -> calls++; null }, oauthHttp = null, toolsetStates = { statuses })
        val picker = requireNotNull(session.toolsets(backgroundScope, runtime))
        statuses = MutableStateFlow(mapOf(runtime to host))
        picker.select("codex")
        runCurrent()
        assertEquals(0, calls)
        assertEquals("The host connection changed; reopen the toolset picker", picker.state.value.error)
    }

    @Test
    fun theSharedSessionTargetsOneConversationAndNeverTreatsTheAckAsAnEcho() = runTest {
        val runtime = ToolsetRuntime("agent-1", "conv-1")
        val host = ToolsetState(listOf(ToolsetOption("auto", "Auto"), ToolsetOption("codex", "Codex")), "auto")
        val statuses = MutableStateFlow(mapOf(runtime to host))
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val session = ModelControlSession(AdminRpcInvoker { method, params ->
            calls += method to params
            JsonObject(mapOf("current_toolset" to JsonPrimitive("codex")))
        }, oauthHttp = null, toolsetStates = { statuses })
        val picker = requireNotNull(session.toolsets(backgroundScope, runtime))
        picker.select("codex")
        picker.select("codex")
        runCurrent()
        assertEquals("toolset.update", calls.single().first)
        assertEquals(JsonObject(mapOf(
            "agent_id" to JsonPrimitive("agent-1"), "conversation_id" to JsonPrimitive("conv-1"),
            "toolset_preference" to JsonPrimitive("codex"),
        )), calls.single().second)
        assertEquals("auto", picker.state.value.preference)
        statuses.value = mapOf(runtime to host.copy(preference = "codex"))
        runCurrent()
        assertEquals("codex", picker.state.value.preference)
    }
}
