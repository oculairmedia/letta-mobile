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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ReconnectCoordinatorResumeTest {

    @Test
    fun onAppResumedSyncsAllActiveRuntimesConcurrentlyWithForceDeviceStatus() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = TestAppServerController()

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

        val result = coordinator.onAppResumed()
        assertIs<ResumeSyncResult.Success>(result)
        assertEquals(2, result.syncedCount)

        assertEquals(2, controller.syncCalls.size)
        controller.syncCalls.forEach { call ->
            assertFalse(call.recoverApprovals, "Resume sync should not recover approvals")
            assertTrue(call.forceDeviceStatus, "Resume sync must force device status")
        }
    }

    @Test
    fun onAppResumedIsDebouncedWithinCooldownWindow() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = TestAppServerController()
        val fakeClock = FakeClock(Instant.fromEpochMilliseconds(1000000L))

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
            clock = fakeClock,
        )

        val result1 = coordinator.onAppResumed(cooldown = 5.seconds)
        assertIs<ResumeSyncResult.Success>(result1)
        assertEquals(1, controller.syncCalls.size)

        // Immediate subsequent call within 5s cooldown
        fakeClock.advanceBy(2.seconds)
        val result2 = coordinator.onAppResumed(cooldown = 5.seconds)
        assertIs<ResumeSyncResult.Skipped>(result2)
        assertEquals("cooldown_active", result2.reason)
        assertEquals(1, controller.syncCalls.size, "Debounced call must not issue extra sync RPCs")

        // After cooldown expires
        fakeClock.advanceBy(4.seconds) // now 6s total elapsed
        val result3 = coordinator.onAppResumed(cooldown = 5.seconds)
        assertIs<ResumeSyncResult.Success>(result3)
        assertEquals(2, controller.syncCalls.size, "Should sync after cooldown window passes")
    }

    @Test
    fun onAppResumedSkipsWhenReconnectIsNeeded() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = TestAppServerController()
        controller.setState(AppServerControllerState.Disconnected("Socket closed"))

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

        val result = coordinator.onAppResumed()
        assertIs<ResumeSyncResult.Skipped>(result)
        assertEquals("reconnect_needed", result.reason)
        assertEquals(0, controller.syncCalls.size)
    }

    @Test
    fun onAppResumedSkipsWhenReconnectIsInFlight() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val syncGate = CompletableDeferred<Unit>()
        val controller = TestAppServerController(
            onSyncHook = { _, _ ->
                syncGate.await()
            },
        )

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

        // Launch reconnect which hangs in onSyncHook
        val reconnectJob = async { coordinator.reconnect() }

        // Give reconnect time to acquire the mutex and reach syncGate
        delay(50)

        // Attempt resume while reconnect is in flight
        val resumeResult = coordinator.onAppResumed()
        assertIs<ResumeSyncResult.Skipped>(resumeResult)
        assertEquals("reconnect_in_flight", resumeResult.reason)

        // Unblock reconnect and wait for completion
        syncGate.complete(Unit)
        val reconnectResult = reconnectJob.await()
        assertEquals(1, reconnectResult.reconnectedCount)
    }

    @Test
    fun onAppResumedBoundsTimeoutPerRuntimeAndReturnsPartialOnSlowRuntime() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = TestAppServerController(
            onSyncHook = { runtime, _ ->
                if (runtime.agentId == "agent-slow") {
                    delay(2000) // longer than timeoutPerRuntime
                }
            },
        )

        registry.save(
            RuntimeRecord(
                id = "record-fast",
                agentId = AgentId("agent-fast"),
                conversationId = ConversationId("conv-fast"),
            ),
        )
        registry.save(
            RuntimeRecord(
                id = "record-slow",
                agentId = AgentId("agent-slow"),
                conversationId = ConversationId("conv-slow"),
            ),
        )

        val coordinator = ReconnectCoordinator(
            controller = controller,
            registry = registry,
        )

        val result = coordinator.onAppResumed(
            timeoutPerRuntime = 100.milliseconds,
            cooldown = 0.seconds,
        )
        assertIs<ResumeSyncResult.Partial>(result)
        assertEquals(1, result.syncedCount)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.first().message.contains("timed out"))
    }

    @Test
    fun onAppResumedReturnsSuccessZeroWhenRegistryIsEmpty() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = TestAppServerController()

        val coordinator = ReconnectCoordinator(
            controller = controller,
            registry = registry,
        )

        val result = coordinator.onAppResumed()
        assertIs<ResumeSyncResult.Success>(result)
        assertEquals(0, result.syncedCount)
        assertEquals(0, controller.syncCalls.size)
    }
}

private class FakeClock(private var current: Instant) : Clock {
    override fun now(): Instant = current

    fun advanceBy(duration: kotlin.time.Duration) {
        current = current.plus(duration)
    }
}

private class TestAppServerController(
    private val onSyncHook: suspend (AppServerRuntimeScope, Boolean) -> Unit = { _, _ -> },
) : AppServerController {
    private val _state = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)
    override val state: StateFlow<AppServerControllerState> = _state

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
        return CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = agentId.value,
                conversationId = conversationId.value,
            ),
            agent = buildJsonObject { put("id", agentId.value) },
            conversation = buildJsonObject { put("id", conversationId.value) },
        )
    }

    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = emptyFlow()

    override suspend fun sync(
        runtime: AppServerRuntimeScope,
        recoverApprovals: Boolean,
        forceDeviceStatus: Boolean,
    ): AppServerInboundFrame.SyncResponse {
        syncCalls += SyncCall(runtime, recoverApprovals, forceDeviceStatus)
        onSyncHook(runtime, forceDeviceStatus)
        return AppServerInboundFrame.SyncResponse(
            requestId = "sync-test-${syncCalls.size}",
            runtime = runtime,
            success = true,
        )
    }

    override suspend fun abort(
        runtime: AppServerRuntimeScope,
        runId: String?,
    ): AppServerInboundFrame.AbortMessageResponse {
        return AppServerInboundFrame.AbortMessageResponse(
            requestId = "abort-test",
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
