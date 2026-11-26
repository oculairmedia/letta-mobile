package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.AGENT
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.FUTURE_STATUS
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.RUN
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.SILENCE_CHECK_SLACK_MS
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.assistant
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.childSubagentDelta
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.decode
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.delta
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.frame
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.futureLoopStatus
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.idleLoopStatus
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.loopStatus
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.serverStartedTurn
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.stop
import com.letta.mobile.data.controller.node.iroh.ServerStartedTurnFixtures.turnFinished
import com.letta.mobile.data.model.RunId
import com.letta.mobile.data.runtime.LoopPhase
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a busy server-started turn (one running a client-side tool or waiting for approval) ends when its
 * `turn_finished` is lost: on the App Server's own idle evidence, on a new connection generation, or on the silence
 * bound after a non-waiting loop status, and never on silence alone while it legitimately waits.
 */
class ServerStartedTurnIdleBoundTest {
    /** A turn running a client-side tool: its prompt, the tool call, and the server's EXECUTING_CLIENT_SIDE_TOOL. */
    private fun busyTurn(): List<AppServerReceivedFrame> = listOf(
        serverStartedTurn()[0],
        decode(frame(delta(mapOf("message_type" to "tool_call_message", "run_id" to RUN.value, "id" to "tool-call-toolu_lost")))),
        decode(loopStatus(LoopPhase.ExecutingClientTool, listOf(RUN))),
    )

    /**
     * A busy turn loses its `turn_finished`. Silence alone never closes it (the tool may legitimately run for hours),
     * but any later loop status that is not a long wait, here the idle one the server sends right before
     * `turn_finished`, ends the tool exemption: the turn closes within the silence bound after it, as not truly ended.
     * This pins behaviour the relay already had before the idle status was read as evidence (any non-waiting status
     * cleared the exemption); it is not evidence of the later change.
     */
    @Test
    fun aBusyTurnWhoseFinishIsLostClosesWithinTheBoundAfterANonWaitingLoopStatus() = runTest {
        val node = startedTurnNode { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        runCurrent()

        advanceTimeBy(2 * SERVER_TURN_SILENCE_MS)
        runCurrent()
        assertStillOpen(node)

        node.frames.emit(decode(toolReturn()))
        node.frames.emit(decode(idleLoopStatus()))
        advanceTimeBy(SERVER_TURN_SILENCE_MS + SILENCE_CHECK_SLACK_MS)
        runCurrent()

        val closed = journalEntries(node.journal).last()
        assertEquals("turn_closed", closed.kind, "${journalEntries(node.journal).map { it.kind }}")
        assertEquals("false", closed.payload?.get("terminal_observed").toString(), "no terminal was seen")
    }

    /**
     * A busy turn's tool returns, its `turn_finished` is lost, the server says the runtime went idle, and the server's
     * next turn starts at once: that turn's first delta (a new run) closes the lost one, as not truly ended, and opens
     * its own turn instead of being folded into the lost one.
     */
    @Test
    fun aBusyTurnWhoseFinishIsLostClosesWhenTheNextServerTurnStartsAfterIdle() = runTest {
        val node = startedTurnNode { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        advanceTimeBy(2 * SERVER_TURN_SILENCE_MS)
        runCurrent()
        assertStillOpen(node)

        node.frames.emit(decode(toolReturn()))
        node.frames.emit(decode(idleLoopStatus()))
        node.frames.emit(decode(assistant("Next notification handled.", run = NEXT_RUN)))
        runCurrent()

        val journal = journalEntries(node.journal)
        val turns = journal.map { it.turn }.distinct()
        assertEquals(2, turns.size, "${journal.map { it.turn to it.kind }}")
        val first = journal.filter { it.turn == turns[0] }
        assertEquals("turn_closed", first.last().kind)
        assertEquals("false", first.last().payload?.get("terminal_observed").toString())
        assertEquals(listOf("turn_open", "stream_delta"), journal.filter { it.turn == turns[1] }.map { it.kind })
    }

    /**
     * The App Server sends its idle loop status just BEFORE `turn_finished` when a turn ends (letta-code
     * finishListenerTurn: emitRuntimeStateUpdates, then emitDurableTurnFinished). A turn that ends normally still
     * closes on its `turn_finished`, as truly ended, with no second turn.
     */
    @Test
    fun anIdleLoopStatusRightBeforeTurnFinishedStillEndsTheTurnAsTrulyEnded() = runTest {
        val node = startedTurnNode { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        node.frames.emit(decode(loopStatus(LoopPhase.ProcessingResponse, listOf(RUN))))
        node.frames.emit(decode(idleLoopStatus()))
        node.frames.emit(turnFinished())
        advanceTimeBy(2 * SERVER_TURN_SILENCE_MS)
        runCurrent()

        val journal = journalEntries(node.journal)
        assertEquals(1, journal.count { it.kind == "turn_closed" }, "${journal.map { it.kind }}")
        assertEquals("true", journal.last().payload?.get("terminal_observed").toString())
    }

    /**
     * An aborted turn: the server says idle as soon as the abort is requested (letta-code requestCancellation) and
     * sends the turn's remaining frames and `turn_finished` once the cancelled tool settled. Those stay one turn, closed
     * as truly ended, when they arrive within the silence bound after the idle status, however long the tool ran.
     */
    @Test
    fun anAbortedBusyTurnSettlingAfterItsIdleStatusStaysOneTurn() = runTest {
        val node = startedTurnNode { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        advanceTimeBy(2 * SERVER_TURN_SILENCE_MS)
        node.frames.emit(decode(idleLoopStatus()))
        runCurrent()

        advanceTimeBy(SERVER_TURN_SILENCE_MS / 2)
        listOf(decode(toolReturn()), decode(stop(RUN, reason = ServerStopReason.Cancelled)), turnFinished())
            .forEach { node.frames.emit(it) }
        runCurrent()

        val journal = journalEntries(node.journal)
        assertEquals(1, journal.count { it.kind == "turn_open" }, "${journal.map { it.kind }}")
        assertEquals("true", journal.last().payload?.get("terminal_observed").toString())
    }

    /**
     * The idle status the server can send between approval-continuation rounds (after a `requires_approval` stop) is
     * not the end of the turn: the same rule a client turn's boundary gate applies. The next round's run then stays in
     * this turn instead of closing it.
     */
    @Test
    fun anIdleBetweenApprovalRoundsDoesNotEndTheTurn() = runTest {
        val node = startedTurnNode { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        node.frames.emit(decode(stop(RUN, reason = ServerStopReason.RequiresApproval)))
        node.frames.emit(decode(idleLoopStatus()))
        node.frames.emit(decode(assistant("Approved; continuing.", run = NEXT_RUN)))
        node.frames.emit(decode(stop(NEXT_RUN)))
        node.frames.emit(turnFinished(NEXT_RUN))
        runCurrent()

        val journal = journalEntries(node.journal)
        assertEquals(1, journal.count { it.kind == "turn_open" }, "${journal.map { it.kind }}")
        assertEquals("true", journal.last().payload?.get("terminal_observed").toString())
    }

    /**
     * While a server-started turn runs, the server's loop status may name no run (before the run's first chunk, or
     * after an approval-only continuation cleared it) but is then not idle. A delta of another run (a client's run the
     * server started after this turn) does not close the turn as "server idle".
     */
    @Test
    fun aRunlessButBusyLoopStatusDoesNotArmTheIdleClose() = runTest {
        assertForeignRunKeepsTheBusyTurn(decode(loopStatus(LoopPhase.ProcessingResponse, emptyList())))
    }

    /**
     * The server says WAITING_ON_INPUT while it still names an active run: a turn is still running, so the runtime is
     * not idle. A delta of another run then does not close the turn as "server idle".
     */
    @Test
    fun aWaitingOnInputStatusThatStillNamesAnActiveRunDoesNotArmTheIdleClose() = runTest {
        assertForeignRunKeepsTheBusyTurn(decode(loopStatus(LoopPhase.WaitingOnInput, listOf(RUN))))
    }

    /**
     * While the parent turn is idle (aborted and settling), a child agent's delta carries a run of its own. It is not
     * the parent's next turn, so it neither closes the turn as "server idle" nor is relayed; the parent's own
     * `turn_finished` then still closes the one turn as truly ended.
     */
    @Test
    fun aChildAgentDeltaOfAnotherRunDoesNotCloseAnIdleParentTurn() = runTest {
        val node = startedTurnNode { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        node.frames.emit(decode(idleLoopStatus()))
        node.frames.emit(childSubagentDelta())
        runCurrent()

        assertStillOpen(node)

        node.frames.emit(decode(toolReturn()))
        node.frames.emit(turnFinished())
        runCurrent()

        val journal = journalEntries(node.journal)
        assertEquals(1, journal.count { it.kind == "turn_open" }, "${journal.map { it.kind }}")
        assertEquals(1, journal.count { it.kind == "turn_closed" }, "${journal.map { it.kind }}")
        assertEquals("true", journal.last().payload?.get("terminal_observed").toString())
    }

    /** A pending approval is a wait too: silence alone keeps the turn open. */
    @Test
    fun anApprovalWaitKeepsABusyTurnOpenThroughSilence() = runTest {
        val node = startedTurnNode { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        node.frames.emit(decode(loopStatus(LoopPhase.WaitingOnApproval, listOf(RUN))))
        runCurrent()

        advanceTimeBy(2 * SERVER_TURN_SILENCE_MS)
        runCurrent()

        assertTrue(journalEntries(node.journal).none { it.kind == "turn_closed" }, "${journalEntries(node.journal).map { it.kind }}")
    }

    /**
     * A busy turn whose App Server connection was replaced: the new generation shows on a frame of another runtime
     * (this runtime sends nothing again), and that alone closes the turn, as not truly ended. Silence before it does not.
     */
    @Test
    fun aBusyTurnWhoseFinishIsLostClosesWhenAnyFrameShowsANewConnectionGeneration() = runTest {
        val node = startedTurnNode { emptySet() }
        busyTurn().forEach { node.frames.emit(it.copy(connectionGeneration = 1L)) }
        runCurrent()

        advanceTimeBy(2 * SERVER_TURN_SILENCE_MS)
        runCurrent()
        assertStillOpen(node)

        node.frames.emit(decode(otherRuntimeLoopStatus()).copy(connectionGeneration = 2L))
        runCurrent()

        val closed = journalEntries(node.journal).last()
        assertEquals("turn_closed", closed.kind, "${journalEntries(node.journal).map { it.kind }}")
        assertEquals("false", closed.payload?.get("terminal_observed").toString())
    }

    /** A loop status this relay does not know is "not waiting" (the silence bound applies) and is logged once by name. */
    @Test
    fun anUnknownLoopStatusIsNotWaitingAndIsLoggedOnceByName() = runTest {
        val recorder = com.letta.mobile.util.TelemetryRecorder()
        com.letta.mobile.util.Telemetry.installRecorder(recorder).use {
            val node = startedTurnNode { emptySet() }
            busyTurn().forEach { node.frames.emit(it) }
            node.frames.emit(decode(futureLoopStatus(RUN)))
            node.frames.emit(decode(futureLoopStatus(RUN)))
            runCurrent()

            advanceTimeBy(SERVER_TURN_SILENCE_MS + SILENCE_CHECK_SLACK_MS)
            runCurrent()

            val closed = journalEntries(node.journal).last()
            assertEquals(
                "turn_closed",
                closed.kind,
                "an unknown status does not exempt the turn: ${journalEntries(node.journal).map { it.kind }}",
            )
            assertEquals("false", closed.payload?.get("terminal_observed").toString())
            val logged = recorder.snapshot().filter {
                it.name == "turn.server_started_unknown_loop_status" && it.attrs["status"]?.toString() == FUTURE_STATUS
            }
            assertEquals(1, logged.size, "logged once by name: ${recorder.snapshot().map { it.name }}")
        }
    }

    /** After [statusFrame] a delta of [NEXT_RUN] arrives on the busy turn: it stays the one open turn. */
    private suspend fun TestScope.assertForeignRunKeepsTheBusyTurn(statusFrame: AppServerReceivedFrame) {
        val node = startedTurnNode { emptySet() }
        busyTurn().forEach { node.frames.emit(it) }
        node.frames.emit(statusFrame)
        node.frames.emit(decode(assistant("Still the same turn.", run = NEXT_RUN)))
        runCurrent()

        val kinds = journalEntries(node.journal).map { it.kind }
        assertEquals(1, kinds.count { it == "turn_open" }, "$kinds")
        assertTrue("turn_closed" !in kinds, "$kinds")
    }

    private fun assertStillOpen(node: ServerStartedTurnNode) {
        val kinds = journalEntries(node.journal).map { it.kind }
        assertTrue("turn_closed" !in kinds, "silence alone keeps a busy turn: $kinds")
    }

    private companion object {
        val NEXT_RUN = RunId("local-run-1209")

        /** A loop status of another conversation of the same agent. */
        fun otherRuntimeLoopStatus(): String {
            val other = Json.parseToJsonElement(idleLoopStatus()).jsonObject.toMutableMap()
            other["runtime"] = AppServerProtocol.json.encodeToJsonElement(
                AppServerRuntimeScope.serializer(),
                AppServerRuntimeScope(AGENT.value, "local-conv-other"),
            )
            return JsonObject(other).toString()
        }

        /** The result of the busy turn's tool, on its run. */
        fun toolReturn(): String = frame(
            delta(
                mapOf("message_type" to "tool_return_message", "run_id" to RUN.value, "type" to "message", "tool_call_id" to "toolu_lost"),
            ),
        )
    }
}
