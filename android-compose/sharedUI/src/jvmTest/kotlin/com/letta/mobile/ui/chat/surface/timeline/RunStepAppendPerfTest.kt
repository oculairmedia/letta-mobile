@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowTestTags
import com.letta.mobile.ui.chat.surface.timeline.rows.TimelineRenderProbe
import com.letta.mobile.ui.chat.surface.timeline.rows.TimelineRenderSink
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A live run's steps append without restructuring its row: when a run's first step is followed by
 * its second (the lone first message becoming a run block), the row keeps its composition (no
 * dispose and remount of the run's row, and the row still held under the key its first step gave it,
 * so each step appends into it): for a run of bare tool calls, and for a run that opens with prose or
 * reasoning (whose message was drawn through a separate lone-message branch and rebuilt when the
 * second step landed). Each is deterministic on any runner, so CI never flakes on frame time.
 *
 * The step-2 frame time is printed for whoever reads a run, and only asserted when explicitly
 * requested: `LETTA_PERF_ASSERTIONS=1 ./gradlew :sharedUI:jvmTest --tests '*RunStepAppendPerfTest'`
 * (see [TIMED_ASSERTION_ENV]).
 */
class RunStepAppendPerfTest {
    private val events = mutableListOf<String>()

    /** The list's own state, hoisted so a landing can read the row keys it actually holds. */
    private lateinit var listState: LazyListState
    private var messages by mutableStateOf(history())

    @AfterTest
    fun clearProbe() {
        TimelineRenderProbe.sink = null
    }

    @Test
    fun theSecondStepAppendsWithoutRebuildingTheRun() = appendSteps(firstStep = ::step)

    /**
     * A run that opens with an older reply's text: echo compaction drops it once the run has work, so the
     * second step really does take a long block away (no frame bound). Its key must not follow its
     * newest step, so its row is never torn down and rebuilt.
     */
    @Test
    fun aRunWhoseOpeningEchoesAnOlderReplyKeepsItsRow() = appendSteps(timed = false, firstStep = { step ->
        UiMessage(id = step.messageId, role = "assistant", content = PROSE, timestamp = ts(11), runId = step.run)
    })

    /** A run that opens with prose (drawn as a lone step) and then works. */
    @Test
    fun aRunOpeningWithProseAppendsItsFirstToolWithoutRebuilding() = appendSteps(firstStep = ::prose)

    /** A run that opens with reasoning (drawn as the run's lone row) and then works: its row is never rebuilt. */
    @Test
    fun aRunOpeningWithReasoningAppendsItsFirstToolWithoutRebuilding() =
        appendSteps(timed = false, firstStep = ::reasoning)

    /**
     * The lone reasoning's own subtree survives the run's second step: its glyph is the same layout node
     * before and after (a step drawn through another call site at step two would be a new node).
     */
    @Test
    fun aRunsLoneReasoningKeepsItsCompositionAtTheSecondStep() = assertFirstStepSurvivesTheSecond(
        firstStep = ::reasoning,
        node = { reasoningGlyphNode() },
        what = "the run's lone reasoning",
    )

    /**
     * The opening prose's own subtree survives the run's second step: its text is the same layout node
     * before and after. This is the deterministic half of the prose-first case; the frame time is the other.
     */
    @Test
    fun aRunsOpeningProseKeepsItsCompositionAtTheSecondStep() = assertFirstStepSurvivesTheSecond(
        firstStep = ::prose,
        node = { proseNode() },
        what = "the run's opening prose",
    )

    /** Lands [firstStep] then a tool step, and asserts [node] (the first step's layout node) is the same one after. */
    private fun assertFirstStepSurvivesTheSecond(
        firstStep: (RunStepId) -> UiMessage,
        node: ComposeUiTest.() -> Int,
        what: String,
    ) {
        messages = history()
        runComposeUiTest {
            mainClock.autoAdvance = false
            show()
            settle()
            messages = messages + firstStep(RunStepId(RUN_ID, 1))
            settle()
            val before = node()
            messages = messages + step(RunStepId(RUN_ID, 2))
            settle()
            assertEquals(before, node(), "$what was rebuilt when its second step landed")
        }
    }

    private fun ComposeUiTest.proseNode(): Int =
        onAllNodes(hasText(LIVE_PROSE_FIRST_LINE, substring = true), useUnmergedTree = true).fetchSemanticsNodes().single().id

    private fun ComposeUiTest.reasoningGlyphNode(): Int =
        onAllNodesWithTag(ChatRowTestTags.REASONING_GLYPH, useUnmergedTree = true).fetchSemanticsNodes().single().id

    /**
     * Plays the run once to warm the JIT and class loading (a first use of the live run's code would
     * otherwise be measured), then again in [PASSES] fresh compositions.
     *
     * The rebuild guard is deterministic and always runs: at its second step the run's row is neither
     * disposed nor mounted again (the probe), and the list still keys it by its run, never by a message
     * inside it. The step-2 frame time is only asserted when [timed] and the environment ask for it.
     */
    private fun appendSteps(timed: Boolean = true, firstStep: (RunStepId) -> UiMessage) {
        landSteps(firstStep)
        val passes = List(PASSES) { landSteps(firstStep) }
        passes.forEachIndexed { pass, landed ->
            landed.forEach { (index, landing) ->
                println("pass $pass step $index: ${landing.frameMillis} ms, events=${landing.events}, keys=${landing.keys}")
            }
            assertNoRebuildAtSecondStep(landed)
        }
        val frame = (2..LAST_STEP).associateWith { index -> median(passes.map { it.getValue(index).frameMillis }) }
        val later = median((3..LAST_STEP).map { frame.getValue(it) })
        println("medians: $frame, later steps: $later ms")
        if (!timed || !assertTimings()) return
        // A shared runner's frames jitter by a few milliseconds whatever the code does: the bound is the
        // later steps' median with relative headroom plus a small absolute slack, so only a real rebuild
        // (a step-2 frame several times the others) can exceed it.
        assertTrue(
            frame.getValue(2) <= later * RELATIVE_BUDGET + JITTER_SLACK_MILLIS,
            "landing the second step took ${frame.getValue(2)} ms against the later steps' median of $later ms",
        )
    }

    /**
     * [landed]'s second step left the run's row alone: no dispose, no remount, and the row still held
     * under the key its first step gave it. The key is checked at every landing, because a block named by
     * its newest message ([RUN_KEY]-<newest id>) or one echo compaction drops went on re-keying the row at
     * every later step too, disposing and rebuilding it each time.
     */
    private fun assertNoRebuildAtSecondStep(landed: Map<Int, StepLanding>) {
        val second = landed.getValue(2)
        assertTrue(second.events.none { it == "dispose $RUN_KEY" }, "the run's row was torn down at its second step: ${second.events}")
        assertTrue(second.events.none { it == "mount $RUN_KEY" }, "the run's row was built again at its second step: ${second.events}")
        landed.forEach { (index, landing) ->
            // The list holds the run's row under its first step's key, so each step appends into that row
            // instead of handing the list a new key to build a row under. This is the measured cause of the
            // hitch the frame time used to bound, and it reads the same on every runner.
            assertTrue(
                landing.keys[RUN_KEY] == 1,
                "the run's row was not held under $RUN_KEY when its step $index landed: rows keyed ${landing.keys}",
            )
        }
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    /**
     * One run, its steps landing one frame at a time: each landing's frame time, its probe events and
     * the keys the list held when it landed.
     */
    private fun landSteps(firstStep: (RunStepId) -> UiMessage): Map<Int, StepLanding> {
        var landed: Map<Int, StepLanding> = emptyMap()
        messages = history()
        runComposeUiTest {
            TimelineRenderProbe.sink = TimelineRenderSink { name, key, _, _, _, _ ->
                if (name != "resident") events += "$name $key"
            }
            mainClock.autoAdvance = false
            show()
            settle()
            messages = messages + firstStep(RunStepId(RUN_ID, 1))
            settle()
            landed = (2..LAST_STEP).associateWith { index ->
                events.clear()
                messages = messages + step(RunStepId(RUN_ID, index))
                val start = System.nanoTime()
                mainClock.advanceTimeByFrame()
                val frameMillis = (System.nanoTime() - start) / NANOS_PER_MILLI
                val result = StepLanding(frameMillis, events.toList(), listKeys().groupingBy { it }.eachCount())
                settle()
                result
            }
        }
        TimelineRenderProbe.sink = null
        return landed
    }

    /** One step's landing: its frame time, the probe events it raised, and how many of the list's rows hold each key. */
    private class StepLanding(
        val frameMillis: Double,
        val events: List<String>,
        val keys: Map<String, Int>,
    )

    /** The keys of the rows the list holds at this instant (its laid-out slots; the list fills the page). */
    private fun listKeys(): List<String> = listState.layoutInfo.visibleItemsInfo.map { it.key as String }

    private fun ComposeUiTest.show() {
        setContent {
            listState = rememberLazyListState()
            MaterialTheme {
                Box(Modifier.size(width = 900.dp, height = 1400.dp)) {
                    ChatTimeline(
                        state = ChatUiState(
                            conversationState = ConversationState.Ready("c1"),
                            isLoadingMessages = false,
                            messages = messages.toPersistentList(),
                            isStreaming = true,
                        ),
                        pagedTimeline = null,
                        actions = RecordingChatActions(),
                        capabilities = ChatSurfaceCapabilities.Default,
                        host = ChatSurfaceHost(),
                        appearance = ChatSurfaceAppearance(),
                        listState = listState,
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.settle() = repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }

    /** An older two-step run (so the run block's code is warm), then the prompt the live run answers. */
    private fun history(): List<UiMessage> = listOf(
        UiMessage(id = "old-u", role = "user", content = "Earlier question", timestamp = ts(0)),
        step(RunStepId("old-run", 1)),
        step(RunStepId("old-run", 2)),
        UiMessage(id = "old-a", role = "assistant", content = PROSE, timestamp = ts(3), runId = "old-run"),
        UiMessage(id = "u0", role = "user", content = "Check the build and the tests", timestamp = ts(4)),
    )

    /** One step of a run: its run id and its place in the run (1 first). */
    private data class RunStepId(val run: String, val index: Int) {
        val messageId: String get() = "$run-step-$index"
    }

    private fun prose(step: RunStepId) =
        UiMessage(id = step.messageId, role = "assistant", content = LIVE_PROSE, timestamp = ts(11), runId = step.run)

    private fun reasoning(step: RunStepId) = UiMessage(
        id = step.messageId,
        role = "assistant",
        content = "Looking at which module fails first.",
        timestamp = ts(11),
        runId = step.run,
        isReasoning = true,
    )

    /** A tool step: one Bash call in [step]'s run. */
    private fun step(step: RunStepId) = UiMessage(
        id = step.messageId,
        role = "assistant",
        content = "",
        timestamp = ts(10 + step.index),
        runId = step.run,
        toolCalls = listOf(
            UiToolCall(
                name = "Bash",
                arguments = """{"command":"./gradlew :module${step.index}:check --console=plain"}""",
                result = "BUILD SUCCESSFUL in ${step.index}s",
                status = "success",
                toolCallId = "${step.messageId}-call",
            ),
        ),
    )

    private fun ts(second: Int) = "2026-11-17T09:00:%02dZ".format(second)

    private companion object {
        const val RUN_ID = "live-run"
        const val LIVE_PROSE_FIRST_LINE = "Checking the build first, then the tests."
        const val LIVE_PROSE = "$LIVE_PROSE_FIRST_LINE\n\nI'll report what fails."
        const val RUN_KEY = "run-$RUN_ID"
        const val LAST_STEP = 5
        const val SETTLE_FRAMES = 30
        const val NANOS_PER_MILLI = 1_000_000.0

        /**
         * The frame-time assertion is opt-in, the way this module's other opt-in probes are (an
         * environment variable, since Gradle cannot pass a `-D` to the forked test JVM): run
         * `LETTA_PERF_ASSERTIONS=1 ./gradlew :sharedUI:jvmTest --tests '*RunStepAppendPerfTest'` and the
         * step-2 frame must stay within this budget of the later steps. CI's shared runners cannot hold
         * a wall-clock bound, so they never set it.
         */
        const val TIMED_ASSERTION_ENV = "LETTA_PERF_ASSERTIONS"
        const val RELATIVE_BUDGET = 1.5
        const val JITTER_SLACK_MILLIS = 5.0
        const val PASSES = 5
        val PROSE = (1..12).joinToString("\n\n") { "Paragraph $it of the earlier reply, with **bold** and `code`." }

        /** Whether this run asked for the frame-time assertion ([TIMED_ASSERTION_ENV]). */
        fun assertTimings(): Boolean = System.getenv(TIMED_ASSERTION_ENV) == "1"
    }
}
