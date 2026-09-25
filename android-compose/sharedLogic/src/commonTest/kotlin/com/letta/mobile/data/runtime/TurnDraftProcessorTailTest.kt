package com.letta.mobile.data.runtime

import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-qygvv.26: the end-of-round tail (usage_statistics, stop_reason) reaches the client in
 * the order the App Server sent it, once per round, and the terminal flush carries only the final
 * round's tail.
 */
class TurnDraftProcessorTailTest {

    @Test
    fun singleRoundTailKeepsServerOrder() = runTest {
        val harness = TailHarness(this)
        harness.feed(frame("assistant_message", "run-1"), frame("usage_statistics", "run-1"), frame("stop_reason", "run-1"))
        harness.feedTerminal("run-1")
        assertEquals(
            listOf("assistant_message", "usage_statistics", "stop_reason", "Completed"),
            harness.emittedKinds(),
        )
    }

    @Test
    fun everyRoundTailReachesTheClientAtItsRoundBoundary() = runTest {
        val harness = TailHarness(this)
        harness.feed(
            frame("approval_request_message", "run-1"),
            frame("usage_statistics", "run-1", "11"),
            frame("stop_reason", "run-1", "requires_approval"),
            frame("tool_return_message", "run-1"),
            frame("assistant_message", "run-2"),
            frame("usage_statistics", "run-2", "22"),
            frame("stop_reason", "run-2", "end_turn"),
        )
        // Round 1's tail went out before round 2's first frame; round 2's waits for the terminal.
        assertEquals(
            listOf("approval_request_message", "usage_statistics", "stop_reason", "tool_return_message", "assistant_message"),
            harness.emittedKinds(),
        )
        harness.feedTerminal("run-2")
        assertEquals(
            listOf("usage_statistics:run-1", "stop_reason:run-1", "usage_statistics:run-2", "stop_reason:run-2"),
            harness.emittedTails(),
        )
        assertEquals("Completed", harness.emittedKinds().last())
    }

    @Test
    fun usageWithoutStopReasonStaysInTheTailUntilTheTerminal() = runTest {
        val harness = TailHarness(this)
        harness.feed(
            frame("usage_statistics", "run-1", "11"),
            frame("assistant_message", "run-1"),
            frame("usage_statistics", "run-1", "22"),
        )
        assertEquals(listOf("assistant_message"), harness.emittedKinds())
        harness.feedTerminal("run-1")
        assertEquals(
            listOf("assistant_message", "usage_statistics", "usage_statistics", "Completed"),
            harness.emittedKinds(),
        )
    }

    @Test
    fun terminalFlushClearsApprovalsBeforeTheFinalTail() = runTest {
        val harness = TailHarness(this)
        harness.feed(frame("usage_statistics", "run-1"), frame("stop_reason", "run-1"))
        harness.feedTerminal("run-1")
        assertEquals(listOf("clearApprovals", "usage_statistics", "stop_reason", "Completed"), harness.log)
    }

    private fun frame(messageType: String, runId: String, detail: String? = null): RuntimeEventDraft {
        val extra = when (messageType) {
            "stop_reason" -> detail?.let { ""","stop_reason":"$it"""" }.orEmpty()
            "usage_statistics" -> detail?.let { ""","total_tokens":$it""" }.orEmpty()
            else -> ""
        }
        val body = """{"type":"stream_delta","delta":{"message_type":"$messageType","run_id":"$runId"$extra}}"""
        val payload = RuntimeEventPayload.RemoteStreamFrame(frameId = "$messageType-$runId", messageType = messageType, body = body)
        return tailTestDraft(runId, payload)
    }
}

private fun tailTestDraft(runId: String?, payload: RuntimeEventPayload) = RuntimeEventDraft(
    backendId = BackendId("backend-1"),
    runtimeId = RuntimeId("runtime-1"),
    runId = runId?.let(::RunId),
    source = RuntimeEventSource.RemoteLetta,
    payload = payload,
)

private class TurnEndedForTest : RuntimeException()

/** Drives one [TurnDraftProcessor] and records what it emits, as the engine's callbacks would. */
private class TailHarness(private val scope: TestScope) {
    val log = mutableListOf<String>()
    private val emitted = mutableListOf<RuntimeEventDraft>()

    private val processor = TurnDraftProcessor(
        TurnDraftCallbacks(
            autoApprovedDraft = { null },
            track = { _, _ -> },
            clearApprovals = { log += "clearApprovals" },
            emit = { draft ->
                emitted += draft
                log += draft.kind()
            },
            settle = { _, _ -> },
            completedDraft = { runId -> lifecycle(RuntimeRunStatus.Completed, runId?.value) },
            recordTerminal = { _, _ -> },
            noteCompleted = { },
            complete = { throw TurnEndedForTest() },
            settleDelayMs = 1_500,
        ),
        scope.backgroundScope,
    )

    suspend fun feed(vararg drafts: RuntimeEventDraft) {
        drafts.forEach { processor.process(it, frameSeq = null) }
    }

    /** The server's authoritative end of turn (turn_finished) for [runId]. */
    suspend fun feedTerminal(runId: String) {
        try {
            processor.process(lifecycle(RuntimeRunStatus.Completed, runId), frameSeq = null, authoritative = true)
        } catch (_: TurnEndedForTest) {
            // The processor completes the turn by throwing out of complete(), as the engine's marker does.
        }
        scope.advanceUntilIdle()
    }

    fun emittedKinds(): List<String> = emitted.map { it.kind() }

    fun emittedTails(): List<String> =
        emitted.filter { it.kind() in TAIL_KINDS }.map { "${it.kind()}:${it.runId?.value}" }

    private fun lifecycle(status: RuntimeRunStatus, runId: String?) =
        tailTestDraft(runId, RuntimeEventPayload.RunLifecycleChanged(status))

    private fun RuntimeEventDraft.kind(): String = when (val event = payload) {
        is RuntimeEventPayload.RemoteStreamFrame -> event.messageType.orEmpty()
        is RuntimeEventPayload.RunLifecycleChanged -> event.status.name
        else -> "other"
    }

    private companion object {
        val TAIL_KINDS = setOf("usage_statistics", "stop_reason")
    }
}
