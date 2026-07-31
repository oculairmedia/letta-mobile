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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-8xxzv — TWO CONCURRENT RUNTIMES THROUGH ONE TURN ENGINE.
 *
 * Reported on device 2026-07-31: sending into another agent's conversation while
 * one conversation was streaming surfaced "Iroh App Server turn engine is
 * already busy." + a failed TurnDone. The busy string is CLIENT-minted: the
 * engine held ONE process-wide `activeLeaseRef`, so the second conversation was
 * refused before a byte reached the server. This is the last unkeyed slot of the
 * letta-mobile-or40x defect class (transport keyed in PR #1055, coordinator in
 * PR #1056).
 *
 * SERVER CONTRACT (letta-code 0.29.9 and 0.29.12, verified identical):
 * `src/websocket/listener/runtime.ts` keeps a `conversationRuntimes` map with a
 * per-conversation TurnLifecycle/queue/pump, and `message-router.ts` routes
 * `create_message` with no global gate. docs.letta.com (platform/app-server/
 * integration-patterns) mandates AT MOST ONE active turn per
 * `{agent_id, conversation_id}` runtime, PARALLEL across runtimes. Both halves
 * are asserted here: different keys run concurrently, the SAME key is refused.
 *
 * Determinism: virtual time only (UnconfinedTestDispatcher + runCurrent /
 * advanceUntilIdle) and frame-driven awaits — never a wall-clock sleep. Cases
 * that need the idle watchdog inject the engine clock (`nowMs`) so the watchdog
 * runs on the SAME virtual timeline — see case (6) and letta-mobile-465hq.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineConcurrentConversationsTest {

    // ------------------------------------------------------------------ (1)

    @Test
    fun twoConversationsRunConcurrentlyThroughOneEngineAndBothReachTerminal() =
        runTest(UnconfinedTestDispatcher()) {
            val client = ConcurrentClient()
            val engine = AppServerTurnEngine(client = client)
            val draftsA = mutableListOf<RuntimeEventDraft>()
            val draftsB = mutableListOf<RuntimeEventDraft>()
            val failures = mutableListOf<Throwable>()

            val turnA = backgroundScope.async {
                runCatching { engine.runTurn(commandA).collect { draftsA += it } }
                    .onFailure { failures += it }
            }
            runCurrent()
            val turnB = backgroundScope.async {
                runCatching { engine.runTurn(commandB).collect { draftsB += it } }
                    .onFailure { failures += it }
            }
            runCurrent()

            assertTrue(
                failures.isEmpty(),
                "a turn on a DIFFERENT {agent, conversation} runtime must never be refused; got $failures",
            )
            assertTrue(engine.isBusy(AGENT, CONV_A), "A holds its own lease")
            assertTrue(engine.isBusy(AGENT, CONV_B), "B holds its own lease CONCURRENTLY with A")
            assertEquals(
                setOf(TurnRuntimeKey(AGENT, CONV_A), TurnRuntimeKey(AGENT, CONV_B)),
                engine.busyRuntimeKeys().toSet(),
                "both runtimes must hold leases at the same time",
            )
            // Both sends genuinely reached the server, in parallel.
            assertEquals(
                listOf(CONV_A, CONV_B).sorted(),
                client.inputConversations.sorted(),
                "each concurrent turn must send its own create_message",
            )

            // Both stream, then both settle on their own terminal.
            client.emit(delta(CONV_A, "assistant_message", "run-a", content = "A reply", seq = 1))
            client.emit(delta(CONV_B, "assistant_message", "run-b", content = "B reply", seq = 2))
            runCurrent()
            client.emit(delta(CONV_A, "stop_reason", "run-a", seq = 3))
            client.emit(delta(CONV_B, "stop_reason", "run-b", seq = 4))
            advanceTimeBy(DEFAULT_SETTLE_DRAIN_MS)
            runCurrent()
            turnA.await()
            turnB.await()

            assertTrue(terminalStatuses(draftsA).contains(RuntimeRunStatus.Completed), "A must reach a terminal")
            assertTrue(terminalStatuses(draftsB).contains(RuntimeRunStatus.Completed), "B must reach a terminal")
            assertFalse(engine.isAnyBusy, "both leases are released once both turns settle")
        }

    // ------------------------------------------------------------------ (2)

    @Test
    fun secondTurnForTheSameConversationIsStillRejectedBusy() =
        runTest(UnconfinedTestDispatcher()) {
            val client = ConcurrentClient(runStatus = "in_progress")
            val engine = AppServerTurnEngine(client = client)
            val failures = mutableListOf<Throwable>()

            val turnA = backgroundScope.async {
                runCatching { engine.runTurn(commandA).collect() }
            }
            runCurrent()
            client.emit(delta(CONV_A, "assistant_message", "run-a", content = "A reply", seq = 1))
            runCurrent()

            // Same {agent, conversation}: letta-code allows at most ONE active
            // turn per runtime, so this must still be refused (the reconciler
            // proves the owner run is alive before giving up).
            var sameKeyAccepted = false
            backgroundScope.launch {
                runCatching {
                    engine.runTurn(commandA.copy(input = TurnInput.UserMessage("local-a2", "again")))
                        .collect { sameKeyAccepted = true }
                }.onFailure { failures += it }
            }
            runCurrent()

            assertFalse(sameKeyAccepted, "a second turn for the SAME runtime key must not run")
            assertTrue(
                failures.any { it is IllegalStateException },
                "the same-key collision must still fail fast; got $failures",
            )
            assertTrue(engine.isBusy(AGENT, CONV_A), "A's original lease survives the rejected retry")

            // ... while a DIFFERENT conversation is admitted at the same moment.
            var otherAccepted = false
            backgroundScope.launch {
                runCatching { engine.runTurn(commandB).collect { otherAccepted = true } }
                    .onFailure { failures += it }
            }
            runCurrent()
            assertTrue(otherAccepted, "a different runtime key must be admitted while A is busy")
            turnA.cancel()
        }

    // ------------------------------------------------------------------ (3)

    @Test
    fun livenessReleaseOfOneConversationDoesNotReleaseAnothersLease() =
        runTest(UnconfinedTestDispatcher()) {
            // run.get reports every probed run dead, so A's stale lease is
            // reconciled away by A's own retry. B must be untouched.
            val client = ConcurrentClient(runStatus = "failed")
            val engine = AppServerTurnEngine(client = client)
            val draftsB = mutableListOf<RuntimeEventDraft>()

            backgroundScope.launch { runCatching { engine.runTurn(commandA).collect() } }
            runCurrent()
            client.emit(delta(CONV_A, "assistant_message", "run-a", content = "A reply", seq = 1))
            runCurrent()
            backgroundScope.launch { runCatching { engine.runTurn(commandB).collect { draftsB += it } } }
            runCurrent()
            client.emit(delta(CONV_B, "assistant_message", "run-b", content = "B reply", seq = 2))
            runCurrent()
            assertTrue(engine.isBusy(AGENT, CONV_A) && engine.isBusy(AGENT, CONV_B))

            // A's retry drives the liveness reconciler, which cancels + replaces
            // A's dead lease.
            var replacementAccepted = false
            val replacement = backgroundScope.launch {
                runCatching {
                    engine.runTurn(commandA.copy(input = TurnInput.UserMessage("local-a2", "retry")))
                        .collect { replacementAccepted = true }
                }
            }
            runCurrent()

            assertTrue(replacementAccepted, "the provably-dead owner run must be reconciled for its OWN key")
            assertTrue(client.runGetRunIds.contains("run-a"), "the reconciler probed A's run")
            assertFalse(
                client.runGetRunIds.contains("run-b"),
                "the reconciler must never probe another conversation's run; probed=${client.runGetRunIds}",
            )
            assertTrue(engine.isBusy(AGENT, CONV_B), "B's lease must survive A's liveness release")

            // B keeps receiving its own frames after A's reconcile.
            client.emit(delta(CONV_B, "assistant_message", "run-b", content = "B again", seq = 3))
            runCurrent()
            assertTrue(
                bodies(draftsB).any { it.contains("B again") },
                "B's turn must keep receiving frames after A's lease was reconciled",
            )

            // End A's replacement so only B's turn remains in flight (its idle
            // watchdog is wall-clock based, so leaving it running would make the
            // virtual-time drain below nondeterministic — letta-mobile-465hq).
            replacement.cancel()
            runCurrent()
            assertTrue(engine.isBusy(AGENT, CONV_B), "B is untouched by A's teardown too")

            // And B still settles normally on its own terminal afterwards.
            client.emit(delta(CONV_B, "stop_reason", "run-b", seq = 4))
            runCurrent()
            // Drive the terminal-settle quiet window explicitly. advanceUntilIdle
            // cannot be used while another turn's wall-clock idle watchdog is
            // parked on a virtual delay (letta-mobile-465hq).
            advanceTimeBy(DEFAULT_SETTLE_DRAIN_MS)
            runCurrent()
            assertTrue(
                terminalStatuses(draftsB).contains(RuntimeRunStatus.Completed),
                "B must still reach its own terminal after A's lease was reconciled; draftsB=" +
                    draftsB.map { it.payload::class.simpleName to it.payload.toString().take(80) },
            )
            assertFalse(engine.isBusy(AGENT, CONV_B), "B releases its own lease on its own terminal")
        }

    // ------------------------------------------------------------------ (4)

    @Test
    fun framesRouteOnlyToTheirOwnRuntimeKeyWhileTwoTurnsRunConcurrently() =
        runTest(UnconfinedTestDispatcher()) {
            val client = ConcurrentClient()
            val engine = AppServerTurnEngine(client = client)
            val draftsA = mutableListOf<RuntimeEventDraft>()
            val draftsB = mutableListOf<RuntimeEventDraft>()

            val turnA = backgroundScope.async {
                runCatching { engine.runTurn(commandA).collect { draftsA += it } }
            }
            runCurrent()
            val turnB = backgroundScope.async {
                runCatching { engine.runTurn(commandB).collect { draftsB += it } }
            }
            runCurrent()

            client.emit(delta(CONV_A, "assistant_message", "run-a", content = "A reply", seq = 1))
            client.emit(delta(CONV_B, "assistant_message", "run-b", content = "B reply", seq = 2))
            runCurrent()

            assertTrue(bodies(draftsA).any { it.contains("A reply") }, "A's turn must receive A's frame")
            assertTrue(bodies(draftsB).any { it.contains("B reply") }, "B's turn must receive B's frame")
            assertFalse(bodies(draftsA).any { it.contains("B reply") }, "A's turn must never receive B's frames")
            assertFalse(bodies(draftsB).any { it.contains("A reply") }, "B's turn must never receive A's frames")

            // A's terminal must settle A only — B keeps streaming.
            client.emit(delta(CONV_A, "stop_reason", "run-a", seq = 3))
            advanceTimeBy(DEFAULT_SETTLE_DRAIN_MS)
            runCurrent()
            turnA.await()
            assertFalse(engine.isBusy(AGENT, CONV_A), "A settled on its own terminal")
            assertTrue(engine.isBusy(AGENT, CONV_B), "A's terminal must not settle B")
            assertFalse(
                terminalStatuses(draftsB).isNotEmpty(),
                "B must not have been terminated by A's terminal; got ${terminalStatuses(draftsB)}",
            )

            client.emit(delta(CONV_B, "stop_reason", "run-b", seq = 4))
            advanceTimeBy(DEFAULT_SETTLE_DRAIN_MS)
            runCurrent()
            turnB.await()
            assertTrue(terminalStatuses(draftsB).contains(RuntimeRunStatus.Completed), "B settles on its own terminal")
        }

    // ------------------------------------------------------------------ (5)

    @Test
    fun liveLeaseSurvivesManyOtherRuntimeKeysExceedingTheEntryCap() =
        runTest(UnconfinedTestDispatcher()) {
            val client = ConcurrentClient()
            val engine = AppServerTurnEngine(client = client)

            val live = backgroundScope.async { runCatching { engine.runTurn(commandA).collect() } }
            runCurrent()
            assertTrue(engine.isBusy(AGENT, CONV_A))

            // Far more than the cap (32), each starting and immediately settling
            // so the entries are evictable — the live entry must never be one.
            repeat(40) { index ->
                val other = commandA.copy(
                    conversationId = ConversationId("conv-filler-$index"),
                    input = TurnInput.UserMessage("local-filler-$index", "filler"),
                )
                val job = backgroundScope.launch { runCatching { engine.runTurn(other).collect() } }
                runCurrent()
                job.cancel()
                runCurrent()
            }

            assertTrue(
                engine.isBusy(AGENT, CONV_A),
                "the bounded key map must never evict a runtime key that still holds a live lease",
            )
            live.cancel()
        }

    // ------------------------------------------------------------------ (6)

    @Test
    fun idleWatchdogFailsOnlyTheIdleConversationAndLeavesTheActiveOneRunning() =
        runTest(UnconfinedTestDispatcher()) {
            // letta-mobile-465hq: previously impossible — the watchdog read the wall
            // clock, so it could not be driven from virtual time. With the injected
            // clock the watchdog measures the SAME timeline `advanceTimeBy` moves,
            // so per-key watchdog isolation is now assertable deterministically.
            val client = ConcurrentClient()
            val engine = AppServerTurnEngine(
                client = client,
                turnIdleTimeoutMs = IDLE_WINDOW_MS,
                nowMs = { testScheduler.currentTime },
            )
            val draftsA = mutableListOf<RuntimeEventDraft>()
            val draftsB = mutableListOf<RuntimeEventDraft>()

            backgroundScope.launch { runCatching { engine.runTurn(commandA).collect { draftsA += it } } }
            runCurrent()
            backgroundScope.launch { runCatching { engine.runTurn(commandB).collect { draftsB += it } } }
            runCurrent()
            assertTrue(engine.isBusy(AGENT, CONV_A) && engine.isBusy(AGENT, CONV_B))

            // Halfway through the window B streams (resetting only B's idle timer)
            // while A stays silent.
            advanceTimeBy(IDLE_WINDOW_MS / 2)
            client.emit(delta(CONV_B, "assistant_message", "run-b", content = "B reply", seq = 1))
            runCurrent()

            // Past A's window, still inside B's refreshed one.
            advanceTimeBy(IDLE_WINDOW_MS / 2 + IDLE_WINDOW_MS / 4)
            runCurrent()

            assertTrue(
                terminalStatuses(draftsA).contains(RuntimeRunStatus.Failed),
                "A's idle watchdog must force-fail A; got ${terminalStatuses(draftsA)}",
            )
            assertFalse(engine.isBusy(AGENT, CONV_A), "A's lease is released by its own watchdog")
            assertTrue(
                terminalStatuses(draftsB).isEmpty(),
                "B's turn must be untouched by A's watchdog; got ${terminalStatuses(draftsB)}",
            )
            assertTrue(engine.isBusy(AGENT, CONV_B), "B keeps its lease while it is still streaming")

            // B still settles on its own terminal afterwards.
            client.emit(delta(CONV_B, "stop_reason", "run-b", seq = 2))
            advanceTimeBy(DEFAULT_SETTLE_DRAIN_MS)
            runCurrent()
            assertTrue(
                terminalStatuses(draftsB).contains(RuntimeRunStatus.Completed),
                "B must reach its own Completed terminal; got ${terminalStatuses(draftsB)}",
            )
            assertFalse(engine.isAnyBusy, "both leases are released once both turns end")
        }

    // ---------------------------------------------------------------- helpers

    private fun terminalStatuses(drafts: List<RuntimeEventDraft>): List<RuntimeRunStatus> =
        drafts.mapNotNull { it.payload as? RuntimeEventPayload.RunLifecycleChanged }
            .map { it.status }
            .filter {
                it == RuntimeRunStatus.Completed ||
                    it == RuntimeRunStatus.Failed ||
                    it == RuntimeRunStatus.Cancelled
            }

    private fun bodies(drafts: List<RuntimeEventDraft>): List<String> =
        drafts.mapNotNull { it.payload as? RuntimeEventPayload.RemoteStreamFrame }.map { it.body }

    private companion object {
        /** Comfortably longer than the engine's 1.5s terminal-settle window. */
        const val DEFAULT_SETTLE_DRAIN_MS = 5_000L

        /** Idle window used by the watchdog-isolation case (virtual ms). */
        const val IDLE_WINDOW_MS = 4_000L
        const val AGENT = "agent-1"
        const val CONV_A = "conv-a"
        const val CONV_B = "conv-b"

        val commandA = TurnCommand(
            backendId = BackendId("iroh-app-server"),
            runtimeId = RuntimeId("iroh:session"),
            agentId = AgentId(AGENT),
            conversationId = ConversationId(CONV_A),
            input = TurnInput.UserMessage(localMessageId = "local-a", text = "hi A"),
        )
        val commandB = commandA.copy(
            conversationId = ConversationId(CONV_B),
            input = TurnInput.UserMessage(localMessageId = "local-b", text = "hi B"),
        )

        fun delta(
            conversationId: String,
            messageType: String,
            runId: String,
            seq: Long,
            content: String? = null,
        ): AppServerInboundFrame.StreamDelta = AppServerInboundFrame.StreamDelta(
            runtime = AppServerRuntimeScope(AGENT, conversationId),
            eventSeq = seq,
            emittedAt = "2026-07-31T00:00:00Z",
            idempotencyKey = "evt-$conversationId-$seq",
            delta = buildJsonObject {
                put("message_type", messageType)
                put("run_id", runId)
                if (content != null) put("content", content)
            },
        )
    }

    /**
     * One App Server client shared by both concurrent turns — the production
     * topology (a single `/ws` socket carrying every runtime's frames).
     * `input` returns immediately, so a turn streams until the test drives its
     * own terminal.
     */
    private class ConcurrentClient(private val runStatus: String = "in_progress") : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 128)

        val inputConversations = mutableListOf<String>()
        val runGetRunIds = mutableListOf<String>()

        override suspend fun runtimeStart(
            command: AppServerCommand.RuntimeStart,
        ): AppServerInboundFrame.RuntimeStartResponse = AppServerInboundFrame.RuntimeStartResponse(
            requestId = command.requestId,
            success = true,
            runtime = AppServerRuntimeScope(
                requireNotNull(command.agentId),
                requireNotNull(command.conversationId),
            ),
        )

        override suspend fun input(command: AppServerCommand.Input) {
            inputConversations += command.runtime.conversationId
        }

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("sync unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("abort unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse {
            when (command.method) {
                "run.get" -> {
                    command.params?.get("run_id")?.let { runGetRunIds += it.toString().trim('"') }
                    return AppServerInboundFrame.AdminRpcResponse(
                        requestId = command.requestId,
                        success = true,
                        result = buildJsonObject { put("status", runStatus) },
                    )
                }
                "run.list" -> return AppServerInboundFrame.AdminRpcResponse(
                    requestId = command.requestId,
                    success = true,
                    result = JsonArray(emptyList()),
                )
            }
            return AppServerInboundFrame.AdminRpcResponse(
                requestId = command.requestId,
                success = false,
                error = "unexpected",
            )
        }

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        fun emit(frame: AppServerInboundFrame.StreamDelta) {
            (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream,
                    frame = frame,
                    raw = buildJsonObject {
                        put("type", frame.type ?: "stream_delta")
                        put("idempotency_key", frame.idempotencyKey)
                        put("delta", frame.delta)
                    },
                ),
            )
        }
    }
}
