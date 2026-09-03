package com.letta.mobile.cli.commands

import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.SubagentRegistrySource
import com.letta.mobile.data.controller.node.iroh.SubagentTodosSnapshot
import com.letta.mobile.data.controller.reconnect.ReconnectingClientState
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

class AppServerServeIrohProductionWiringTest {
    @Test
    fun `lifecycle cleanup cancels scope and runs cleanup upon cancellation`() = runTest {
        val command = AppServerServeIrohCommand()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var cleanupRan = false

        val job = launch {
            command.runWithLifecycleCleanup(
                scope = scope,
                cleanup = {
                    cleanupRan = true
                    scope.cancel()
                },
                shutdownHookRegistry = {},
                shutdownHookDeregistry = {},
                exitHandler = { error("unexpected exit") },
            ) {
                delay(1.hours)
            }
        }

        testScheduler.runCurrent()
        assertFalse(cleanupRan)
        assertTrue(scope.isActive)

        job.cancel()
        job.join()

        assertTrue(cleanupRan)
        assertFalse(scope.isActive)
    }

    @Test
    fun `lifecycle cleanup runs exactly once across exception and shutdown hook`() = runTest {
        val command = AppServerServeIrohCommand()
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val cleanupCount = java.util.concurrent.atomic.AtomicInteger(0)
        var registeredHook: Thread? = null

        val failure = runCatching {
            command.runWithLifecycleCleanup(
                scope = scope,
                cleanup = {
                    cleanupCount.incrementAndGet()
                    scope.cancel()
                },
                shutdownHookRegistry = { registeredHook = it },
                shutdownHookDeregistry = {},
                exitHandler = { throw IllegalStateException("process exited: $it") },
            ) {
                throw RuntimeException("partial initialization failure")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, cleanupCount.get())
        assertFalse(scope.isActive)

        // Simulate shutdown hook firing afterwards; cleanup must not run twice
        registeredHook?.run()
        assertEquals(1, cleanupCount.get())
    }
    @Test
    fun `runtime readiness waits for management lane readiness`() = runTest {
        val state = MutableStateFlow<ReconnectingClientState>(ReconnectingClientState.Connecting(attempt = 0))

        val waiting = async { awaitManagementLaneReady(state) }
        testScheduler.runCurrent()
        assertFalse(waiting.isCompleted)

        state.value = ReconnectingClientState.Ready
        waiting.await()
    }

    @Test
    fun `runtime readiness fails when management lane gives up`() = runTest {
        val state = MutableStateFlow<ReconnectingClientState>(ReconnectingClientState.GaveUp("auth rejected"))

        val failure = runCatching { awaitManagementLaneReady(state) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `production router always registers controller-native subagent routes`() {
        val controller = DefaultAppServerController(EmptyClient)

        val defaultRouter = buildProductionAdminRouter(controller = controller)
        val explicit = buildProductionAdminRouter(controller = controller, subagentRegistrySource = EmptySource)

        assertTrue(defaultRouter.registeredMethods.containsAll(AdminRpcRegistry.subagentMethods))
        assertTrue(explicit.registeredMethods.containsAll(AdminRpcRegistry.subagentMethods))
    }

    private object EmptySource : SubagentRegistrySource {
        override suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry> = emptyList()
        override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? = null
    }

    private object EmptyClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
    }
}
