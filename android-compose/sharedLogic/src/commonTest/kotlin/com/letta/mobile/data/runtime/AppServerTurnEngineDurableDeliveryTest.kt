package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInfoData
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerQueueRemoval
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRequestFailedException
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerTerminalConsumerId
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-zxk2n.2: durable turn delivery over the shared router stack. A 0.34.8+ host gets the
 * terminal consumer id and the chat preferences on every user input; each durable `turn_finished`
 * is applied once and acked once per delivery; an input whose ack the link lost is re-sent
 * unchanged on the restored session; a host without the capability sees none of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineDurableDeliveryTest {

    @Test
    fun aTerminalIsAckedOnceThoughTheTurnAndTheBetweenTurnsObserverBothSeeIt() = runTest {
        val turn = startTurn(DurableHost())
        turn.client.emit(assistantDelta())
        turn.client.emit(turnFinished())
        advanceUntilIdle()
        turn.job.join()

        assertEquals(RuntimeRunStatus.Completed, turn.singleTerminal())
        assertEquals(listOf(CONSUMER.value), turn.client.acks.map { it.consumerId })
        assertEquals(FINISHED_KEY, turn.client.acks.single().idempotencyKey)

        // The same delivery arriving again on the same connection is not acked twice; a new one is.
        // runCurrent: with the turn over only background work is left, which advanceUntilIdle skips.
        turn.client.emit(turnFinished())
        runCurrent()
        assertEquals(1, turn.client.acks.size)
        turn.client.emit(turnFinished().copy(idempotencyKey = "turn_finished:turn-2", turnId = "turn-2"))
        runCurrent()
        assertEquals(2, turn.client.acks.size, "a terminal no turn is collecting is acked between turns")
    }

    @Test
    fun aDurableHostGetsTheConsumerIdAndTheChatPreferences() = runTest {
        val turn = startTurn(DurableHost())
        turn.client.emit(turnFinished())
        advanceUntilIdle()

        val input = turn.client.inputs.single()
        assertEquals(CONSUMER.value, input.terminalConsumerId)
        assertEquals(DurableTurnDelivery.CHAT_CLIENT_PREFERENCES, (input.payload as AppServerInputPayload.CreateMessage).clientPreferences)
    }

    @Test
    fun aReplayAfterAReconnectSettlesTheWaitingTurnOnceAndIsAckedAgainOnTheNextConnection() = runTest {
        val turn = startTurn(DurableHost())
        turn.client.emit(assistantDelta())
        runCurrent()

        turn.router.interruptSession("wifi blip")
        runCurrent()
        turn.router.restoreSession()
        // The host replays the kept terminal to the reattached runtime.
        turn.client.emit(turnFinished())
        advanceUntilIdle()
        turn.job.join()
        assertEquals(RuntimeRunStatus.Completed, turn.singleTerminal())
        assertEquals(1, turn.client.acks.size)

        // A later connection that missed the ack sees the replay again: acked, never re-applied.
        turn.client.emit(turnFinished(), generation = 1)
        runCurrent()
        assertEquals(2, turn.client.acks.size)
        assertEquals(1, turn.drafts.count { it.isTerminal() })
    }

    @Test
    fun anInputWhoseAckTheLinkLostIsSentAgainUnchangedOnTheRestoredSession() = runTest {
        val host = DurableHost().apply { failNextInputs = 1 }
        val turn = startTurn(host)
        runCurrent()
        assertEquals(1, host.inputs.size, "the first copy went out")

        turn.router.interruptSession("socket closed")
        turn.router.restoreSession()
        runCurrent()
        assertEquals(2, host.inputs.size, "the same input was sent once more")
        val (first, second) = host.inputs
        assertEquals(first.copy(requestId = null), second.copy(requestId = null), "identical but for the request id")
        assertEquals(LOCAL_ID, first.clientMessageId())

        host.emit(turnFinished())
        advanceUntilIdle()
        turn.job.join()
        assertEquals(RuntimeRunStatus.Completed, turn.singleTerminal())
    }

    @Test
    fun aQueuedInputWaitsForItsDequeueAndItsTerminalIsAcked() = runTest {
        val turn = startTurn(DurableHost(disposition = "queued"))
        runCurrent()
        assertTrue(turn.drafts.any { it.reason() == INPUT_QUEUED_REASON }, "the queued state is shown")

        turn.client.emit(dequeued())
        turn.client.emit(assistantDelta())
        turn.client.emit(turnFinished())
        advanceUntilIdle()
        turn.job.join()

        assertEquals(RuntimeRunStatus.Completed, turn.singleTerminal())
        assertEquals(1, turn.client.acks.size)
    }

    @Test
    fun aHostWithoutTheCapabilityGetsNoNewFieldsAndNoAcks() = runTest {
        listOf("0.33.6", null).forEach { version ->
            val turn = startTurn(DurableHost(version = version).apply { failNextInputs = 1 })
            runCurrent()
            turn.router.interruptSession("socket closed")
            turn.router.restoreSession()
            turn.client.emit(turnFinished(consumers = emptyList()))
            advanceUntilIdle()
            turn.job.join()

            val input = turn.client.inputs.single()
            assertNull(input.terminalConsumerId, "version $version")
            assertNull((input.payload as AppServerInputPayload.CreateMessage).clientPreferences, "version $version")
            assertTrue(turn.client.acks.isEmpty(), "version $version")
            assertEquals(RuntimeRunStatus.Failed, turn.singleTerminal(), "no resend: the lost input fails as before")
        }
    }

    @Test
    fun eachFieldWaitsForTheVersionThatHasIt() {
        val delivery = DurableTurnDelivery.chat(CONSUMER)
        fun fields(version: String) = delivery.fieldsFor(AppServerInfoData(lettaCodeVersion = version), COMMAND)

        assertEquals(DurableInputFields.NONE, fields("0.33.6"))
        assertEquals(DurableInputFields(null, DurableTurnDelivery.CHAT_CLIENT_PREFERENCES), fields("0.34.7"))
        assertEquals(DurableInputFields(CONSUMER, DurableTurnDelivery.CHAT_CLIENT_PREFERENCES), fields("0.34.8"))
        assertEquals(DurableInputFields.NONE, fields("not-a-version"))
        assertEquals(DurableInputFields.NONE, DurableTurnDelivery.OFF.fieldsFor(AppServerInfoData(lettaCodeVersion = "0.34.14"), COMMAND))
    }

    private class RunningTurn(
        val client: DurableHost,
        val router: AppServerRuntimeEventRouter,
        val drafts: MutableList<RuntimeEventDraft>,
        val job: Job,
    ) {
        fun singleTerminal(): RuntimeRunStatus {
            val terminals = drafts.filter { it.isTerminal() }
            assertEquals(1, terminals.size, "exactly one terminal in ${drafts.map { it.payload }}")
            return requireNotNull(terminals.single().runLifecycleStatus())
        }
    }

    private fun TestScope.startTurn(host: DurableHost): RunningTurn {
        val router = AppServerRuntimeEventRouter()
        val spec = AppServerTurnStackSpec(durableDelivery = DurableTurnDelivery.chat(CONSUMER))
        val engine = router.attachTurnStack(host, backgroundScope, spec)
        val drafts = mutableListOf<RuntimeEventDraft>()
        val job = launch { engine.runTurn(COMMAND).toList(drafts) }
        runCurrent()
        return RunningTurn(host, router, drafts, job)
    }

    /** A fake host at [version] that records inputs and acks; [failNextInputs] lose their `input_accepted` to a dropped link. */
    private class DurableHost(version: String? = "0.34.14", private val disposition: String = "started") : AppServerClient {
        private val inbound = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        override val events: Flow<AppServerReceivedFrame> = inbound
        override val serverInfo: StateFlow<AppServerInfoData?> =
            MutableStateFlow(version?.let { AppServerInfoData(lettaCodeVersion = it, protocolVersion = 1, backend = "local") })
        val inputs = mutableListOf<AppServerCommand.Input>()
        val acks = mutableListOf<AppServerCommand.TurnFinishedAck>()
        var failNextInputs = 0

        fun emit(frame: AppServerInboundFrame, generation: Long? = null) {
            val raw = buildJsonObject { put("type", frame.type ?: "") }
            inbound.tryEmit(AppServerReceivedFrame(AppServerChannel.Stream, frame, raw, generation))
        }

        override suspend fun inputAwaitingAcceptance(command: AppServerCommand.Input): AppServerInboundFrame.InputAccepted {
            inputs += command
            if (failNextInputs > 0) {
                failNextInputs -= 1
                throw AppServerRequestFailedException(IllegalStateException("socket closed"))
            }
            return AppServerInboundFrame.InputAccepted(
                command.requestId.orEmpty(),
                command.runtime,
                accepted = true,
                disposition = disposition,
            )
        }

        override suspend fun acknowledgeTurnFinished(command: AppServerCommand.TurnFinishedAck) {
            acks += command
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) =
            AppServerInboundFrame.RuntimeStartResponse(requestId = command.requestId, success = true, runtime = RUNTIME)

        override suspend fun input(command: AppServerCommand.Input) = error("every user input awaits its ack")
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = error("sync unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("a dropped link never aborts the server turn")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-1"
        const val LOCAL_ID = "local-1"
        const val FINISHED_KEY = "turn_finished:turn-1"
        val CONSUMER = AppServerTerminalConsumerId.forClient("letta-desktop", "install-1")
        val RUNTIME = AppServerRuntimeScope(AGENT, CONVERSATION)
        val COMMAND = TurnCommand(
            backendId = BackendId("app-server"),
            runtimeId = RuntimeId("app-server:test"),
            agentId = AgentId(AGENT),
            conversationId = ConversationId(CONVERSATION),
            input = TurnInput.UserMessage(localMessageId = LOCAL_ID, text = "hi"),
        )

        fun assistantDelta() = AppServerInboundFrame.StreamDelta(
            runtime = RUNTIME,
            eventSeq = 1,
            emittedAt = EMITTED_AT,
            idempotencyKey = "evt-assistant-1",
            delta = buildJsonObject {
                put("id", "letta-msg-1")
                put("message_type", "assistant_message")
                put("content", "hello")
                put("run_id", RUN_ID)
            },
        )

        fun turnFinished(consumers: List<String> = listOf(CONSUMER.value)) = AppServerInboundFrame.TurnFinished(
            runtime = RUNTIME,
            eventSeq = 2,
            emittedAt = EMITTED_AT,
            idempotencyKey = FINISHED_KEY,
            turnId = "turn-1",
            stopReason = "end_turn",
            runId = RUN_ID,
            terminalConsumerIds = consumers,
        )

        fun dequeued() = AppServerInboundFrame.UpdateQueue(
            runtime = RUNTIME,
            eventSeq = 1,
            emittedAt = EMITTED_AT,
            idempotencyKey = "queue-1",
            queue = emptyList(),
            removed = listOf(AppServerQueueRemoval(LOCAL_ID, "dequeued")),
        )

        const val RUN_ID = "run-1"
        const val EMITTED_AT = "2026-10-16T00:00:00Z"

        fun RuntimeEventDraft.isTerminal(): Boolean = runLifecycleStatus() in turnEngineTerminalStatuses

        fun RuntimeEventDraft.reason(): String? =
            (payload as? com.letta.mobile.runtime.RuntimeEventPayload.RunLifecycleChanged)?.reason

        fun AppServerCommand.Input.clientMessageId(): String? =
            (payload as? AppServerInputPayload.CreateMessage)?.messages?.single()?.clientMessageId
    }
}
