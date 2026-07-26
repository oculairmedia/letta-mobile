package com.letta.mobile.data.controller.registry

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock

class RuntimeRegistryCoordinatorTest {
    @Test
    fun recoverStartsRuntimesFromRegistry() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = FakeAppServerControllerForRecovery()
        val coordinator = RuntimeRegistryCoordinator(controller, registry)

        // Pre-populate registry with three records
        registry.save(
            RuntimeRecord(
                id = "record-1",
                agentId = AgentId("agent-1"),
                conversationId = ConversationId("conv-1"),
                cwd = "/workspace/1",
            ),
        )
        registry.save(
            RuntimeRecord(
                id = "record-2",
                agentId = AgentId("agent-2"),
                conversationId = ConversationId("conv-2"),
                cwd = "/workspace/2",
            ),
        )
        registry.save(
            RuntimeRecord(
                id = "record-3",
                agentId = AgentId("agent-3"),
                conversationId = ConversationId("conv-3"),
            ),
        )

        // Recover
        val recovered = coordinator.recover()

        // Should have recovered all three
        assertEquals(3, recovered)

        // Controller should have been called three times
        assertEquals(3, controller.startRuntimeCalls.size)

        // Verify calls were made with correct parameters
        assertEquals(AgentId("agent-1"), controller.startRuntimeCalls[0].agentId)
        assertEquals(ConversationId("conv-1"), controller.startRuntimeCalls[0].conversationId)
        assertEquals("/workspace/1", controller.startRuntimeCalls[0].cwd)

        assertEquals(AgentId("agent-2"), controller.startRuntimeCalls[1].agentId)
        assertEquals(ConversationId("conv-2"), controller.startRuntimeCalls[1].conversationId)
        assertEquals("/workspace/2", controller.startRuntimeCalls[1].cwd)

        assertEquals(AgentId("agent-3"), controller.startRuntimeCalls[2].agentId)
        assertEquals(ConversationId("conv-3"), controller.startRuntimeCalls[2].conversationId)
        assertNull(controller.startRuntimeCalls[2].cwd)

        // Verify registry records were updated with canonical runtime
        val record1 = registry.load("record-1")
        assertNotNull(record1)
        assertNotNull(record1.canonicalRuntime)
        assertNotNull(record1.lastStartedAt)
        assertEquals("agent-1", record1.canonicalRuntime?.scope?.agentId)

        val record2 = registry.load("record-2")
        assertNotNull(record2)
        assertNotNull(record2.canonicalRuntime)
        assertNotNull(record2.lastStartedAt)

        val record3 = registry.load("record-3")
        assertNotNull(record3)
        assertNotNull(record3.canonicalRuntime)
        assertNotNull(record3.lastStartedAt)
    }

    @Test
    fun recoverReturnsZeroWhenNoRecords() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = FakeAppServerControllerForRecovery()
        val coordinator = RuntimeRegistryCoordinator(controller, registry)

        val recovered = coordinator.recover()

        assertEquals(0, recovered)
        assertEquals(0, controller.startRuntimeCalls.size)
    }

    @Test
    fun recoverContinuesOnPartialFailure() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = FakeAppServerControllerForRecovery(
            // Fail on agent-2
            shouldFailFor = setOf(AgentId("agent-2")),
        )
        val coordinator = RuntimeRegistryCoordinator(controller, registry)

        registry.save(
            RuntimeRecord(
                id = "record-1",
                agentId = AgentId("agent-1"),
                conversationId = ConversationId("conv-1"),
            ),
        )
        registry.save(
            RuntimeRecord(
                id = "record-2",
                agentId = AgentId("agent-2"),
                conversationId = ConversationId("conv-2"),
            ),
        )
        registry.save(
            RuntimeRecord(
                id = "record-3",
                agentId = AgentId("agent-3"),
                conversationId = ConversationId("conv-3"),
            ),
        )

        val recovered = coordinator.recover()

        // Should have recovered 2 out of 3 (failed on agent-2)
        assertEquals(2, recovered)

        // Controller should have been called three times (including the failed one)
        assertEquals(3, controller.startRuntimeCalls.size)

        // Verify successful records were updated
        val record1 = registry.load("record-1")
        assertNotNull(record1)
        assertNotNull(record1.canonicalRuntime)

        val record2 = registry.load("record-2")
        assertNotNull(record2)
        // Should NOT have canonical runtime (failed)
        assertNull(record2.canonicalRuntime)

        val record3 = registry.load("record-3")
        assertNotNull(record3)
        assertNotNull(record3.canonicalRuntime)
    }

    @Test
    fun ensureRecordReturnsExistingRecord() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = FakeAppServerControllerForRecovery()
        val coordinator = RuntimeRegistryCoordinator(controller, registry)

        val existingRecord = RuntimeRecord(
            id = "existing-1",
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            displayName = "Existing Chat",
        )

        registry.save(existingRecord)

        val record = coordinator.ensureRecord(
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            displayName = "New Chat",
        )

        // Should return the existing record
        assertEquals("existing-1", record.id)
        assertEquals("Existing Chat", record.displayName)

        // Should not have created a new record
        assertEquals(1, registry.list().size)
    }

    @Test
    fun ensureRecordCreatesNewRecordWhenNotFound() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = FakeAppServerControllerForRecovery()
        val coordinator = RuntimeRegistryCoordinator(controller, registry)

        val record = coordinator.ensureRecord(
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            cwd = "/workspace",
            displayName = "New Chat",
            role = "assistant",
        )

        // Should have created a new record
        assertNotNull(record.id)
        assertEquals(AgentId("agent-1"), record.agentId)
        assertEquals(ConversationId("conv-1"), record.conversationId)
        assertEquals("/workspace", record.cwd)
        assertEquals("New Chat", record.displayName)
        assertEquals("assistant", record.role)
        assertNull(record.canonicalRuntime)
        assertNull(record.lastStartedAt)

        // Should be saved in registry
        assertEquals(1, registry.list().size)
        val loaded = registry.load(record.id)
        assertNotNull(loaded)
        assertEquals(record, loaded)
    }

    @Test
    fun removeRecordDeletesFromRegistry() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = FakeAppServerControllerForRecovery()
        val coordinator = RuntimeRegistryCoordinator(controller, registry)

        val record = RuntimeRecord(
            id = "record-1",
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
        )

        registry.save(record)
        assertNotNull(registry.load("record-1"))

        coordinator.removeRecord("record-1")

        assertNull(registry.load("record-1"))
        assertEquals(0, registry.list().size)
    }

    @Test
    fun recoverIsIdempotent() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = FakeAppServerControllerForRecovery()
        val coordinator = RuntimeRegistryCoordinator(controller, registry)

        registry.save(
            RuntimeRecord(
                id = "record-1",
                agentId = AgentId("agent-1"),
                conversationId = ConversationId("conv-1"),
            ),
        )

        // Recover first time
        val recovered1 = coordinator.recover()
        assertEquals(1, recovered1)
        assertEquals(1, controller.startRuntimeCalls.size)

        // Recover second time
        val recovered2 = coordinator.recover()
        assertEquals(1, recovered2)
        // Controller should have been called again, but it will return cached runtime
        assertEquals(2, controller.startRuntimeCalls.size)

        // Record should still be valid with updated timestamp
        val record = registry.load("record-1")
        assertNotNull(record)
        assertNotNull(record.canonicalRuntime)
        assertNotNull(record.lastStartedAt)
    }
}

/**
 * Fake App Server controller for recovery testing.
 */
private class FakeAppServerControllerForRecovery(
    private val shouldFailFor: Set<AgentId> = emptySet(),
) : AppServerController {
    data class StartRuntimeCall(
        val agentId: AgentId,
        val conversationId: ConversationId,
        val cwd: String?,
    )

    val startRuntimeCalls = mutableListOf<StartRuntimeCall>()

    override val state: StateFlow<AppServerControllerState> =
        MutableStateFlow(AppServerControllerState.Connected)

    override suspend fun startRuntime(
        agentId: AgentId,
        conversationId: ConversationId,
        cwd: String?,
        mode: com.letta.mobile.data.transport.appserver.AppServerPermissionMode?,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): CanonicalRuntime {
        startRuntimeCalls += StartRuntimeCall(agentId, conversationId, cwd)

        if (agentId in shouldFailFor) {
            throw Exception("Simulated failure for $agentId")
        }

        return CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = agentId.value,
                conversationId = conversationId.value,
            ),
        )
    }

    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = emptyFlow()

    override suspend fun sync(
        runtime: AppServerRuntimeScope,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): AppServerInboundFrame.SyncResponse {
        return AppServerInboundFrame.SyncResponse(
            requestId = "sync-1",
            runtime = runtime,
            success = true,
        )
    }

    override suspend fun abort(
        runtime: AppServerRuntimeScope,
        runId: String?,
    ): AppServerInboundFrame.AbortMessageResponse {
        return AppServerInboundFrame.AbortMessageResponse(
            requestId = "abort-1",
            runtime = runtime,
            aborted = true,
            success = true,
        )
    }
}
