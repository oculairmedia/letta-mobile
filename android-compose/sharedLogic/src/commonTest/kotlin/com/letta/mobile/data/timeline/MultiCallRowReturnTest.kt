package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UiToolCall
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A row of one message carrying several tool calls holds one return per call in its per-call maps, and one
 * single return (`toolReturnContent` / `toolReturnIsError`) that the chat projection shows on call 0 when call 0
 * has no entry of its own. Every writer must keep that single return its first call's, so a later call's result
 * or error never shows on call 0, live, through the legacy stream reducer, after a reload, or in a notification.
 */
class MultiCallRowReturnTest {
    @Test
    fun theStreamReducerKeepsALaterCallsErrorOffTheFirstCall() {
        val row = streamed(row("a", "b"), returned("b", "boom", error = true)).single()

        assertEquals(mapOf("b" to "boom"), row.toolReturnContentByCallId.toMap())
        assertEquals(mapOf("b" to true), row.toolReturnIsErrorByCallId.toMap())
        assertNull(row.toolReturnContent, "call a has not returned")
        assertFalse(row.toolReturnIsError, "call b's error is not call a's")
        val calls = shown(row)
        assertNull(calls.getValue("a").result, "call a shows nothing of call b")
        assertNull(calls.getValue("a").status)
        assertEquals("error", calls.getValue("b").status)
    }

    @Test
    fun theStreamReducerGivesTheFirstCallItsOwnReturnWhicheverOrderTheyArrive() {
        val row = streamed(row("a", "b"), returned("b", "boom", error = true), returned("a", "fine")).single()

        assertEquals("fine", row.toolReturnContent)
        assertFalse(row.toolReturnIsError)
        assertEquals("success", shown(row).getValue("a").status)
        assertEquals("error", shown(row).getValue("b").status)
    }

    @Test
    fun aParkedLaterReturnThatJoinsOnAppendStaysOffTheFirstCall() {
        val row = streamed(returned("b", "early", error = true), row("a", "b")).single()

        assertEquals("early", row.toolReturnContentByCallId["b"])
        assertNull(row.toolReturnContent)
        assertFalse(row.toolReturnIsError)
    }

    /** A live row and its reloaded copy (hydration of the same messages) show call 0 the same way. */
    @Test
    fun hydrationShowsTheFirstCallAsTheLiveRowDoes() {
        val messages = listOf(row("a", "b"), returned("b", "boom", error = true))
        val live = live(*messages.toTypedArray()).single()
        val hydrated = TimelineHydrationReducer.reduce(
            conversationId = CONVERSATION,
            serverMessagesChronological = messages,
            timelineBeforeFetch = Timeline(CONVERSATION),
            currentTimeline = Timeline(CONVERSATION),
            diskRecords = emptyList(),
        ).timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
            .single { it.messageType == TimelineMessageType.TOOL_CALL }

        assertNull(hydrated.toolReturnContent, "call a has not returned")
        assertFalse(hydrated.toolReturnIsError)
        assertEquals(display(live), display(hydrated))
    }

    @Test
    fun snapshotEnrichmentKeepsALaterCallsErrorOffTheFirstCall() {
        val seeded = Timeline(CONVERSATION).append(checkNotNull(row("a", "b").toTimelineEvent(position = 1.0)))

        val row = enrichTimelineFromSnapshot(seeded, listOf(returned("b", "boom", error = true)))
            .events.filterIsInstance<TimelineEvent.Confirmed>().single()

        assertEquals("boom", row.toolReturnContentByCallId["b"])
        assertNull(row.toolReturnContent)
        assertFalse(row.toolReturnIsError)
    }

    /** A later call's live return notifies with its own text, not the row's single return (call a's or nothing). */
    @Test
    fun aLaterCallsLiveReturnNotifiesWithItsOwnText() {
        var state = TimelineReducerState(Timeline(CONVERSATION)).reduceLive(row("a", "b"), agentId = null)
        state = state.reduceLive(returned("a", "first body"), agentId = null)

        val input = TimelineReducerInput(state.timeline, returned("b", "second body"), state.pendingToolReturnsByCallId)
        val output = reduceLiveFrame(input)

        assertEquals(PendingIngestNotification("toolcall-a", "tool_return_message", "second body"), output.notification)
    }

    /**
     * A blank call id can reach a row: the mapper falls back to the frame id only when the id is absent, and keeps an
     * explicit `"tool_call_id": ""` (IrohStreamDeltaServerFrameMapper.toToolCallPayload). A blank first call is never
     * answered (every writer skips a blank id), so a later call's return still stays off it.
     */
    @Test
    fun aBlankFirstCallIdNeverReceivesALaterCallsReturn() {
        val blankFirst = ToolCallMessage(
            id = "toolcall-blank",
            toolCalls = listOf(call(""), call("b")),
            logicalMessageId = "tc-blank",
        )

        val live = live(blankFirst, returned("b", "boom", error = true)).single()
        val stream = streamed(blankFirst, returned("b", "boom", error = true)).single()

        listOf(live, stream).forEach { row ->
            assertNull(row.toolReturnContent)
            assertFalse(row.toolReturnIsError)
            assertNull(shown(row).getValue("").result)
        }
    }

    /**
     * A re-emission of the same row that lists one more call with no more argument text used to be dropped as
     * stale, leaving that call unlisted and its return parked for good.
     */
    @Test
    fun aReEmissionListingANewCallWithoutMoreArgumentsCollectsItsParkedReturn() {
        val wide = ToolCallMessage(
            id = "toolcall-a",
            toolCalls = listOf(ToolCall(toolCallId = "a", name = "Bash", arguments = LONG_ARGUMENTS)),
            logicalMessageId = "tc-a",
        )
        val withSecond = ToolCallMessage(
            id = "toolcall-a",
            toolCalls = listOf("a", "b").map { ToolCall(toolCallId = it, name = "Bash", arguments = "{}") },
            logicalMessageId = "tc-a",
        )
        var state = TimelineReducerState(Timeline(CONVERSATION))
        listOf(wide, returned("b", "waited"), withSecond).forEach { state = state.reduceLive(it, agentId = null) }

        val row = state.timeline.events.filterIsInstance<TimelineEvent.Confirmed>().single()
        assertEquals(listOf("a", "b"), row.toolCalls.map { it.effectiveId })
        assertTrue(row.content.contains("a long command line"), "the richer arguments the row held are kept")
        assertEquals("waited", row.toolReturnContentByCallId["b"])
        assertTrue(state.pendingToolReturnsByCallId.isEmpty())
    }

    /**
     * The same calls re-emitted in another order are stamped after their new first call (`tc-b`), so a second row
     * appends. Both rows list both calls, so no return is ever parked; each return lands on a row that lists it.
     */
    @Test
    fun theSameCallsReAppendedUnderAnotherRowIdLeaveNothingParked() {
        val reordered = ToolCallMessage(id = "toolcall-b", toolCalls = listOf(call("b"), call("a")), logicalMessageId = "tc-b")
        var state = TimelineReducerState(Timeline(CONVERSATION))
        listOf(row("a", "b"), reordered, returned("a", "A"), returned("b", "B")).forEach {
            state = state.reduceLive(it, agentId = null)
        }

        assertTrue(state.pendingToolReturnsByCallId.isEmpty())
        val answeredByRow = state.timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
            .associate { it.logicalId to it.toolReturnContentByCallId.toMap() }
        assertEquals(
            mapOf("tc-a" to mapOf("a" to "A"), "tc-b" to mapOf("b" to "B")),
            answeredByRow,
        )
    }

    /**
     * Three calls, an error on a later call, a duplicate return, a return for a call no row lists, a fuller
     * re-emission, in every seeded order through the live reducer: after each frame no listed call has a parked
     * return, and at the end both what the UI reads per call and the row's single return are each call's own.
     */
    @Test
    fun everyOrderingOfAThreeCallRoundShowsEachCallItsOwnReturn() {
        val random = Random(SEED)
        repeat(ITERATIONS) { iteration ->
            val frames = (
                listOf(
                    returned("a", "A"),
                    returned("b", "B failed", error = true),
                    returned("c", "C"),
                    returned("b", "B failed", error = true),
                    returned("z", "stray"),
                    row("a", "b", "c"),
                ) + if (random.nextBoolean()) listOf(row("a")) else emptyList()
                ).shuffled(random)
            var state = TimelineReducerState(Timeline(CONVERSATION))
            frames.forEach { frame ->
                state = state.reduceLive(frame, agentId = null)
                val listed = state.timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
                    .flatMap { row -> row.toolCalls.map { it.effectiveId } }.toSet()
                assertTrue(state.pendingToolReturnsByCallId.keys.none { it in listed }, "iteration $iteration: $frames")
            }

            val row = state.timeline.events.filterIsInstance<TimelineEvent.Confirmed>().single()
            val tag = "iteration $iteration: $frames"
            assertEquals(listOf("a", "b", "c"), row.toolCalls.map { it.effectiveId }, tag)
            assertEquals(mapOf("a" to "A", "b" to "B failed", "c" to "C"), row.toolReturnContentByCallId.toMap(), tag)
            assertEquals(mapOf("a" to false, "b" to true, "c" to false), row.toolReturnIsErrorByCallId.toMap(), tag)
            assertEquals("A", row.toolReturnContent, tag)
            assertFalse(row.toolReturnIsError, tag)
            assertEquals(setOf("z"), state.pendingToolReturnsByCallId.keys, "$tag: only the unlisted return is parked")
            val calls = shown(row)
            val statuses = listOf("a", "b", "c").map { calls.getValue(it).status }
            assertEquals(listOf("success", "error", "success"), statuses, tag)
        }
    }

    /** The park path: a return no row lists walks the whole window, attaches to nothing and is kept by its id. */
    @Test
    fun aReturnNoRowListsParksAfterWalkingEveryRowAndChangesNone() {
        var state = TimelineReducerState(Timeline(CONVERSATION))
        repeat(ROWS) { state = state.reduceLive(row("call-$it"), agentId = null) }
        val before = state.timeline

        state = state.reduceLive(returned("unlisted", "nobody's"), agentId = null)

        assertEquals(before, state.timeline)
        assertEquals(listOf("unlisted"), state.pendingToolReturnsByCallId.keys.toList())
    }

    private fun row(vararg ids: String) = ToolCallMessage(
        id = "toolcall-${ids.first()}",
        toolCalls = ids.map(::call),
        logicalMessageId = "tc-${ids.first()}",
    )

    private fun call(id: String) = ToolCall(toolCallId = id, name = "Bash", arguments = """{"command":"$id"}""")

    private fun returned(id: String, body: String, error: Boolean = false) = ToolReturnMessage(
        id = "toolreturn-$id",
        toolReturnRaw = JsonPrimitive(body),
        toolCallId = id,
        status = if (error) "error" else "success",
        logicalMessageId = "tr-$id",
    )

    private fun live(vararg frames: LettaMessage): List<TimelineEvent.Confirmed> {
        var state = TimelineReducerState(Timeline(CONVERSATION))
        frames.forEach { state = state.reduceLive(it, agentId = null) }
        return state.timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
    }

    private fun streamed(vararg frames: LettaMessage): List<TimelineEvent.Confirmed> {
        var timeline = Timeline(CONVERSATION)
        var pending = persistentMapOf<String, ToolReturnMessage>()
        frames.forEach { frame ->
            val output = reduceStreamFrame(TimelineReducerInput(timeline, frame, pending))
            timeline = output.next
            pending = output.updatedPendingToolReturnsByCallId
        }
        return timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.messageType == TimelineMessageType.TOOL_CALL }
    }

    /** What the chat list draws for each call of [row], by call id. */
    private fun shown(row: TimelineEvent.Confirmed): Map<String, UiToolCall> =
        assertNotNull(timelineEventToUiMessage(row)).toolCalls.orEmpty().associateBy { it.toolCallId.orEmpty() }

    private fun display(row: TimelineEvent.Confirmed) = shown(row).mapValues { (_, call) -> call.result to call.status }

    private companion object {
        const val LONG_ARGUMENTS = """{"command":"a long command line"}"""
        const val CONVERSATION = "conversation"
        const val SEED = 20_261_126
        const val ITERATIONS = 300
        const val ROWS = 500
    }
}
