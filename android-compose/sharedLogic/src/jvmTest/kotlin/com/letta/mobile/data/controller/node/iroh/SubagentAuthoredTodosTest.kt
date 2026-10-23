package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentTodoProgressWire
import com.letta.mobile.data.model.todoProgress
import com.letta.mobile.data.subagents.DurableSubagentRegistry
import com.letta.mobile.data.subagents.InMemorySubagentRegistryStore
import com.letta.mobile.data.subagents.SubagentChipRecord
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the real cumulative native snapshot shape through the production frame decoder. */
class SubagentAuthoredTodosTest {
    private val fixture = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/subagents/todo-snapshot.json")).readText(),
    ).jsonObject
    private val frame = AppServerProtocol.decodeFrame(fixture.getValue("frame").toString()).frame
        as AppServerInboundFrame.UpdateSubagentState

    private fun source() = ControllerSubagentRegistrySource(DurableSubagentRegistry(clock = { 1_000L }))

    private fun updated(vararg calls: JsonObject, status: String = "running") = frame.copy(
        eventSeq = frame.eventSeq + 1,
        subagents = listOf(JsonObject(frame.subagents.first() + mapOf(
            "status" to JsonPrimitive(status),
            "tool_calls" to JsonArray((frame.subagents.first()["tool_calls"] as JsonArray) + calls),
        ))),
    )

    private fun call(arguments: String, name: String = "TodoWrite") = buildJsonObject {
        put("id", "todo-${arguments.hashCode()}")
        put("name", name)
        put("args", arguments)
    }

    @Test
    fun nativeAuthoredPlanDrivesListChecklistPushAndReplayWithoutOpeningSheet() = runTest {
        val source = source()
        val changed = mutableListOf<SubagentConversationKey>()
        source.changeListener = SubagentRegistryChangeListener { changed += it }
        source.ingestReceived(AppServerProtocol.decodeFrame(fixture.getValue("frame").toString()))

        val entry = source.list(CONVERSATION, true).first { it.toolCallId == "dispatch-1" }
        assertEquals(SubagentTodoProgressWire(2, 3), entry.todoProgress)
        assertEquals("running", entry.status)
        val sheet = source.todos(CONVERSATION, entry.toolCallId)!!
        assertTrue(sheet.todosFound)
        assertEquals(entry, sheet.subagent)
        assertEquals(entry.todoProgress, sheet.todos.todoProgress())
        assertEquals("Running tests", sheet.todos.last().activeForm)
        assertEquals(entry, source.pushSnapshot(SubagentConversationKey("parent-1", CONVERSATION), 1_000L).first())
        assertEquals(entry, source.replaySnapshot(CONVERSATION).first())
        assertEquals(listOf(SubagentConversationKey("parent-1", CONVERSATION)), changed)
        val sibling = source.todos(CONVERSATION, "dispatch-2")!!
        assertFalse(sibling.todosFound)
        assertTrue(sibling.todos.isEmpty())
        assertNull(sibling.subagent.todoProgress)
        assertEquals(1, sibling.subagent.toolCallCount)
    }

    @Test
    fun plansReplaceRatherThanAccumulateAndCompletedPlanDoesNotExitWorker() = runTest {
        val source = source()
        source.ingest(frame)
        source.ingest(updated(call("""{"plan":[{"step":"Verify","status":"completed"}]}""", "update_plan")))
        val sheet = source.todos(CONVERSATION, "dispatch-1")!!
        assertEquals(listOf("Verify"), sheet.todos.map { it.content })
        assertEquals(SubagentTodoProgressWire(1, 1), sheet.subagent.todoProgress)
        assertEquals("running", sheet.subagent.status)
        assertEquals(1, source.list(CONVERSATION, false).count { it.toolCallId == "dispatch-1" })
    }

    @Test
    fun reusedChildIdentityDoesNotSharePlansAcrossDispatchesAndActivityDoesNotAdvancePlan() = runTest {
        val source = source()
        source.ingest(frame.copy(subagents = listOf(frame.subagents.first())))
        source.ingest(frame.copy(subagents = listOf(JsonObject(frame.subagents.first() + mapOf(
            "tool_call_id" to JsonPrimitive("dispatch-2"), "tool_calls" to JsonArray(emptyList()),
        )))))
        source.ingest(updated(call("""{"command":"git status"}""", "Bash")))
        assertEquals(1, source.list(CONVERSATION, true).map { it.subagentAgentId }.distinct().size)
        assertEquals(SubagentTodoProgressWire(2, 3), source.todos(CONVERSATION, "dispatch-1")!!.subagent.todoProgress)
        assertTrue(source.todos(CONVERSATION, "dispatch-2")!!.todos.isEmpty())
        assertNull(source.todos(CONVERSATION, "dispatch-2")!!.subagent.todoProgress)
    }

    @Test
    fun replayAndRestartPreserveNewestPlanEvenWhenEventSequenceRestarts() = runTest {
        val source = source()
        source.ingest(frame)
        val newer = updated(call("""{"todos":[{"content":"New scope","status":"pending","activeForm":"Working"}]}"""))
        source.ingest(newer)
        source.ingest(frame)
        source.ingest(newer)
        val serialized = Json.encodeToString(source.registry.replaySnapshot(CONVERSATION))
        val records = Json.decodeFromString<List<SubagentChipRecord>>(serialized)
        val restarted = ControllerSubagentRegistrySource(
            DurableSubagentRegistry(InMemorySubagentRegistryStore(records), clock = { 1_000L }),
        )
        restarted.ingest(frame.copy(eventSeq = 1))
        assertEquals(source.todos(CONVERSATION, "dispatch-1"), restarted.todos(CONVERSATION, "dispatch-1"))
        assertEquals("New scope", restarted.todos(CONVERSATION, "dispatch-1")!!.todos.single().content)
        val next = newer.subagents.single()
        val history = next["tool_calls"] as JsonArray
        restarted.ingest(newer.copy(eventSeq = 2, subagents = listOf(JsonObject(next + (
            "tool_calls" to JsonArray(history + call("""{"todos":[]}"""))
        )))))
        assertEquals(emptyList(), restarted.todos(CONVERSATION, "dispatch-1")!!.todos)
    }

    @Test
    fun emptyPlansClearProgressAndMalformedUpdatesLeaveLastValidPlanIntact() = runTest {
        val source = source()
        source.ingest(frame)
        source.ingest(updated(call("""{"todos":[{"content":"partial"}]}""")))
        assertEquals(SubagentTodoProgressWire(2, 3), source.todos(CONVERSATION, "dispatch-1")!!.subagent.todoProgress)
        source.ingest(updated(call("""{"todos":[]}""")))
        val empty = source.todos(CONVERSATION, "dispatch-1")!!
        assertTrue(empty.todosFound)
        assertTrue(empty.todos.isEmpty())
        assertNull(empty.subagent.todoProgress)
        source.ingest(frame)
        assertTrue(source.todos(CONVERSATION, "dispatch-1")!!.todos.isEmpty())
    }

    @Test
    fun successfulFailedAndCancelledWorkersRetainTheirIncompleteOrAbsentPlan() = runTest {
        for (status in listOf("success", "error", "cancelled")) {
            val source = source()
            source.ingest(frame)
            source.ingest(updated(status = status))
            val sheet = source.todos(CONVERSATION, "dispatch-1")!!
            assertEquals(SubagentTodoProgressWire(2, 3), sheet.subagent.todoProgress)
            assertEquals(sheet.subagent.todoProgress, sheet.todos.todoProgress())
            source.ingest(frame.copy(subagents = listOf(JsonObject(frame.subagents.last() + ("status" to JsonPrimitive(status))))))
            assertNull(source.todos(CONVERSATION, "dispatch-2")!!.subagent.todoProgress)
            assertTrue(source.todos(CONVERSATION, "dispatch-2")!!.todos.isEmpty())
        }
    }

    @Test
    fun parentPlansAndOtherParentsCannotWriteChildPlanAndAmbiguousDetailsFailClosed() = runTest {
        val source = source()
        source.ingest(frame)
        source.ingest(AppServerInboundFrame.StreamDelta(
            runtime = frame.runtime, eventSeq = 51, emittedAt = "t", idempotencyKey = "parent-plan",
            delta = buildJsonObject {
                put("message_type", "tool_call_message")
                put("tool_call", buildJsonObject {
                    put("tool_call_id", "parent-todo")
                    put("name", "TodoWrite")
                    put("arguments", """{"todos":[]}""")
                })
            },
        ))
        assertEquals(SubagentTodoProgressWire(2, 3), source.todos(CONVERSATION, "dispatch-1")!!.subagent.todoProgress)
        val other = frame.copy(runtime = AppServerRuntimeScope("parent-2", CONVERSATION))
        source.ingest(other.copy(subagents = listOf(frame.subagents.last())))
        val parentOne = source.pushSnapshot(SubagentConversationKey("parent-1", CONVERSATION), 1_000L)
        val parentTwo = source.pushSnapshot(SubagentConversationKey("parent-2", CONVERSATION), 1_000L)
        assertEquals(listOf("dispatch-1", "dispatch-2"), parentOne.map { it.toolCallId })
        assertNull(parentTwo.single().todoProgress)
        assertNull(source.todos(CONVERSATION, "dispatch-2"))
        assertEquals(SubagentTodoProgressWire(2, 3), source.todos(CONVERSATION, "dispatch-1")!!.subagent.todoProgress)
        assertNull(source.todos("other-conversation", "dispatch-1"))
    }

    private companion object {
        const val CONVERSATION = "parent-conversation"
    }
}
