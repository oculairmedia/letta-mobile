package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.runLifecycleStatus
import com.letta.mobile.data.runtime.terminalReasonKind
import com.letta.mobile.data.runtime.turnEngineTerminalStatuses
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.16: the bridge parity gate's session-loss case. A recorded App Server turn is
 * cut off mid-reply (the Iroh wrapper restarts, the phone redials): the phone's engine must still
 * end the turn, exactly once, with the connection-lost failure, and free its lease.
 */
class IrohBridgeSessionLossParityTest {

    @Test
    fun sessionLostMidTurnEndsTheTurnOnceWithConnectionLost() = runTest {
        val run = runUntilSessionLoss()

        val terminals = run.drafts.filter { it.runLifecycleStatus() in turnEngineTerminalStatuses }
        assertEquals(1, terminals.size, "exactly one terminal in ${run.drafts.map { it.payload::class.simpleName }}")
        val terminal = terminals.single().payload as RuntimeEventPayload.RunLifecycleChanged
        assertEquals(RuntimeRunStatus.Failed, terminal.status)
        assertEquals("connection_lost", terminalReasonKind(terminal.reason))
        assertTrue(run.sawAssistantReply, "the reply streamed before the loss still reaches the phone")
        assertFalse(run.engineBusyAfter, "the lease must be released, not left behind as a phantom")
    }

    private class SessionLossRun(
        val drafts: List<RuntimeEventDraft>,
        val sawAssistantReply: Boolean,
        val engineBusyAfter: Boolean,
    )

    private suspend fun TestScope.runUntilSessionLoss(): SessionLossRun {
        val recording = AppServerRecording.load(FIXTURE)
        val client = RecordedAppServerClient(recording.ackJson, recording.frames, backgroundScope)
        val router = AppServerRuntimeEventRouter().also { it.attach(backgroundScope, client.events) }
        val engine = AppServerTurnEngine(
            client = client,
            eventRouter = router,
            turnIdleTimeoutMs = 600_000,
            nowMs = { testScheduler.currentTime },
        )
        val command = turnCommandFor(recording.runtime, CLIENT_MESSAGE_ID)
        val collected = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(command).toList(collected) }
        runCurrent()
        // The wrapper restarts: the session closes and the router detaches mid-turn.
        router.detach()
        advanceUntilIdle()
        turn.join()
        return SessionLossRun(
            drafts = collected,
            sawAssistantReply = collected.any {
                (it.payload as? RuntimeEventPayload.RemoteStreamFrame)?.messageType == "assistant_message"
            },
            engineBusyAfter = engine.isBusy(recording.runtime.agentId, recording.runtime.conversationId),
        )
    }

    private companion object {
        const val FIXTURE = "appserver/bridge-parity/session-lost-mid-turn.jsonl"
        const val CLIENT_MESSAGE_ID = "cm-parity-session-lost-1"
    }
}
