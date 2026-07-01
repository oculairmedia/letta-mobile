package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerException
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.controller.registry.InMemoryRuntimeRegistry
import com.letta.mobile.data.controller.registry.RuntimeRecord
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectCoordinatorTest {
    @Test
    fun reconnectLoadsRecordsAndReconnectsEachRuntime() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = FakeAppServerController()

        // Save some runtime records
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

        val coordinator = ReconnectCoordinator(
            controller = controller,
            registry = registry,
        )

        val result = coordinator.reconnect()

        // Should reconnect both runtimes
        assertEquals(2, result.reconnectedCount)
        assertTrue(result.isFullySuccessful)
        assertTrue(result.errors.isEmpty())

        // Should have called startRuntime for each record
        assertEquals(2, controller.startRuntimeCalls.size)
        assertEquals(
            setOf("agent-1" to "conv-1", "agent-2" to "conv-2"),
            controller.startRuntimeCalls.map { it.first to it.second }.toSet(),
        )

        // Should have called sync for each runtime with recover_approvals=true and force_device_status=true
        assertEquals(2, controller.syncCalls.size)
        controller.syncCalls.forEach { call ->
            assertTrue(call.recoverApprovals)
            assertTrue(call.forceDeviceStatus)
        }
    }

    @Test
    fun reconnectIsIdempotent() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = FakeAppServerController()

        registry.save(
            RuntimeRecord(
                id = "record-1",
                agentId = AgentId("agent-1"),
                conversationId = ConversationId("conv-1"),
            ),
        )

        val coordinator = ReconnectCoordinator(
            controller = controller,
            registry = registry,
        )

        // Call reconnect twice
        val result1 = coordinator.reconnect()
        val result2 = coordinator.reconnect()

        // Both should succeed
        assertEquals(1, result1.reconnectedCount)
        assertEquals(1, result2.reconnectedCount)

        // startRuntime should be called twice (once per reconnect)
        // but the controller caches runtimes, so it should only actually start once
        assertEquals(2, controller.startRuntimeCalls.size)

        // sync should be called twice (once per reconnect)
        assertEquals(2, controller.syncCalls.size)
    }

    @Test
    fun reconnectCallsExternalToolRegistrarHookAfterRuntimeStart() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = FakeAppServerController()
        val toolRegistrar = FakeExternalToolRegistrar()

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

        val coordinator = ReconnectCoordinator(
            controller = controller,
            registry = registry,
            externalToolRegistrar = toolRegistrar,
        )

        val result = coordinator.reconnect()

        assertEquals(2, result.reconnectedCount)
        assertTrue(result.isFullySuccessful)

        // Should have called reRegisterAll for each runtime
        assertEquals(2, toolRegistrar.reRegisterCalls.size)
        assertEquals(
            setOf("agent-1" to "conv-1", "agent-2" to "conv-2"),
            toolRegistrar.reRegisterCalls.map { it.agentId to it.conversationId }.toSet(),
        )
    }

    @Test
    fun reconnectHandlesPartialFailures() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = FakeAppServerController(
            shouldFailStartRuntime = { agentId, _ ->
                agentId.value == "agent-2"
            },
        )

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

        val coordinator = ReconnectCoordinator(
            controller = controller,
            registry = registry,
        )

        val result = coordinator.reconnect()

        // Should reconnect one runtime successfully
        assertEquals(1, result.reconnectedCount)
        assertTrue(result.isPartiallySuccessful)

        // Should have one error
        assertEquals(1, result.errors.size)
        assertEquals("record-2", result.errors.first().runtimeRecordId)
        assertEquals(ReconnectPhase.RECONNECT_RUNTIME, result.errors.first().phase)
    }

    @Test
    fun reconnectFailsGracefullyWhenRegistryLoadFails() = runTest {
        val registry = FailingRuntimeRegistry()
        val controller = FakeAppServerController()

        val coordinator = ReconnectCoordinator(
            controller = controller,
            registry = registry,
        )

        val result = coordinator.reconnect()

        assertEquals(0, result.reconnectedCount)
        assertTrue(result.isFailed)

        // Should have one error for loading records
        assertEquals(1, result.errors.size)
        assertEquals(ReconnectPhase.LOAD_RECORDS, result.errors.first().phase)
    }

    @Test
    fun reconnectHandlesEmptyRegistry() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = FakeAppServerController()

        val coordinator = ReconnectCoordinator(
            controller = controller,
            registry = registry,
        )

        val result = coordinator.reconnect()

        assertEquals(0, result.reconnectedCount)
        assertTrue(result.isFullySuccessful)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun isReconnectNeededReturnsTrueWhenDisconnected() = runTest {
        val controller = FakeAppServerController()
        controller.setState(AppServerControllerState.Disconnected("Connection lost"))

        val coordinator = ReconnectCoordinator(
            controller = controller,
            registry = InMemoryRuntimeRegistry(),
        )

        assertTrue(coordinator.isReconnectNeeded())
    }

    @Test
    fun isReconnectNeededReturnsTrueWhenError() = runTest {
        val controller = FakeAppServerController()
        controller.setState(AppServerControllerState.Error("Failed to connect"))

        val coordinator = ReconnectCoordinator(
            controller = controller,
            registry = InMemoryRuntimeRegistry(),
        )

        assertTrue(coordinator.isReconnectNeeded())
    }

    @Test
    fun isReconnectNeededReturnsFalseWhenConnected() = runTest {
        val controller = FakeAppServerController()
        controller.setState(AppServerControllerState.Connected)

        val coordinator = ReconnectCoordinator(
            controller = controller,
            registry = InMemoryRuntimeRegistry(),
        )

        assertFalse(coordinator.isReconnectNeeded())
    }

    @Test
    fun reconnectPassesCorrectParametersToSync() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = FakeAppServerController()

        registry.save(
            RuntimeRecord(
                id = "record-1",
                agentId = AgentId("agent-1"),
                conversationId = ConversationId("conv-1"),
                cwd = "/workspace",
            ),
        )

        val coordinator = ReconnectCoordinator(
            controller = controller,
            registry = registry,
        )

        coordinator.reconnect()

        // Verify sync was called with correct parameters
        assertEquals(1, controller.syncCalls.size)
        val syncCall = controller.syncCalls.first()
        assertEquals("agent-1", syncCall.runtime.agentId)
        assertEquals("conv-1", syncCall.runtime.conversationId)
        assertTrue(syncCall.recoverApprovals)
        assertTrue(syncCall.forceDeviceStatus)
    }
}

// Test doubles

private class FakeAppServerController(
    private val shouldFailStartRuntime: (AgentId, ConversationId) -> Boolean = { _, _ -> false },
) : AppServerController {
    private val _state = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)
    override val state: StateFlow<AppServerControllerState> = _state

    val startRuntimeCalls = mutableListOf<Triple<String, String, String?>>()
    val syncCalls = mutableListOf<SyncCall>()

    fun setState(newState: AppServerControllerState) {
        _state.value = newState
    }

    override suspend fun startRuntime(
        agentId: AgentId,
        conversationId: ConversationId,
        cwd: String?,
        mode: AppServerPermissionMode?,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): CanonicalRuntime {
        if (shouldFailStartRuntime(agentId, conversationId)) {
            throw AppServerControllerException("Failed to start runtime $agentId/$conversationId")
        }

        startRuntimeCalls += Triple(agentId.value, conversationId.value, cwd)

        return CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = agentId.value,
                conversationId = conversationId.value,
            ),
            agent = buildJsonObject {
                put("id", agentId.value)
            },
            conversation = buildJsonObject {
                put("id", conversationId.value)
            },
        )
    }

    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = emptyFlow()

    override suspend fun sync(
        runtime: AppServerRuntimeScope,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): AppServerInboundFrame.SyncResponse {
        syncCalls += SyncCall(
            runtime = runtime,
            recoverApprovals = recoverApprovals,
            forceDeviceStatus = forceDeviceStatus,
        )

        return AppServerInboundFrame.SyncResponse(
            requestId = "sync-req-${syncCalls.size}",
            runtime = runtime,
            success = true,
        )
    }

    override suspend fun abort(
        runtime: AppServerRuntimeScope,
        runId: String?,
    ): AppServerInboundFrame.AbortMessageResponse {
        return AppServerInboundFrame.AbortMessageResponse(
            requestId = "abort-req-1",
            runtime = runtime,
            aborted = true,
            success = true,
        )
    }

    data class SyncCall(
        val runtime: AppServerRuntimeScope,
        val recoverApprovals: Boolean,
        val forceDeviceStatus: Boolean,
    )
}

private class FakeExternalToolRegistrar : ExternalToolRegistrar {
    val reRegisterCalls = mutableListOf<AppServerRuntimeScope>()

    override suspend fun reRegisterAll(runtime: AppServerRuntimeScope) {
        reRegisterCalls += runtime
    }
}

private class FailingRuntimeRegistry : com.letta.mobile.data.controller.registry.RuntimeRegistry {
    override suspend fun save(record: RuntimeRecord) {
        throw RuntimeException("Registry is broken")
    }

    override suspend fun load(id: String): RuntimeRecord? {
        throw RuntimeException("Registry is broken")
    }

    override suspend fun list(): List<RuntimeRecord> {
        throw RuntimeException("Registry is broken")
    }

    override suspend fun remove(id: String) {
        throw RuntimeException("Registry is broken")
    }

    override suspend fun markStarted(
        id: String,
        canonicalRuntime: CanonicalRuntime,
        lastStartedAt: kotlin.time.Instant,
    ) {
        throw RuntimeException("Registry is broken")
    }

    override suspend fun findByAgentAndConversation(
        agentId: AgentId,
        conversationId: ConversationId,
    ): RuntimeRecord? {
        throw RuntimeException("Registry is broken")
    }
}
