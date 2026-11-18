@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import com.letta.mobile.ui.chat.surface.timeline.rows.LocalRowComposed
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
 * dispose and remount of the run's row), and the frame that lands the second step costs at most 1.3x
 * the median of the later steps' frames: for a run of bare tool calls, and for a run that opens with prose (whose message
 * was drawn through a separate lone-message branch and rebuilt when the second step landed); a run that
 * opens with reasoning keeps that reasoning's own composition too.
 */
class RunStepAppendPerfTest {
    private val events = mutableListOf<String>()
    private val composed = mutableMapOf<String, Int>()
    private var messages by mutableStateOf(history())

    /** One counter for the whole test: a new lambda per composition would itself recompose every row. */
    private val countComposition: (String) -> Unit = { key -> composed[key] = (composed[key] ?: 0) + 1 }

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
    fun aRunsLoneReasoningKeepsItsCompositionAtTheSecondStep() {
        messages = history()
        runComposeUiTest {
            mainClock.autoAdvance = false
            show()
            settle()
            messages = messages + reasoning(RunStepId(RUN_ID, 1))
            settle()
            val before = reasoningGlyphNode()
            messages = messages + step(RunStepId(RUN_ID, 2))
            settle()
            assertEquals(before, reasoningGlyphNode(), "the run's lone reasoning was rebuilt when its second step landed")
        }
    }

    private fun ComposeUiTest.reasoningGlyphNode(): Int =
        onAllNodesWithTag(ChatRowTestTags.REASONING_GLYPH, useUnmergedTree = true).fetchSemanticsNodes().single().id

    /**
     * Plays the run once to warm the JIT and class loading (a first use of the live run's code would
     * otherwise be measured), then again in a fresh composition, measured.
     */
    private fun appendSteps(timed: Boolean = true, firstStep: (RunStepId) -> UiMessage) {
        landSteps(firstStep)
        val passes = List(PASSES) { landSteps(firstStep) }
        passes.forEachIndexed { pass, landed ->
            landed.forEach { (index, landing) ->
                println("pass $pass step $index: ${landing.frameMillis} ms, events=${landing.events}, composed=${landing.composed}")
            }
            val second = landed.getValue(2)
            assertTrue(second.events.none { it == "dispose $RUN_KEY" }, "the run's row was torn down at its second step: ${second.events}")
            assertTrue(second.events.none { it == "mount $RUN_KEY" }, "the run's row was built again at its second step: ${second.events}")
        }
        // Each step's frame as the median over the passes, so one noisy frame on the runner cannot decide.
        val frame = (2..LAST_STEP).associateWith { index -> median(passes.map { it.getValue(index).frameMillis }) }
        val later = median((3..LAST_STEP).map { frame.getValue(it) })
        println("medians: $frame, later steps: $later ms")
        if (!timed) return
        assertTrue(
            frame.getValue(2) <= later * RELATIVE_BUDGET,
            "landing the second step took ${frame.getValue(2)} ms against the later steps' median of $later ms",
        )
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    /** One run, its steps landing one frame at a time: each landing's frame time, probe events and row compositions. */
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
                composed.clear()
                messages = messages + step(RunStepId(RUN_ID, index))
                val start = System.nanoTime()
                mainClock.advanceTimeByFrame()
                val frameMillis = (System.nanoTime() - start) / NANOS_PER_MILLI
                val result = StepLanding(frameMillis, events.toList(), composed.toMap())
                settle()
                result
            }
        }
        TimelineRenderProbe.sink = null
        return landed
    }

    private class StepLanding(val frameMillis: Double, val events: List<String>, val composed: Map<String, Int>)

    private fun ComposeUiTest.show() {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalRowComposed provides countComposition) {
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
                        )
                    }
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
        const val LIVE_PROSE = "Checking the build first, then the tests.\n\nI'll report what fails."
        const val RUN_KEY = "run-$RUN_ID"
        const val LAST_STEP = 5
        const val SETTLE_FRAMES = 30
        const val NANOS_PER_MILLI = 1_000_000.0
        const val RELATIVE_BUDGET = 1.3
        const val PASSES = 3
        val PROSE = (1..12).joinToString("\n\n") { "Paragraph $it of the earlier reply, with **bold** and `code`." }
    }
}
