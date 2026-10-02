package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.RunActivityState
import com.letta.mobile.data.chat.projection.projectRunActivity
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.1: App Server runs every tool round of one turn under a NEW run id
 * (bridge-parity/multi-round-agentic.jsonl: local-run-100 -> 101 -> 102, one `requires_approval`
 * stop per round, one `turn_finished` at the end). A coding agent running Bash four times in a row
 * must still read as ONE run, "Working · 4 tools" over one "Ran 4 commands" group, live and after a
 * reload, the way the durable (run-less) history already groups it.
 */
class ToolRoundRunStackingTest {
    @Test fun consecutiveToolRoundsOfOneTurnStackIntoOneLiveRun() = runBlocking {
        val harness = Harness.open(durable = emptyList())
        try {
            val live = harness.streamMidRun(liveRounds)
            val runs = live.filter { it.isRunItem }
            assertEquals(1, runs.size, "every tool round of the turn is one run: ${live.map { it.key }}")
            val messages = messagesOf(runs.single())
            assertEquals(5, messages.size)
            val activity = requireNotNull(projectRunActivity(messages, isActiveRunStreaming = true))
            assertEquals(RunActivityState.Working, activity.state)
            assertEquals(4, activity.toolCount, "the header counts the run's tools")
            // The running Bash call (no return yet) sits in the same run as the finished ones.
            assertTrue(messages.last().toolCalls.orEmpty().single().result == null)
            // The run keeps its first round's key as later rounds append, so its slot never moves.
            assertEquals("run-$RUN_1", runs.single().key)
        } finally {
            harness.close()
        }
    }

    @Test fun aReloadedTurnStacksItsToolRoundsTheSameWay() = runBlocking {
        val harness = Harness.open(durable = durableTurn)
        try {
            harness.settle()
            val runs = harness.rows().filter { it.isRunItem }
            assertEquals(1, runs.size, "hydrated history: ${harness.rows().map { it.key }}")
            val activity = requireNotNull(projectRunActivity(messagesOf(runs.single()), isActiveRunStreaming = false))
            assertEquals(4, activity.toolCount)
        } finally {
            harness.close()
        }
    }

    @Test fun aTurnThatEndedInTextIsNotJoinedByTheNextRun() = runBlocking {
        val harness = Harness.open(durable = emptyList())
        try {
            // A finished reply, then an agent-initiated run with no prompt in between: two runs.
            val live = harness.streamMidRun(
                listOf(
                    echo,
                    AssistantMessage(id = "reply-a", contentRaw = JsonPrimitive("Done."), date = at(2), runId = "run-a"),
                    call("call-b", "run-b", second = 3),
                ),
                expectedRows = 3,
            )
            assertEquals(2, live.count { it.isRunItem }, live.map { it.key }.toString())
        } finally {
            harness.close()
        }
    }

    private fun messagesOf(item: ChatRenderItem): List<UiMessage> = when (item) {
        is ChatRenderItem.RunBlock -> item.messages.map { it.first }
        is ChatRenderItem.Single -> listOf(item.message)
    }

    private class Harness private constructor(
        private val coordinator: CanonicalTimelineCoordinator,
        private val owner: CanonicalTimelineCoordinator.Owner,
        private val ui: CoroutineScope,
        private val presentation: CanonicalTimelinePresentation,
    ) {
        companion object {
            suspend fun open(durable: List<LettaMessage>): Harness {
                val coordinator = CanonicalTimelineCoordinator(InMemoryTimelineStore(), DurableTransport(durable))
                val owner = coordinator.acquire(scope)
                val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                return Harness(coordinator, owner, ui, CanonicalTimelinePresentation.open(coordinator, owner, ui))
            }
        }

        private val presenter = RecordingPresenter<CanonicalTimelinePresentation.Row>()

        /** Streams [frames] of a turn that is still running (no Done) and returns the live render. */
        suspend fun streamMidRun(frames: List<LettaMessage>, expectedRows: Int? = null): List<ChatRenderItem> {
            val fence = coordinator.beginLive(owner)
            frames.forEach { assertTrue(coordinator.ingest(owner, fence, TimelineStreamFrame.Message(it))) }
            val last = frames.last().id
            awaitCondition({ "live turn never projected: ${presentation.live.value.map { it.key }}" }) {
                val live = presentation.live.value
                live.any { it.containsMessageId(last) } && (expectedRows == null || live.size == expectedRows)
            }
            return presentation.live.value
        }

        suspend fun settle() {
            assertEquals(TimelineEnginePageOutcome.Applied, coordinator.reconcileRecent(owner))
            ui.launch { presentation.settled.collectLatest { presenter.collectFrom(it) } }
            presenter.awaitRows(2) { "settled turn never arrived" }
            presenter.awaitIdle()
        }

        fun rows(): List<ChatRenderItem> = presenter.snapshot().items.map { it.item }

        suspend fun close() {
            presentation.close()
            ui.cancel()
        }
    }

    private class DurableTransport(private val durable: List<LettaMessage>) :
        TimelineTransport by unexpectedTimelineTransport() {
        override suspend fun listConversationMessagePage(request: TimelineRemotePageRequest, progress: TimelinePageProgress?) =
            TimelineRemotePageResult.Page(
                request.requestId, request.selectionGeneration,
                durable.map { TimelineRemoteRecord(TimelineMessageId(it.id), it, 0) },
                null, false, 0,
            )
    }

    private companion object {
        const val RUN_1 = "local-run-100"
        val scope = TimelineScope("backend", "local-conv-606", "agent")

        fun at(second: Int) = "2026-09-25T11:05:%02d.000Z".format(second)

        val prompt = UserMessage(id = "ui-msg-prompt", contentRaw = JsonPrimitive("run the checks"), date = at(0), otid = "cm-1")
        val echo = prompt.copy(id = "cm-user-cm-1", date = at(1))

        fun call(id: String, runId: String?, second: Int) = ToolCallMessage(
            id = "msg-$id",
            toolCalls = listOf(ToolCall(id = id, name = "Bash", arguments = """{"command":"ls"}""")),
            date = at(second),
            runId = runId,
        )

        fun result(id: String, runId: String?, second: Int) = ToolReturnMessage(
            id = "ret-$id", toolCallId = id, toolReturnRaw = JsonPrimitive("ok"), status = "success",
            date = at(second), runId = runId,
        )

        /** What the device streams: one run id per tool round, the fourth Bash still running. */
        val liveRounds: List<LettaMessage> = listOf(
            echo,
            ReasoningMessage(id = "thought-1", reasoning = "Look around first", date = at(2), runId = RUN_1),
            call("call-1", RUN_1, 3), result("call-1", RUN_1, 4),
            call("call-2", "local-run-101", 5), result("call-2", "local-run-101", 6),
            call("call-3", "local-run-102", 7), result("call-3", "local-run-102", 8),
            call("call-4", "local-run-103", 9),
        )

        /** What `message.list` returns for the same turn after it finished: no row names a run. */
        val durableTurn: List<LettaMessage> = listOf(
            AssistantMessage(id = "reply", contentRaw = JsonPrimitive("All green."), date = at(12)),
            result("call-4", null, 11), call("call-4", null, 10),
            result("call-3", null, 8), call("call-3", null, 7),
            result("call-2", null, 6), call("call-2", null, 5),
            result("call-1", null, 4), call("call-1", null, 3),
            prompt,
        )
    }
}
