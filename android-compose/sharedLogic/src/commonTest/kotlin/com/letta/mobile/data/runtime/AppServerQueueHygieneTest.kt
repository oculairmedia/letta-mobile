package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerQueueRemoval
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-qygvv.6: after a confirmed abort the client removes its own parked queue items,
 * reports them Cancelled, and resumes the queue so other clients' items are not left parked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerQueueHygieneTest {

    @Test
    fun abortRemovesOwnQueuedInputThenResumesQueue() = runTest {
        val client = QueueClient(abortResponse = abortResponse(aborted = true))
        val engine = engineFor(client)
        val cancelled = mutableListOf<CancelledQueuedInput>()
        backgroundScope.launch { engine.cancelledQueuedInputs.collect { cancelled += it } }
        val drafts = mutableListOf<RuntimeEventDraft>()
        val turn = launch { engine.runTurn(command).collect { drafts += it } }
        runCurrent()
        assertTrue(INPUT_QUEUED_REASON in drafts.lifecycleReasons(), "the input is queued behind another turn")

        client.emit(updateQueue(item("q-1", "local-1"), item("q-2", "other-client-msg")))
        runCurrent()
        engine.abort("agent-1", "conv-1", runId = null)
        advanceUntilIdle()
        turn.join()

        assertEquals(listOf("abort_message", "remove_queue_item:q-1", "resume_queue"), client.calls)
        assertEquals(listOf("local-1"), cancelled.map { it.clientMessageId })
        val lifecycle = cancelled.single().draft.payload as RuntimeEventPayload.RunLifecycleChanged
        assertEquals(RuntimeRunStatus.Cancelled, lifecycle.status)
        assertEquals(QUEUED_INPUT_REMOVED_AFTER_ABORT_REASON, lifecycle.reason)
        // The server's `cancelled` removal also settles the lease that was waiting on the item.
        assertEquals(RuntimeRunStatus.Cancelled, drafts.lastLifecycle()?.status)
        assertFalse(engine.isBusy("agent-1", "conv-1"))
    }

    @Test
    fun pausedFlagIsSurfacedPerRuntime() = runTest {
        val hygiene = AppServerQueueHygiene(QueueClient(abortResponse(aborted = true)), { "req" })

        hygiene.observe(updateQueue(item("q-1", "local-1", paused = true)))
        val snapshot = hygiene.snapshots.value.getValue(key)
        assertTrue(snapshot.paused)
        assertEquals(listOf("q-1"), snapshot.items.map { it.id })

        hygiene.observe(updateQueue(item("q-1", "local-1")))
        assertFalse(hygiene.snapshots.value.getValue(key).paused)

        hygiene.observe(updateQueue())
        assertNull(hygiene.snapshots.value[key], "an empty queue drops the runtime's entry")
    }

    @Test
    fun abortWithEmptyQueueSendsNoQueueCommands() = runTest {
        val client = QueueClient(abortResponse(aborted = true))
        val hygiene = AppServerQueueHygiene(client, { "req" })
        hygiene.noteSentInput(key, "local-1", command)

        hygiene.noteAbortRequested(runtime)
        hygiene.onAbortResponse(runtime, abortResponse(aborted = true))

        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun abortThatStoppedNothingLeavesQueueAlone() = runTest {
        val client = QueueClient(abortResponse(aborted = false))
        val hygiene = AppServerQueueHygiene(client, { "req" }, scope = backgroundScope)
        hygiene.noteSentInput(key, "local-1", command)
        hygiene.observe(updateQueue(item("q-1", "local-1")))

        hygiene.noteAbortRequested(runtime)
        hygiene.onAbortResponse(runtime, abortResponse(aborted = false))
        hygiene.observe(turnFinished("cancelled"))
        runCurrent() // the settle runs in backgroundScope, which advanceUntilIdle skips

        assertTrue(client.calls.isEmpty(), "aborted=false means nothing was parked, and clears the pending abort")
    }

    @Test
    fun cancelledTurnFinishedSettlesAbortWhoseResponseNeverArrived() = runTest {
        val client = QueueClient(abortResponse(aborted = true))
        val hygiene = AppServerQueueHygiene(client, { "req" }, scope = backgroundScope)
        hygiene.noteSentInput(key, "local-1", command)
        hygiene.observe(updateQueue(item("q-1", "local-1", paused = true), item("q-2", "cm-q-2", paused = true)))

        hygiene.noteAbortRequested(runtime)
        hygiene.observe(turnFinished("end_turn"))
        runCurrent() // the settle runs in backgroundScope, which advanceUntilIdle skips
        assertTrue(client.calls.isEmpty(), "only a cancelled turn_finished settles an abort")

        hygiene.observe(turnFinished("cancelled"))
        runCurrent() // the settle runs in backgroundScope, which advanceUntilIdle skips
        assertEquals(listOf("remove_queue_item:q-1", "resume_queue"), client.calls)

        // A late abort response for the same abort must not run the sequence twice.
        hygiene.onAbortResponse(runtime, abortResponse(aborted = true))
        runCurrent() // the settle runs in backgroundScope, which advanceUntilIdle skips
        assertEquals(2, client.calls.size)
    }

    @Test
    fun failedRemovalStillResumesAndReportsNothingCancelled() = runTest {
        val client = QueueClient(abortResponse(aborted = true), removeSucceeds = false)
        val hygiene = AppServerQueueHygiene(client, { "req" })
        val cancelled = mutableListOf<CancelledQueuedInput>()
        backgroundScope.launch { hygiene.cancelledInputs.collect { cancelled += it } }
        hygiene.noteSentInput(key, "local-1", command)
        hygiene.observe(updateQueue(item("q-1", "local-1", paused = true)))

        hygiene.noteAbortRequested(runtime)
        hygiene.onAbortResponse(runtime, abortResponse(aborted = true))
        runCurrent()

        assertEquals(listOf("remove_queue_item:q-1", "resume_queue"), client.calls)
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun unsupportedQueueCommandsNeverFailTheAbort() = runTest {
        val client = QueueClient(abortResponse(aborted = true), supportsQueueCommands = false)
        val hygiene = AppServerQueueHygiene(client, { "req" })
        hygiene.noteSentInput(key, "local-1", command)
        hygiene.observe(updateQueue(item("q-1", "local-1")))

        hygiene.noteAbortRequested(runtime)
        hygiene.onAbortResponse(runtime, abortResponse(aborted = true))

        assertEquals(listOf("remove_queue_item:q-1", "resume_queue"), client.calls)
    }

    private fun TestScope.engineFor(client: AppServerClient) = AppServerTurnEngine(
        client = client,
        requestIdFactory = { "req-1" },
        turnIdleTimeoutMs = 1_000,
        terminalSettleQuietMs = 10,
        nowMs = { testScheduler.currentTime },
    )

    private fun List<RuntimeEventDraft>.lifecycles() =
        mapNotNull { it.payload as? RuntimeEventPayload.RunLifecycleChanged }

    private fun List<RuntimeEventDraft>.lastLifecycle() = lifecycles().lastOrNull()

    private fun List<RuntimeEventDraft>.lifecycleReasons() = lifecycles().mapNotNull { it.reason }

    private companion object {
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
        val key = TurnRuntimeKey("agent-1", "conv-1")
        val command = TurnCommand(
            backendId = BackendId("iroh-node-server"),
            runtimeId = RuntimeId("iroh-node:agent-1:conv-1"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hey"),
        )

        private var seq = 0L

        fun abortResponse(aborted: Boolean) =
            AppServerInboundFrame.AbortMessageResponse(requestId = "req-1", runtime = runtime, aborted = aborted, success = true)

        fun item(id: String, clientMessageId: String, paused: Boolean = false): JsonObject = buildJsonObject {
            put("id", id)
            put("client_message_id", clientMessageId)
            put("kind", "message")
            put("source", "user")
            put("content", "queued text")
            put("enqueued_at", "2026-09-24T00:00:00Z")
            if (paused) put("paused", true)
        }

        fun updateQueue(vararg queue: JsonObject, removed: List<AppServerQueueRemoval> = emptyList()): AppServerInboundFrame.UpdateQueue {
            seq += 1
            return AppServerInboundFrame.UpdateQueue(
                runtime = runtime,
                eventSeq = seq,
                emittedAt = "2026-09-24T00:00:00Z",
                idempotencyKey = "queue-$seq",
                queue = queue.toList(),
                removed = removed,
            )
        }

        fun turnFinished(stopReason: String): AppServerInboundFrame.TurnFinished {
            seq += 1
            return AppServerInboundFrame.TurnFinished(
                runtime = runtime,
                eventSeq = seq,
                emittedAt = "2026-09-24T00:00:00Z",
                idempotencyKey = "turn-finished-$seq",
                turnId = "turn-$seq",
                stopReason = stopReason,
            )
        }
    }

    /** Stands in for the App Server: input is queued, and a removal emits the `cancelled` transition. */
    private class QueueClient(
        private val abortResponse: AppServerInboundFrame.AbortMessageResponse,
        private val removeSucceeds: Boolean = true,
        private val supportsQueueCommands: Boolean = true,
    ) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 64)
        val calls = mutableListOf<String>()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = requireNotNull(command.agentId),
                    conversationId = requireNotNull(command.conversationId),
                ),
            )

        override suspend fun input(command: AppServerCommand.Input) = Unit

        override suspend fun inputAwaitingAcceptance(command: AppServerCommand.Input) =
            AppServerInboundFrame.InputAccepted(
                requestId = requireNotNull(command.requestId),
                runtime = command.runtime,
                accepted = true,
                disposition = "queued",
            )

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse {
            calls += "abort_message"
            return abortResponse
        }

        override suspend fun removeQueueItem(
            command: AppServerCommand.RemoveQueueItem,
        ): AppServerInboundFrame.RemoveQueueItemResponse {
            calls += "remove_queue_item:${command.itemId}"
            if (!supportsQueueCommands) throw UnsupportedOperationException("no queue commands")
            if (removeSucceeds) {
                emit(updateQueue(removed = listOf(AppServerQueueRemoval("local-1", "cancelled"))))
            }
            return AppServerInboundFrame.RemoveQueueItemResponse(command.requestId, success = removeSucceeds, itemId = command.itemId)
        }

        override suspend fun resumeQueue(command: AppServerCommand.ResumeQueue): AppServerInboundFrame.ResumeQueueResponse {
            calls += "resume_queue"
            if (!supportsQueueCommands) throw UnsupportedOperationException("no queue commands")
            return AppServerInboundFrame.ResumeQueueResponse(
                requestId = requireNotNull(command.requestId),
                runtime = command.runtime,
                resumed = 1,
                success = true,
            )
        }

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("sync unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        fun emit(frame: AppServerInboundFrame) {
            val raw: JsonObject = buildJsonObject {
                put("type", frame.type ?: "unknown")
                if (frame is AppServerInboundFrame.UpdateQueue) put("idempotency_key", frame.idempotencyKey)
            }
            (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
                AppServerReceivedFrame(channel = AppServerChannel.Stream, frame = frame, raw = raw),
            )
        }
    }
}
