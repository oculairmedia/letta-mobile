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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
    fun parentCancellationStopsRegistryAndRuntimeWorkAndReleasesCoordinatorLocks() = runTest {
        for (resume in listOf(false, true)) {
            for (duringLoad in listOf(false, true)) verifyParentCancellation(resume, duringLoad)
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.verifyParentCancellation(resume: Boolean, duringLoad: Boolean) {
        val backing = InMemoryRuntimeRegistry()
        backing.save(RuntimeRecord(
            id = "record-1", agentId = AgentId("agent-1"), conversationId = ConversationId("conv-1"),
            canonicalRuntime = CanonicalRuntime(scope = AppServerRuntimeScope("agent-1", "conv-1")),
        ))
        backing.save(RuntimeRecord(
            id = "record-2", agentId = AgentId("agent-2"), conversationId = ConversationId("conv-2"),
        ))
        val entered = CompletableDeferred<Unit>()
        var block = true
        var cleanups = 0
        var continued = false
        suspend fun blockUntilCancelled() {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cleanups++
            }
        }
        val registry = object : com.letta.mobile.data.controller.registry.RuntimeRegistry by backing {
            override suspend fun list(): List<RuntimeRecord> {
                if (block && duringLoad) blockUntilCancelled()
                return backing.list()
            }
        }
        val controller = TestAppServerController(onSyncHook = { _, _ ->
            if (block && !duringLoad) blockUntilCancelled()
        })
        val coordinator = ReconnectCoordinator(controller, registry)
        val recorder = com.letta.mobile.util.TelemetryRecorder()
        com.letta.mobile.util.Telemetry.installRecorder(recorder).use {
            val job = launch {
                if (resume) coordinator.onAppResumed(cooldown = kotlin.time.Duration.ZERO) else coordinator.reconnect()
                continued = true
            }
            entered.await()
            job.cancelAndJoin()
            assertFalse(continued, "cancellation must not be converted to a result")
            assertEquals(1, cleanups)
            assertTrue(controller.startRuntimeCalls.size <= 1, "must not reconnect the next runtime after cancellation")
            assertTrue(recorder.snapshot().none { it.name == "resume.sync.failed" || it.name == "resume.sync" })
            block = false
            if (resume) {
                assertIs<ResumeSyncResult.Success>(coordinator.onAppResumed(cooldown = kotlin.time.Duration.ZERO))
            } else {
                assertTrue(coordinator.reconnect().isFullySuccessful)
            }
        }
    }

    @Test
    fun onAppResumedSyncsAllActiveRuntimesConcurrentlyWithForceDeviceStatus() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = TestAppServerController()

        registry.save(
            RuntimeRecord(
                id = "record-1",
                agentId = AgentId("agent-1"),
                conversationId = ConversationId("conv-1"),
                canonicalRuntime = CanonicalRuntime(scope = AppServerRuntimeScope("agent-1", "conv-1")),
            ),
        )
        registry.save(
            RuntimeRecord(
                id = "record-2",
                agentId = AgentId("agent-2"),
                conversationId = ConversationId("conv-2"),
                canonicalRuntime = CanonicalRuntime(scope = AppServerRuntimeScope("agent-2", "conv-2")),
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
    fun onAppResumedSkipsRecordsWithoutCanonicalRuntimeWithoutCallingStartRuntime() = runTest {
        val registry = InMemoryRuntimeRegistry()
        val controller = TestAppServerController()

        // Record 1 has canonicalRuntime; Record 2 has none
        registry.save(
            RuntimeRecord(
                id = "record-1",
                agentId = AgentId("agent-1"),
                conversationId = ConversationId("conv-1"),
                canonicalRuntime = CanonicalRuntime(scope = AppServerRuntimeScope("agent-1", "conv-1")),
            ),
        )
        registry.save(
            RuntimeRecord(
                id = "record-2",
                agentId = AgentId("agent-2"),
                conversationId = ConversationId("conv-2"),
                canonicalRuntime = null,
            ),
        )

        val coordinator = ReconnectCoordinator(
            controller = controller,
            registry = registry,
        )

        val result = coordinator.onAppResumed()
        assertIs<ResumeSyncResult.Success>(result)
        assertEquals(1, result.syncedCount)
        assertEquals(1, controller.syncCalls.size)
        assertEquals(0, controller.startRuntimeCalls.size, "Resume sync must NEVER call startRuntime")
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
                canonicalRuntime = CanonicalRuntime(scope = AppServerRuntimeScope("agent-1", "conv-1")),
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
                canonicalRuntime = CanonicalRuntime(scope = AppServerRuntimeScope("agent-1", "conv-1")),
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
                canonicalRuntime = CanonicalRuntime(scope = AppServerRuntimeScope("agent-1", "conv-1")),
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
                canonicalRuntime = CanonicalRuntime(scope = AppServerRuntimeScope("agent-fast", "conv-fast")),
            ),
        )
        registry.save(
            RuntimeRecord(
                id = "record-slow",
                agentId = AgentId("agent-slow"),
                conversationId = ConversationId("conv-slow"),
                canonicalRuntime = CanonicalRuntime(scope = AppServerRuntimeScope("agent-slow", "conv-slow")),
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
    val startRuntimeCalls = mutableListOf<StartRuntimeCall>()

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
        startRuntimeCalls += StartRuntimeCall(agentId, conversationId)
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

    data class StartRuntimeCall(
        val agentId: AgentId,
        val conversationId: ConversationId,
    )
}
