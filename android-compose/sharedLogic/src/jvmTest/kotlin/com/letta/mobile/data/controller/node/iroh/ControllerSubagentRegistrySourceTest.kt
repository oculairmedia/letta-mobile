package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.numberOrNull

import com.letta.mobile.util.textOrNull

import com.letta.mobile.data.model.SubagentActivitySnapshot
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.subagents.DurableSubagentRegistry
import com.letta.mobile.data.subagents.InMemorySubagentRegistryStore
import com.letta.mobile.data.subagents.SubagentChipSource
import com.letta.mobile.data.subagents.SubagentChipState
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControllerSubagentRegistrySourceTest {
    private var ownedTelemetryHistory: AutoCloseable? = null

    @kotlin.test.BeforeTest
    fun installOwnedTelemetryHistory() {
        ownedTelemetryHistory = com.letta.mobile.util.Telemetry.installRecorder(com.letta.mobile.util.TelemetryRecorder())
    }

    @kotlin.test.AfterTest
    fun detachOwnedTelemetryHistory() {
        ownedTelemetryHistory?.close()
    }

    private val codexChild = "codex_01a1686b-8ec9-7f72-8e79-bd42f1ff3237"

    private fun stream(agent: String, conversation: String, raw: String) = AppServerInboundFrame.StreamDelta(
        runtime = AppServerRuntimeScope(agentId = agent, conversationId = conversation),
        eventSeq = 1, emittedAt = "t", idempotencyKey = raw,
        delta = kotlinx.serialization.json.Json.parseToJsonElement(raw),
    )

    private suspend fun kotlinx.coroutines.CoroutineScope.deliverThroughSubscription(
        source: ControllerSubagentRegistrySource,
        frame: AppServerInboundFrame.StreamDelta,
    ) {
        val events = kotlinx.coroutines.flow.flowOf(
            com.letta.mobile.data.transport.appserver.AppServerReceivedFrame(
                channel = com.letta.mobile.data.transport.appserver.AppServerChannel.Stream,
                frame = frame, raw = buildJsonObject {},
            ),
        )
        source.start(this, events).join()
    }

    private fun repeatedTaskSource(orphanOlder: Boolean = false): ControllerSubagentRegistrySource {
        val source = ControllerSubagentRegistrySource()
        source.ingestFromSource(
            "conversation", "parent",
            listOf(
                SubagentEntry(
                    toolCallId = "older-dispatch", taskId = "task_15", subagentAgentId = "claude-code-older-child",
                    subagentType = "claude-code", status = SubagentStatus.RUNNING,
                    description = "Finished background task",
                ),
                SubagentEntry(
                    toolCallId = "codex-dispatch", taskId = "task_15", subagentAgentId = codexChild,
                    subagentType = "codex", status = SubagentStatus.RUNNING,
                    description = "Finished background task",
                ),
            ),
            SubagentChipSource.CONTROLLER_NATIVE,
        )
        if (orphanOlder) source.registry.reconcile("conversation", setOf("codex-dispatch"))
        return source
    }

    private fun repeatedTaskNotification(includeChild: Boolean = true) = stream("parent", "conversation", buildJsonObject {
        put("message_type", "user_message")
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                val childToken = if (includeChild) " agent_id=$codexChild" else ""
                put("text", """
                    <task-notification>
                    <task-id>task_15</task-id>
                    <status>completed</status>
                    <summary>Finished background task</summary>
                    <result>subagent_type=codex subagent_id=subagent-1792753239807-15 subagent_status=success$childToken runtime_session_id=session-15
                    Finished the requested work. The example agent_id=claude-code-older-child is result prose.
                    </result>
                    </task-notification>
                """.trimIndent())
            })
        })
    }.toString())

    @Test
    fun completionFinishesRunningDispatchWhenOrphanedTaskIdRepeatsAfterRestart() = runTest {
        val source = repeatedTaskSource(orphanOlder = true)
        val orphaned = source.registry.record("conversation", "parent", "older-dispatch")!!
        assertEquals(SubagentChipState.ORPHANED, orphaned.state)

        deliverThroughSubscription(source, repeatedTaskNotification())

        assertEquals(SubagentChipState.COMPLETED, source.registry.record("conversation", "parent", "codex-dispatch")!!.state)
        assertEquals(orphaned, source.registry.record("conversation", "parent", "older-dispatch"))
    }

    @Test
    fun resultHeaderChildIdSelectsOnlyMatchingRunningDispatchWithRepeatedTaskId() = runTest {
        val source = repeatedTaskSource()
        val older = source.registry.record("conversation", "parent", "older-dispatch")!!

        deliverThroughSubscription(source, repeatedTaskNotification())

        assertEquals(SubagentChipState.COMPLETED, source.registry.record("conversation", "parent", "codex-dispatch")!!.state)
        assertEquals(older, source.registry.record("conversation", "parent", "older-dispatch"))
    }

    @Test
    fun ambiguousRepeatedRunningTaskWithoutChildIdWarnsAndChangesNothing() = runTest {
        val source = repeatedTaskSource()
        val before = source.registry.replaySnapshot("conversation")
        assertTrue(before.all { it.state == SubagentChipState.RUNNING })
        Telemetry.clear()

        deliverThroughSubscription(source, repeatedTaskNotification(includeChild = false))

        assertEquals(before, source.registry.replaySnapshot("conversation"))
        val warning = Telemetry.snapshot().single { it.tag == "SubagentRegistry" && it.name == "terminal.ambiguous" }
        assertEquals(Telemetry.Level.WARN, warning.level)
        assertEquals("task_15", warning.attrs["taskId"]?.textOrNull)
        assertEquals(2, warning.attrs["matchCount"]?.numberOrNull)
    }

    @Test
    fun completionWithoutChildIdIgnoresOrphanedTaskFromPreviousRestart() = runTest {
        val source = repeatedTaskSource(orphanOlder = true)
        val orphaned = source.registry.record("conversation", "parent", "older-dispatch")!!

        deliverThroughSubscription(source, repeatedTaskNotification(includeChild = false))

        assertEquals(SubagentChipState.COMPLETED, source.registry.record("conversation", "parent", "codex-dispatch")!!.state)
        assertEquals(orphaned, source.registry.record("conversation", "parent", "older-dispatch"))
    }

    @Test
    fun dispatchReceiptAndNotificationCompleteOnlyMatchingParentAndSurviveReplay() = runTest {
        val store = InMemorySubagentRegistryStore()
        val source = ControllerSubagentRegistrySource(DurableSubagentRegistry(store))
        val dispatch = """{
            "message_type":"tool_call_message",
            "tool_call":{"name":"Agent","tool_call_id":"call-7",
            "arguments":{"description":"Inspect README for test setup","subagent_type":"general-purpose"}}
        }"""
        // Same labelled receipt shape as exact-tool-turns.json, with distinct task and tool ids.
        val receiptBody = "Task running in background with task ID: task_12\n" +
            "Agent ID: agent-local-child\nOutput file: /tmp/task_12.log\n\n" +
            "A <task-notification> message will be delivered with the result."
        val receipt = buildJsonObject {
            put("message_type", "tool_return_message")
            put("tool_call_id", "call-7")
            put("tool_return", receiptBody)
        }.toString()
        val notification = """{
            "message_type":"user_message","content":[{"type":"text",
            "text":"<task-notification><task-id>task_12</task-id><status>completed</status><summary>Done</summary>
            <usage>duration_ms: 15800</usage></task-notification>"}]
        }""".replace("\n", "")
        for ((agent, conversation) in listOf("parent" to "local-conv-672", "other" to "local-conv-672", "parent" to "default")) {
            source.ingestFromSource(
                conversation, agent,
                listOf(SubagentEntry(toolCallId = "call-7", status = SubagentStatus.RUNNING)),
                SubagentChipSource.CONTROLLER_NATIVE,
            )
            source.ingest(stream(agent, conversation, dispatch))
            source.ingest(stream(agent, conversation, receipt))
        }
        deliverThroughSubscription(source, stream("parent", "local-conv-672", notification))
        val completed = source.registry.record("local-conv-672", "parent", "call-7")!!
        assertEquals(SubagentChipState.COMPLETED, completed.state)
        assertEquals(15_800L, completed.toEntry().durationMs)
        assertEquals(SubagentChipState.RUNNING, source.registry.record("local-conv-672", "other", "call-7")!!.state)
        assertEquals(SubagentChipState.RUNNING, source.registry.record("default", "parent", "call-7")!!.state)
        source.ingest(stream("parent", "local-conv-672", dispatch))
        source.ingest(stream("parent", "local-conv-672", receipt))
        source.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "parent", conversationId = "local-conv-672"),
                eventSeq = 2, emittedAt = "late", idempotencyKey = "late-running",
                subagents = listOf(buildJsonObject {
                    put("tool_call_id", "call-7")
                    put("status", "running")
                    put("tool_call_count", 9)
                }),
            ),
        )
        val restarted = ControllerSubagentRegistrySource(DurableSubagentRegistry(store))
        assertEquals(SubagentChipState.COMPLETED, restarted.registry.record("local-conv-672", "parent", "call-7")!!.state)
        val roster = com.letta.mobile.data.subagents.projectSubagentRoster(
            listOf(completed.toEntry()), now = 9_999_999L,
        )
        assertEquals(0, roster.runningCount)
        assertEquals(15_800L, roster.finished.single().elapsedMs)
    }

    @Test
    fun streamDispatchNeedsCorrelatedTaskIdentityBeforeNotificationCanCompleteIt() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.ingest(stream("parent", "conversation", """{
            "message_type":"tool_call_message",
            "tool_call":{"name":"Agent","tool_call_id":"call-7","arguments":{"description":"Read files"}}
        }"""))
        fun notification(task: String) = stream("parent", "conversation", buildJsonObject {
            put("message_type", "user_message")
            put("content", "<task-notification><task-id>$task</task-id><status>completed</status></task-notification>")
        }.toString())
        source.ingest(notification("call-7"))
        assertEquals(SubagentStatus.RUNNING, source.list("conversation", true).single().status)
        source.ingest(stream("parent", "conversation", """{
            "message_type":"tool_return_message","tool_call_id":"call-7",
            "tool_return":{"task_id":"task_12","agent_id":"child","conversation_id":"default"}
        }"""))
        source.ingest(notification("task_other"))
        assertEquals(SubagentStatus.RUNNING, source.list("conversation", true).single().status)
        source.ingest(notification("task_12"))
        assertEquals(SubagentStatus.COMPLETED, source.list("conversation", true).single().status)
    }

    @Test
    fun updateSubagentStateHydratesConversationScopedList() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-a"),
                eventSeq = 1,
                emittedAt = "t",
                idempotencyKey = "k",
                subagents = listOf(
                    buildJsonObject {
                        put("toolCallId", "tool/1")
                        put("description", "Ship fix")
                        put("status", SubagentStatus.RUNNING)
                        put("parentConversationId", "conv-a")
                        put("parentAgentId", "agent-1")
                    },
                ),
            ),
        )

        val running = source.list("conv-a", includeTerminal = false)
        assertEquals(listOf("tool/1"), running.map { it.toolCallId })
        assertEquals("Ship fix", running.single().description)
        assertEquals(emptyList(), source.list("conv-other", includeTerminal = true))
    }

    @Test
    fun controllerActivityNeverBecomesTodoProgress() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-a"),
                eventSeq = 1,
                emittedAt = "t",
                idempotencyKey = "activity",
                subagents = listOf(
                    buildJsonObject {
                        put("tool_call_id", "tool/1")
                        put("status", "running")
                        put("activity", buildJsonObject {
                            put("lines", buildJsonArray {
                                add("Reading the repository")
                                add("Running focused tests")
                            })
                        })
                    },
                ),
            ),
        )

        val snapshot = source.todos("conv-a", "tool/1")!!
        assertFalse(snapshot.todosFound)
        assertEquals(emptyList(), snapshot.todos)
        assertEquals(null, snapshot.subagent.todoProgress)
        assertEquals(2, snapshot.subagent.activity!!.lines.size)
    }

    @Test
    fun terminalActivityDoesNotInventCompletedTodos() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.replaceConversation(
            "conv-a",
            listOf(
                SubagentEntry(
                    toolCallId = "done",
                    status = SubagentStatus.COMPLETED,
                    parentConversationId = "conv-a",
                    activity = SubagentActivitySnapshot(lines = listOf("Finished focused tests")),
                ),
            ),
        )

        assertFalse(source.todos("conv-a", "done")!!.todosFound)
        assertEquals(emptyList(), source.todos("conv-a", "done")!!.todos)
        assertEquals(null, source.todos("conv-a", "done")!!.subagent.todoProgress)
    }

    @Test
    fun includeTerminalFiltersRunningOnlyWhenFalse() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.replaceConversation(
            "conv-a",
            listOf(
                SubagentEntry(toolCallId = "run", status = SubagentStatus.RUNNING, parentConversationId = "conv-a"),
                SubagentEntry(toolCallId = "done", status = SubagentStatus.COMPLETED, parentConversationId = "conv-a"),
            ),
        )
        assertEquals(listOf("run"), source.list("conv-a", includeTerminal = false).map { it.toolCallId })
        assertEquals(setOf("run", "done"), source.list("conv-a", includeTerminal = true).map { it.toolCallId }.toSet())
    }

    @Test
    fun capabilityAdvertisedOnlyWhenSubagentRoutesRegistered() {
        val withoutSource = AdminRpcRegistry.buildRouter(
            services = AdminRpcHostServices(
                controller = null,
                subagentRegistrySource = null,
            ),
        )
        val withSource = AdminRpcRegistry.buildRouter(
            services = AdminRpcHostServices(
                controller = null,
                subagentRegistrySource = ControllerSubagentRegistrySource(),
            ),
        )
        assertFalse(ControllerSubagentRegistrySource.CAPABILITY in IrohNodeConnection.advertisedCapabilities(withoutSource))
        assertTrue(ControllerSubagentRegistrySource.CAPABILITY in IrohNodeConnection.advertisedCapabilities(withSource))
    }

    @Test
    fun snakeCaseSnapshotMapsPendingErrorAndNumericStartTime() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-a"),
                eventSeq = 2,
                emittedAt = "t",
                idempotencyKey = "k2",
                subagents = listOf(
                    buildJsonObject {
                        put("subagent_id", "sa-1")
                        put("status", "pending")
                        put("conversation_id", "sub-conv")
                        put("start_time", 1_700_000_000L)
                    },
                    buildJsonObject {
                        put("tool_call_id", "tool/err")
                        put("status", "running")
                        put("error", "boom")
                    },
                ),
            ),
        )

        val running = source.list("conv-a", includeTerminal = false)
        assertEquals(listOf("sa-1"), running.map { it.toolCallId })
        assertEquals(SubagentStatus.RUNNING, running.single().status)
        assertEquals("sub-conv", running.single().subagentConversationId)
        assertEquals("1700000000000", running.single().startedAt)

        val all = source.list("conv-a", includeTerminal = true)
        val failed = all.single { it.toolCallId == "tool/err" }
        assertEquals(SubagentStatus.FAILED, failed.status)
    }

    /** Missing snapshots do not prove that a background worker ended. */
    @Test
    fun laterUpdateSubagentStateRetainsMissingRunningTasks() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-a"),
                eventSeq = 1,
                emittedAt = "t1",
                idempotencyKey = "k1",
                subagents = listOf(
                    buildJsonObject {
                        put("subagent_id", "sa-old")
                        put("status", "pending")
                    },
                ),
            ),
        )
        source.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-a"),
                eventSeq = 2,
                emittedAt = "t2",
                idempotencyKey = "k2",
                subagents = listOf(
                    buildJsonObject {
                        put("tool_call_id", "tool/new")
                        put("subagent_id", "sa-new")
                        put("status", "running")
                    },
                ),
            ),
        )

        assertEquals(setOf("sa-old", "tool/new"), source.list("conv-a", includeTerminal = false).map { it.toolCallId }.toSet())
        val all = source.list("conv-a", includeTerminal = true)
        assertEquals(setOf("sa-old", "tool/new"), all.map { it.toolCallId }.toSet())
        assertEquals(SubagentStatus.RUNNING, all.single { it.toolCallId == "sa-old" }.status)
        assertEquals(
            SubagentChipState.RUNNING,
            source.registry.record("conv-a", "agent-1", "sa-old")?.state,
        )
    }

    // lgns8.22.8: a durable-store-backed source rehydrates across a restart.
    @Test
    fun sourceRehydratesChipsAcrossAControllerRestart() = runTest {
        val store = InMemorySubagentRegistryStore()
        val before = ControllerSubagentRegistrySource(DurableSubagentRegistry(store = store))
        before.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-a"),
                eventSeq = 1,
                emittedAt = "t1",
                idempotencyKey = "k1",
                subagents = listOf(
                    buildJsonObject {
                        put("toolCallId", "tool/1")
                        put("status", SubagentStatus.RUNNING)
                    },
                ),
            ),
        )

        val after = ControllerSubagentRegistrySource(DurableSubagentRegistry(store = store))

        assertEquals(listOf("tool/1"), after.list("conv-a", includeTerminal = false).map { it.toolCallId })
        assertEquals(listOf("tool/1"), after.replaySnapshot("conv-a").map { it.toolCallId })
    }

    // letta-mobile-7vs4s: a weaker source cannot overwrite controller-native truth.
    @Test
    fun httpAndCorrelatorSourcesCannotOverwriteControllerNativeChips() = runTest {
        val source = ControllerSubagentRegistrySource()
        source.ingest(
            AppServerInboundFrame.UpdateSubagentState(
                runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-a"),
                eventSeq = 1,
                emittedAt = "t1",
                idempotencyKey = "k1",
                subagents = listOf(
                    buildJsonObject {
                        put("toolCallId", "tool/1")
                        put("status", SubagentStatus.COMPLETED)
                    },
                ),
            ),
        )

        source.ingestFromSource(
            conversationId = "conv-a",
            agentId = "agent-1",
            entries = listOf(SubagentEntry(toolCallId = "tool/1", status = SubagentStatus.RUNNING)),
            source = SubagentChipSource.HTTP_REGISTRY,
        )

        assertTrue(source.list("conv-a", includeTerminal = false).isEmpty())
        assertEquals(
            SubagentChipState.COMPLETED,
            source.registry.record("conv-a", "agent-1", "tool/1")?.state,
        )
    }
}
