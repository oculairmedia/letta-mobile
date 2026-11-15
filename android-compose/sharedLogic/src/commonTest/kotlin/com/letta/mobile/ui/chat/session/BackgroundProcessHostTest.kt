package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundProcessHostTest {
    private val runtime = AppServerRuntimeScope("a", "c")
    private fun snapshot(processes: String, conversation: String = "c") = AppServerProtocol.decodeFrame(
        """{"type":"update_device_status","event_seq":1,"emitted_at":"2026-11-15T00:00:00Z","idempotency_key":"status",
            "runtime":{"agent_id":"a","conversation_id":"$conversation"},
            "device_status":{"background_processes":$processes}}""",
    )

    @Test
    fun hostProcessesReachComposerAndStopWaitsForHostRemoval() = runTest(UnconfinedTestDispatcher()) {
        val frames = MutableSharedFlow<com.letta.mobile.data.transport.appserver.AppServerReceivedFrame>()
        val calls = mutableListOf<Pair<AppServerRuntimeScope, String>>()
        val host = backgroundProcessHost(backgroundScope, frames, MutableStateFlow(runtime)) { target, id -> calls += target to id }
        runCurrent()
        frames.emit(snapshot("""[{"id":"p","description":"worker"}]"""))
        runCurrent()
        val port = TimelineChatSessionPort(FakeTimelineChatSession(), TimelineChatTarget("a", "Ada", "c"), backgroundScope,
            TimelineChatSessionOptions(backgroundProcesses = host))
        backgroundScope.launch { port.composer.collect {} }
        runCurrent()
        assertEquals(listOf("p"), port.composer.value.backgroundProcesses.map { it.id })
        port.actions.stopBackgroundProcess("p")
        port.actions.stopBackgroundProcess("p")
        runCurrent()
        assertEquals(listOf(runtime to "p"), calls)
        runCurrent()
        assertEquals(listOf("p"), host.value.backgroundProcesses.map { it.id })
        runCurrent()
        frames.emit(snapshot("[]", "other"))
        runCurrent()
        assertEquals(1, host.value.backgroundProcesses.size)
        runCurrent()
        frames.emit(snapshot("[]"))
        runCurrent()
        assertTrue(host.value.backgroundProcesses.isEmpty())
    }

    @Test
    fun failedStopShowsErrorKeepsRowAndAllowsRetry() = runTest(UnconfinedTestDispatcher()) {
        val frames = MutableSharedFlow<com.letta.mobile.data.transport.appserver.AppServerReceivedFrame>()
        var calls = 0
        val host = backgroundProcessHost(backgroundScope, frames, MutableStateFlow(runtime)) { _, _ ->
            calls++
            error("host refused")
        }
        val port = TimelineChatSessionPort(FakeTimelineChatSession(), TimelineChatTarget("a", "Ada", "c"), backgroundScope,
            TimelineChatSessionOptions(backgroundProcesses = host))
        backgroundScope.launch { port.composer.collect {} }
        runCurrent()
        frames.emit(snapshot("""[{"id":"p"}]"""))
        runCurrent()
        port.actions.stopBackgroundProcess("p")
        runCurrent()
        assertEquals("host refused", port.composer.value.error)
        runCurrent()
        assertEquals(1, host.value.backgroundProcesses.size)
        port.actions.clearComposerError()
        runCurrent()
        assertEquals(null, host.value.backgroundProcessError)
        host.value.stopBackgroundProcess?.invoke("p")
        runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun targetChangeClearsShelfAndRetiresOldActions() = runTest(UnconfinedTestDispatcher()) {
        val frames = MutableSharedFlow<com.letta.mobile.data.transport.appserver.AppServerReceivedFrame>()
        val targets = MutableStateFlow<AppServerRuntimeScope?>(runtime)
        var calls = 0
        val host = backgroundProcessHost(backgroundScope, frames, targets) { _, _ -> calls++ }
        runCurrent()
        frames.emit(snapshot("""[{"id":"p"}]"""))
        runCurrent()
        val oldStop = host.value.stopBackgroundProcess
        targets.value = AppServerRuntimeScope("a", "next")
        oldStop?.invoke("p")
        runCurrent()
        assertEquals(0, calls)
        runCurrent()
        assertTrue(host.value.backgroundProcesses.isEmpty())
    }
    @Test
    fun newerConnectionRetiresPendingStopsAndRejectsOldSnapshots() = runTest(UnconfinedTestDispatcher()) {
        val frames = MutableSharedFlow<com.letta.mobile.data.transport.appserver.AppServerReceivedFrame>()
        var calls = 0
        val host = backgroundProcessHost(backgroundScope, frames, MutableStateFlow(runtime)) { _, _ -> calls++ }
        runCurrent()
        frames.emit(snapshot("""[{"id":"p"}]""").copy(connectionGeneration = 10))
        runCurrent()
        host.value.stopBackgroundProcess?.invoke("p")
        runCurrent()
        frames.emit(snapshot("[]").copy(connectionGeneration = 9))
        frames.emit(snapshot("[]"))
        runCurrent()
        assertEquals(listOf("p"), host.value.backgroundProcesses.map { it.id })
        runCurrent()
        frames.emit(snapshot("""[{"id":"p"}]""").copy(connectionGeneration = 11))
        runCurrent()
        host.value.stopBackgroundProcess?.invoke("p")
        runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun concurrentClicksSendOneStop() = runTest {
        val frames = MutableSharedFlow<com.letta.mobile.data.transport.appserver.AppServerReceivedFrame>()
        val calls = atomic(0)
        val stopped = CompletableDeferred<Unit>()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Default)
        val host = backgroundProcessHost(scope, frames, MutableStateFlow(runtime)) { _, _ ->
            calls.incrementAndGet()
            stopped.complete(Unit)
        }
        frames.subscriptionCount.first { it > 0 }
        frames.emit(snapshot("""[{"id":"p"}]"""))
        val stop = checkNotNull(host.first { it.backgroundProcesses.isNotEmpty() }.stopBackgroundProcess)
        withContext(Dispatchers.Default) {
            coroutineScope { repeat(32) { launch { stop("p") } } }
        }
        stopped.await()
        assertEquals(1, calls.value)
    }

}
