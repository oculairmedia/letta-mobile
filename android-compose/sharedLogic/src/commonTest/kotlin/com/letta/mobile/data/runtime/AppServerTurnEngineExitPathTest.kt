package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.util.Telemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-by4vm.7: every exit of `runTurn` releases its lease with its own reason, and a turn
 * that cannot start ends with a Failed terminal for the caller instead of an escaped exception, a
 * silent cancel, or a release that reads as a normal completion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineExitPathTest {

    @Test
    fun refusedRuntimeStartFailsTheTurnAndReleasesAsStartFailed() = runTest {
        Telemetry.clear()
        val client = ExitPathClient(runtimeStart = { command -> refused(command, "agent not found") })
        val engine = AppServerTurnEngine(client = client)

        val drafts = engine.runTurn(COMMAND).toList()

        val failed = drafts.single().lifecycle()
        assertEquals(RuntimeRunStatus.Failed, failed.status)
        assertTrue(failed.reason.orEmpty().contains("agent not found"), "reason was ${failed.reason}")
        assertTrue(client.inputs.isEmpty(), "no input may be sent when runtime_start failed")
        assertEquals(TurnReleaseReason.START_FAILED, releaseReason())
        assertFalse(engine.isBusy(AGENT, CONVERSATION))
        assertTrue(client.aborts.isEmpty(), "nothing ran on the server, so nothing is aborted")
    }

    @Test
    fun throwingPermissionModeProviderFailsTheTurnBeforeRuntimeStart() = runTest {
        Telemetry.clear()
        val client = ExitPathClient()
        val engine = AppServerTurnEngine(
            client = client,
            permissionModeProvider = { error("no permission mode configured") },
        )

        val drafts = engine.runTurn(COMMAND).toList()

        val failed = drafts.single().lifecycle()
        assertEquals(RuntimeRunStatus.Failed, failed.status)
        assertTrue(failed.reason.orEmpty().contains("no permission mode configured"), "reason was ${failed.reason}")
        assertTrue(client.runtimeStarts.isEmpty(), "runtime_start must not be sent without a mode")
        assertEquals(TurnReleaseReason.START_FAILED, releaseReason())
        assertFalse(engine.isBusy(AGENT, CONVERSATION))
    }

    @Test
    fun clientCancellationExceptionOnALiveTurnIsAStartFailureNotASilentCancel() = runTest {
        Telemetry.clear()
        val client = ExitPathClient(runtimeStart = { throw CancellationException("runtime_start timed out") })
        val engine = AppServerTurnEngine(client = client)

        val drafts = engine.runTurn(COMMAND).toList()

        val failed = drafts.single().lifecycle()
        assertEquals(RuntimeRunStatus.Failed, failed.status)
        assertTrue(failed.reason.orEmpty().contains("runtime_start timed out"), "reason was ${failed.reason}")
        assertEquals(TurnReleaseReason.START_FAILED, releaseReason())
        assertFalse(engine.isBusy(AGENT, CONVERSATION))
    }

    @Test
    fun callerCancellingDuringRuntimeStartReleasesAsCancellation() = runTest {
        Telemetry.clear()
        val neverAnswered = CompletableDeferred<AppServerInboundFrame.RuntimeStartResponse>()
        val client = ExitPathClient(runtimeStart = { neverAnswered.await() })
        val engine = AppServerTurnEngine(client = client)
        val drafts = mutableListOf<RuntimeEventDraft>()

        val job = launch { engine.runTurn(COMMAND).collect { drafts += it } }
        runCurrent()
        assertTrue(engine.isBusy(AGENT, CONVERSATION), "the lease is held while runtime_start is in flight")
        job.cancel()
        job.join()

        assertTrue(drafts.isEmpty(), "a caller that cancelled is not sent a failure, got $drafts")
        assertEquals(TurnReleaseReason.CANCELLATION, releaseReason())
        assertFalse(engine.isBusy(AGENT, CONVERSATION))
    }

    @Test
    fun throwingInputSendFailsTheTurnAndAbortsNothingWithoutItsRun() = runTest {
        Telemetry.clear()
        val client = ExitPathClient(input = { error("socket write failed") })
        val engine = AppServerTurnEngine(client = client)

        val drafts = engine.runTurn(COMMAND).toList()

        assertEquals(
            listOf(RuntimeRunStatus.Started, RuntimeRunStatus.Failed),
            drafts.map { it.lifecycle().status },
        )
        assertTrue(drafts.last().lifecycle().reason.orEmpty().contains("socket write failed"))
        assertEquals(TurnReleaseReason.INPUT_SEND_FAILED, releaseReason())
        assertTrue(client.aborts.isEmpty(), "no run of this lease was seen, so no run-id-less abort")
        assertFalse(engine.isBusy(AGENT, CONVERSATION))
    }

    @Test
    fun rejectedInputReleasesAsInputRejected() = runTest {
        Telemetry.clear()
        val client = ExitPathClient(ack = { false })
        val engine = AppServerTurnEngine(client = client)

        val drafts = engine.runTurn(COMMAND).toList()

        assertEquals(RuntimeRunStatus.Failed, drafts.last().lifecycle().status)
        assertEquals(TurnReleaseReason.INPUT_REJECTED, releaseReason())
    }

    @Test
    fun inboundStreamEndingWithoutATerminalReleasesAsStreamEnded() = runTest {
        Telemetry.clear()
        val client = ExitPathClient(inbound = emptyFlow())
        val engine = AppServerTurnEngine(client = client)

        val drafts = engine.runTurn(COMMAND).toList()

        assertEquals(listOf(RuntimeRunStatus.Started), drafts.map { it.lifecycle().status })
        assertEquals(TurnReleaseReason.STREAM_ENDED, releaseReason())
        assertFalse(engine.isBusy(AGENT, CONVERSATION))
    }

    @Test
    fun releaseRecordKeepsTheFirstReasonAndReportsAMissingOne() {
        Telemetry.clear()
        val record = TurnReleaseRecord()
        val key = TurnRuntimeKey(AGENT, CONVERSATION)

        assertEquals("unrecorded", record.reasonForRelease(key))
        assertTrue(Telemetry.snapshot().any { it.name == "activeTurn.releaseReasonMissing" })

        val first = IllegalStateException("first")
        record.record(TurnReleaseReason.STREAM_ERROR, first)
        record.record(TurnReleaseReason.CANCELLATION, CancellationException("teardown"))
        assertEquals(TurnReleaseReason.STREAM_ERROR, record.reasonForRelease(key))
        assertEquals(first, record.cause)
    }

    private fun releaseReason(): Any? = Telemetry.snapshot().last {
        it.tag == "AppServerTurnEngine" && it.name == "activeTurn.released"
    }.attrs["releaseReason"]

    private fun RuntimeEventDraft.lifecycle(): RuntimeEventPayload.RunLifecycleChanged =
        payload as? RuntimeEventPayload.RunLifecycleChanged ?: error("not a lifecycle draft: $payload")

    private class ExitPathClient(
        private val runtimeStart: suspend (AppServerCommand.RuntimeStart) -> AppServerInboundFrame.RuntimeStartResponse? = { null },
        private val input: suspend (AppServerCommand.Input) -> Unit = {},
        private val ack: (() -> Boolean)? = null,
        inbound: Flow<AppServerReceivedFrame>? = null,
    ) : TurnEngineTestStreamClient() {
        override val events: Flow<AppServerReceivedFrame> = inbound ?: super.events
        val runtimeStarts = mutableListOf<AppServerCommand.RuntimeStart>()
        val inputs = mutableListOf<AppServerCommand.Input>()
        val aborts = mutableListOf<AppServerCommand.AbortMessage>()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse {
            runtimeStarts += command
            return runtimeStart.invoke(command) ?: super.runtimeStart(command)
        }

        override suspend fun input(command: AppServerCommand.Input) {
            inputs += command
            input.invoke(command)
        }

        override suspend fun inputAwaitingAcceptance(command: AppServerCommand.Input): AppServerInboundFrame.InputAccepted {
            val accepted = ack ?: throw UnsupportedOperationException("no ack")
            inputs += command
            return AppServerInboundFrame.InputAccepted(
                requestId = command.requestId.orEmpty(),
                runtime = command.runtime,
                accepted = accepted(),
                disposition = "started",
                error = "Runtime is no longer active",
            )
        }

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
            aborts += command
            return AppServerInboundFrame.AbortMessageResponse(
                requestId = command.requestId.orEmpty(),
                runtime = command.runtime,
                aborted = true,
                success = true,
            )
        }
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-1"
        val COMMAND = TurnCommand(
            backendId = BackendId("app-server"),
            runtimeId = RuntimeId("app-server:test"),
            agentId = AgentId(AGENT),
            conversationId = ConversationId(CONVERSATION),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
        )

        fun refused(command: AppServerCommand.RuntimeStart, error: String) =
            AppServerInboundFrame.RuntimeStartResponse(requestId = command.requestId, success = false, error = error)
    }
}
