package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.subagents.DurableSubagentRegistry
import com.letta.mobile.data.subagents.InMemorySubagentRegistryStore
import com.letta.mobile.data.subagents.SubagentChipState
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** A task completion that overtakes the dispatch receipt naming its task. */
class SubagentEarlyCompletionTest {
    private fun stream(agent: String, conversation: String, raw: String) = AppServerInboundFrame.StreamDelta(
        runtime = AppServerRuntimeScope(agentId = agent, conversationId = conversation),
        eventSeq = 1, emittedAt = "t", idempotencyKey = raw,
        delta = kotlinx.serialization.json.Json.parseToJsonElement(raw),
    )

    // TLA+ EventuallyTerminal: the completion can reach the parent stream
    // before the dispatch receipt that carries its task id. It must be applied when the receipt arrives,
    // and later stale RUNNING snapshots must not keep the chip alive.
    private val earlyDispatch = """{
        "message_type":"tool_call_message",
        "tool_call":{"name":"Agent","tool_call_id":"call-28","arguments":{"description":"Audit logs"}}
    }"""

    private fun earlyReceipt(task: String) = buildJsonObject {
        put("message_type", "tool_return_message")
        put("tool_call_id", "call-28")
        put("tool_return", "Task running in background with task ID: $task\nAgent ID: agent-child-28\n")
    }.toString()

    private fun earlyNotification(body: String) = stream("parent", "conversation", buildJsonObject {
        put("message_type", "user_message")
        put("content", body)
    }.toString())

    private fun staleRunningSnapshot(seq: Long) = AppServerInboundFrame.UpdateSubagentState(
        runtime = AppServerRuntimeScope(agentId = "parent", conversationId = "conversation"),
        eventSeq = seq, emittedAt = "late", idempotencyKey = "stale-$seq",
        subagents = listOf(buildJsonObject {
            put("tool_call_id", "call-28")
            put("status", "running")
        }),
    )

    @Test
    fun completionBeforeReceiptIsAppliedWhenTheReceiptSuppliesItsTaskId() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.ingest(stream("parent", "conversation", earlyDispatch))
        source.ingest(earlyNotification(
            "<task-notification><task-id>task_28</task-id><status>completed</status>" +
                "<usage>duration_ms: 4200</usage></task-notification>",
        ))
        source.ingest(stream("parent", "conversation", earlyReceipt("task_28")))
        repeat(3) { source.ingest(staleRunningSnapshot(10L + it)) }

        val record = source.registry.record("conversation", "parent", "call-28")!!
        assertEquals(SubagentChipState.COMPLETED, record.state)
        assertEquals("task_28", record.taskId)
        assertEquals(4_200L, record.toEntry().durationMs)
    }

    @Test
    fun earlyCompletionSurvivesARestartBeforeTheReceipt() = runTest {
        val store = InMemorySubagentRegistryStore()
        val first = ControllerSubagentRegistrySource(DurableSubagentRegistry(store))
        first.ingest(stream("parent", "conversation", earlyDispatch))
        first.ingest(earlyNotification("<task-notification><task-id>task_28</task-id><status>failed</status></task-notification>"))

        val restarted = ControllerSubagentRegistrySource(DurableSubagentRegistry(store))
        restarted.ingest(stream("parent", "conversation", earlyReceipt("task_28")))
        restarted.ingest(staleRunningSnapshot(20))

        assertEquals(SubagentChipState.FAILED, restarted.registry.record("conversation", "parent", "call-28")!!.state)
    }

    @Test
    fun earlyCompletionForAnotherTaskOrWithoutIdentityFailsClosed() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.ingest(stream("parent", "conversation", earlyDispatch))
        source.ingest(earlyNotification("<task-notification><task-id>task_other</task-id><status>completed</status></task-notification>"))
        // A notification naming only a title is never correlation evidence.
        source.ingest(earlyNotification("<task-notification><summary>Audit logs</summary><status>completed</status></task-notification>"))
        source.ingest(stream("parent", "conversation", earlyReceipt("task_28")))

        assertEquals(SubagentChipState.RUNNING, source.registry.record("conversation", "parent", "call-28")!!.state)
    }
}
