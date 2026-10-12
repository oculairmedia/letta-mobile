package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.subagents.DurableSubagentRegistry
import com.letta.mobile.data.subagents.InMemorySubagentRegistryStore
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * letta-mobile-ulcg6 (RC4/RC5): the host keeps letta-code's native progress
 * (tool calls, tokens, duration, model) and a last-ACTIVITY time that only a
 * new tool call or lifecycle change moves. Frames are shaped like letta-code
 * 0.34.8 `buildSubagentSnapshot` output (snake_case, `tool_calls` objects with
 * `args` as JSON text).
 */
class SubagentNativeProgressTest {
    private var nowMs = 1_791_641_853_470L
    private val source = ControllerSubagentRegistrySource(
        DurableSubagentRegistry(store = InMemorySubagentRegistryStore(), clock = { nowMs }),
    )

    private fun subagent(toolCalls: Int, status: String = "running"): JsonObject = buildJsonObject {
        put("subagent_id", "subagent-1791641853470-1")
        put("subagent_type", "General-purpose")
        put("description", "Audit the registry")
        put("prompt", "secret prompt that must not leak")
        put("status", status)
        put("model", "claude-sonnet-5-5")
        put("is_background", true)
        put("silent", false)
        put("agent_url", CHILD_AGENT)
        put("tool_call_id", "toolu_dispatch")
        put("parent_agent_id", AGENT)
        put("parent_conversation_id", CONVERSATION)
        put("start_time", 1_791_641_853_470L)
        put("total_tokens", 1_200L * toolCalls)
        put("duration_ms", 0)
        putJsonArray("tool_calls") {
            repeat(toolCalls) { index ->
                addJsonObject {
                    put("id", "tc-$index")
                    put("name", if (index % 2 == 0) "Read" else "Bash")
                    put("args", if (index % 2 == 0) """{"file_path":"src/File$index.kt"}""" else """{"command":"git status"}""")
                }
            }
        }
    }

    private fun ingest(seq: Long, vararg subagents: JsonObject) = source.ingest(
        AppServerInboundFrame.UpdateSubagentState(
            runtime = AppServerRuntimeScope(agentId = AGENT, conversationId = CONVERSATION),
            eventSeq = seq,
            emittedAt = "t",
            idempotencyKey = "k-$seq",
            subagents = subagents.toList(),
        ),
    )

    @Test
    fun nativeToolCallsBecomeCountActivityAndUsage() = runTest {
        ingest(1, subagent(toolCalls = 3))

        val entry = source.list(CONVERSATION, includeTerminal = true).single()
        assertEquals(3, entry.toolCallCount)
        assertEquals(listOf("Read src/File0.kt", "Bash git status", "Read src/File2.kt"), entry.activity?.lines)
        assertEquals("claude-sonnet-5-5", entry.model)
        assertEquals(3_600L, entry.totalTokens)
        assertEquals(true, entry.isBackground)
        assertEquals(nowMs, entry.lastActivityAtMs)
        assertEquals(false, entry.activity?.lines.orEmpty().any { "secret" in it })
        // letta-code names the child by agent_url (the bare id for a local agent).
        assertEquals(CHILD_AGENT, entry.subagentAgentId)
    }

    @Test
    fun repeatedSnapshotIsNotActivityButANewToolCallIs() = runTest {
        ingest(1, subagent(toolCalls = 2))
        val startedAt = nowMs

        nowMs += 141_263L
        ingest(2, subagent(toolCalls = 2))
        assertEquals(startedAt, source.list(CONVERSATION, true).single().lastActivityAtMs)

        nowMs += 1_000L
        ingest(3, subagent(toolCalls = 3))
        assertEquals(nowMs, source.list(CONVERSATION, true).single().lastActivityAtMs)
    }

    @Test
    fun toolCallCountNeverGoesBackwardsAndTerminalIsActivity() = runTest {
        ingest(1, subagent(toolCalls = 4))
        nowMs += 5_000L
        ingest(2, subagent(toolCalls = 1, status = "success"))

        val entry = source.list(CONVERSATION, includeTerminal = true).single()
        assertEquals(4, entry.toolCallCount)
        assertEquals(nowMs, entry.lastActivityAtMs)
    }

    @Test
    fun hostWithoutNativeFieldsLeavesProgressUnknown() = runTest {
        ingest(
            1,
            buildJsonObject {
                put("tool_call_id", "toolu_old")
                put("status", "running")
            },
        )

        val entry = source.list(CONVERSATION, includeTerminal = true).single()
        assertNull(entry.toolCallCount)
        assertNull(entry.model)
        assertNull(entry.activity)
    }

    private companion object {
        const val AGENT = "agent-c356b54a-8b37-4d53-b9d0-b43164749b6f"
        const val CONVERSATION = "local-conv-517"
        const val CHILD_AGENT = "agent-local-1a9775a0-f2fb-49a3-9ed8-0e70cce1121e"
    }
}
