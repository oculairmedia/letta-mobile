package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-lgns8.22.1 — adversarial ownership / routing invariants.
 *
 * Characterization tests below document **current** (unsafe) Mutex force-unlock
 * behavior and stay green on CI. Desired-invariant tests are `@Ignore` until
 * [letta-mobile-lgns8.22.2] lands owner-token leases; un-ignore them in that
 * bead and require green.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnEngineOwnershipAdversarialTest {

    // ------------------------------------------------------------------
    // Characterization: current unsafe behavior (required green now)
    // ------------------------------------------------------------------

    @Test
    fun currentUnsafe_preflightOwnerWithNoRunIdIsForceReleasedWhenRunListIdle() =
        runTest(UnconfinedTestDispatcher()) {
            // A holds activeTurn during a hung runtime_start (preflight; no run_id).
            // B's busy rejection probes run.list (idle) and force-unlocks A.
            // Only the first runtimeStart hangs so B can proceed after stealing.
            val client = AdversarialClient(hangFirstRuntimeStartOnly = true, runListIdle = true)
            val engine = AppServerTurnEngine(client = client)

            val a = backgroundScope.launch {
                runCatching { engine.runTurn(cmd("a")).collect() }
            }
            runCurrent()
            client.awaitRuntimeStartGate()
            assertTrue(engine.isBusy, "A must own the lock while hung in runtime_start")
            assertEquals(null, engine.activeTurnOwner?.runId)

            var bAccepted = false
            backgroundScope.launch {
                runCatching {
                    engine.runTurn(cmd("b")).collect { bAccepted = true }
                }
            }
            runCurrent()

            assertTrue(client.runListQueried, "current reconciler falls back to run.list without run_id")
            assertTrue(
                bAccepted,
                "CURRENT UNSAFE: idle run.list force-releases a live preflight owner so B proceeds",
            )
            a.cancel()
        }

    @Test
    fun currentUnsafe_staleOwnerFinallyCanUnlockReplacementOwner() =
        runTest(UnconfinedTestDispatcher()) {
            // A is stuck mid-turn. B reconciles A as dead and acquires. When A is
            // then cancelled, A's finally calls Mutex.unlock() + clears owner —
            // which is now B — leaving the engine idle while B still thinks it owns.
            val client = AdversarialClient(runStatus = "failed")
            val engine = AppServerTurnEngine(client = client)

            val a = backgroundScope.launch {
                runCatching { engine.runTurn(cmd("a")).collect() }
            }
            runCurrent()
            client.emitAssistant("run-a")
            runCurrent()
            assertTrue(engine.isBusy)
            val aOwner = engine.activeTurnOwner
            assertEquals("run-a", aOwner?.runId)

            val bDrafts = mutableListOf<RuntimeEventDraft>()
            val b = backgroundScope.launch {
                runCatching { engine.runTurn(cmd("b")).collect { bDrafts += it } }
            }
            runCurrent()
            client.emitAssistant("run-b")
            runCurrent()
            assertTrue(bDrafts.isNotEmpty(), "B must acquire after dead-owner reconcile")
            assertEquals("run-b", engine.activeTurnOwner?.runId)

            // Cancel A — its finally unlocks whoever currently holds the Mutex.
            a.cancel()
            runCurrent()
            advanceUntilIdle()

            assertFalse(
                engine.isBusy,
                "CURRENT UNSAFE: A's finally unlocked B's lease; engine appears idle while B's job may still run",
            )
            b.cancel()
        }

    @Test
    fun characterization_exactlyOneTerminalOnCleanStop() = runTest(UnconfinedTestDispatcher()) {
        val client = AdversarialClient()
        val engine = AppServerTurnEngine(client = client)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = async {
            engine.runTurn(cmd("solo")).collect { drafts += it }
        }
        runCurrent()
        client.emitAssistant("run-1")
        runCurrent()
        client.emitStop("run-1", "end_turn")
        runCurrent()
        turn.await()

        val terminals = drafts.mapNotNull { it.payload as? RuntimeEventPayload.RunLifecycleChanged }
            .filter { it.status == RuntimeRunStatus.Completed || it.status == RuntimeRunStatus.Failed || it.status == RuntimeRunStatus.Cancelled }
        assertEquals(1, terminals.size, "clean stop must emit exactly one terminal lifecycle")
    }

    // ------------------------------------------------------------------
    // Desired invariants (un-ignore in lgns8.22.2+)
    // ------------------------------------------------------------------

    @Ignore("pending letta-mobile-lgns8.22.2 owner-token leases")
    @Test
    fun desired_preflightOwnerIsNeverForceReleasedByIdleRunList() =
        runTest(UnconfinedTestDispatcher()) {
            val client = AdversarialClient(hangFirstRuntimeStartOnly = true, runListIdle = true)
            val engine = AppServerTurnEngine(client = client)
            backgroundScope.launch { runCatching { engine.runTurn(cmd("a")).collect() } }
            runCurrent()
            client.awaitRuntimeStartGate()
            assertTrue(engine.isBusy)

            var bAccepted = false
            backgroundScope.launch {
                runCatching { engine.runTurn(cmd("b")).collect { bAccepted = true } }
            }
            runCurrent()
            assertFalse(bAccepted, "preflight owner without run_id must not be force-released via idle run.list")
            assertTrue(engine.isBusy)
            assertEquals(null, engine.activeTurnOwner?.runId)
        }

    @Ignore("pending letta-mobile-lgns8.22.2 / collectorReady-after-subscribe")
    @Test
    fun desired_inputIsNotSentBeforeCollectorSubscribes() =
        runTest(UnconfinedTestDispatcher()) {
            val client = AdversarialClient()
            val engine = AppServerTurnEngine(client = client)
            backgroundScope.launch { runCatching { engine.runTurn(cmd("solo")).collect() } }
            runCurrent()
            assertTrue(client.inputCalled)
            assertTrue(
                client.inputSentAfterEventsSubscribed,
                "input must wait until events.collect has subscribed",
            )
        }

    @Ignore("pending letta-mobile-lgns8.22.2 owner-token leases")
    @Test
    fun desired_staleOwnerFinallyCannotReleaseReplacementLease() =
        runTest(UnconfinedTestDispatcher()) {
            val client = AdversarialClient(runStatus = "failed")
            val engine = AppServerTurnEngine(client = client)
            val a = backgroundScope.launch { runCatching { engine.runTurn(cmd("a")).collect() } }
            runCurrent()
            client.emitAssistant("run-a")
            runCurrent()

            backgroundScope.launch { runCatching { engine.runTurn(cmd("b")).collect() } }
            runCurrent()
            client.emitAssistant("run-b")
            runCurrent()
            assertEquals("run-b", engine.activeTurnOwner?.runId)

            a.cancel()
            runCurrent()
            advanceUntilIdle()

            assertTrue(engine.isBusy, "replacement owner B must keep its lease after A finishes")
            assertEquals("run-b", engine.activeTurnOwner?.runId)
        }

    @Ignore("pending letta-mobile-lgns8.22.2 owner-token leases")
    @Test
    fun desired_thirdTurnExcludedWhileReplacementOwns() =
        runTest(UnconfinedTestDispatcher()) {
            val client = AdversarialClient(runStatus = "failed")
            val engine = AppServerTurnEngine(client = client)
            backgroundScope.launch { runCatching { engine.runTurn(cmd("a")).collect() } }
            runCurrent()
            client.emitAssistant("run-a")
            runCurrent()
            backgroundScope.launch { runCatching { engine.runTurn(cmd("b")).collect() } }
            runCurrent()
            client.emitAssistant("run-b")
            runCurrent()

            var cAccepted = false
            backgroundScope.launch {
                runCatching { engine.runTurn(cmd("c")).collect { cAccepted = true } }
            }
            // B still alive — reconciler must not release for C.
            client.runStatusOverride = "in_progress"
            runCurrent()
            assertFalse(cAccepted)
            assertEquals("run-b", engine.activeTurnOwner?.runId)
        }

    @Ignore("pending letta-mobile-lgns8.22.4 frame correlation")
    @Test
    fun desired_oldRunFramesDoNotReachReplacementTurn() =
        runTest(UnconfinedTestDispatcher()) {
            val client = AdversarialClient(runStatus = "failed")
            val engine = AppServerTurnEngine(client = client)
            backgroundScope.launch { runCatching { engine.runTurn(cmd("a")).collect() } }
            runCurrent()
            client.emitAssistant("run-a")
            runCurrent()

            val bDrafts = mutableListOf<RuntimeEventDraft>()
            backgroundScope.launch {
                runCatching { engine.runTurn(cmd("b")).collect { bDrafts += it } }
            }
            runCurrent()
            client.emitAssistant("run-b")
            runCurrent()
            bDrafts.clear()

            // Late frame from A's run must not appear as B drafts.
            client.emitAssistant("run-a")
            runCurrent()
            val crossed = bDrafts.any {
                (it.payload as? RuntimeEventPayload.RemoteStreamFrame)?.let { frame ->
                    frame.body.contains("run-a")
                } == true
            }
            assertFalse(crossed, "old-run frames must not route into the replacement turn")
        }

    private fun cmd(label: String) = TurnCommand(
        backendId = BackendId("iroh-node-server"),
        runtimeId = RuntimeId("iroh-node:agent-1:conv-1"),
        agentId = AgentId("agent-1"),
        conversationId = ConversationId("conv-1"),
        input = TurnInput.UserMessage(localMessageId = "local-$label", text = label),
    )

    /**
     * Controllable fake AppServerClient for ownership/routing adversarial cases.
     */
    private class AdversarialClient(
        private val hangFirstRuntimeStartOnly: Boolean = false,
        private val runListIdle: Boolean = false,
        runStatus: String = "in_progress",
    ) : AppServerClient {
        private val eventsHub = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        override val events: Flow<AppServerReceivedFrame> = eventsHub

        var runStatusOverride: String = runStatus
        var runGetQueried = false
        var runListQueried = false
        var inputCalled = false
        var inputSentAfterEventsSubscribed = false
        private var runtimeStartCount = 0
        private val runtimeStartGate = CompletableDeferred<Unit>()
        private val runtimeStartRelease = CompletableDeferred<Unit>()

        suspend fun awaitRuntimeStartGate() {
            runtimeStartGate.await()
        }

        fun releaseRuntimeStart() {
            runtimeStartRelease.complete(Unit)
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse {
            runtimeStartCount++
            if (hangFirstRuntimeStartOnly && runtimeStartCount == 1) {
                runtimeStartGate.complete(Unit)
                runtimeStartRelease.await()
            }
            return AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    requireNotNull(command.agentId),
                    requireNotNull(command.conversationId),
                ),
            )
        }

        override suspend fun input(command: AppServerCommand.Input) {
            inputCalled = true
            inputSentAfterEventsSubscribed = eventsHub.subscriptionCount.value > 0
        }

        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse {
            when (command.method) {
                "run.get" -> {
                    runGetQueried = true
                    return AppServerInboundFrame.AdminRpcResponse(
                        requestId = command.requestId,
                        success = true,
                        result = buildJsonObject { put("status", runStatusOverride) },
                    )
                }
                "run.list" -> {
                    runListQueried = true
                    val body = if (runListIdle) {
                        JsonArray(emptyList())
                    } else {
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("conversation_id", "conv-1")
                                    put("status", runStatusOverride)
                                },
                            ),
                        )
                    }
                    return AppServerInboundFrame.AdminRpcResponse(
                        requestId = command.requestId,
                        success = true,
                        result = body,
                    )
                }
                else -> {
                    // Preflight probes (agent.get / conversation.get / …) succeed empty.
                    return AppServerInboundFrame.AdminRpcResponse(
                        requestId = command.requestId,
                        success = true,
                        result = buildJsonObject {},
                    )
                }
            }
        }

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        fun emitAssistant(runId: String) {
            trackCollectorsAndEmit(
                AppServerInboundFrame.StreamDelta(
                    runtime = AppServerRuntimeScope("agent-1", "conv-1"),
                    eventSeq = 1,
                    emittedAt = "t",
                    idempotencyKey = "asst-$runId-${System.nanoTime()}",
                    delta = buildJsonObject {
                        put("message_type", "assistant_message")
                        put("run_id", runId)
                        put("content", "hello-$runId")
                    },
                ),
            )
        }

        fun emitStop(runId: String, stopReason: String) {
            trackCollectorsAndEmit(
                AppServerInboundFrame.StreamDelta(
                    runtime = AppServerRuntimeScope("agent-1", "conv-1"),
                    eventSeq = 2,
                    emittedAt = "t",
                    idempotencyKey = "stop-$runId",
                    delta = buildJsonObject {
                        put("message_type", "stop_reason")
                        put("run_id", runId)
                        put("stop_reason", stopReason)
                    },
                ),
            )
        }

        private fun trackCollectorsAndEmit(frame: AppServerInboundFrame) {
            eventsHub.tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream,
                    frame = frame,
                    raw = buildJsonObject {
                        put("type", frame.type ?: "stream_delta")
                        put("idempotency_key", "evt")
                        if (frame is AppServerInboundFrame.StreamDelta) put("delta", frame.delta)
                    },
                ),
            )
        }
    }
}
