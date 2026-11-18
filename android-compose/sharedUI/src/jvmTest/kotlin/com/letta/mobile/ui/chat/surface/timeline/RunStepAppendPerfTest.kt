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
import com.letta.mobile.ui.chat.surface.timeline.rows.LocalRowComposed
import com.letta.mobile.ui.chat.surface.timeline.rows.TimelineRenderProbe
import com.letta.mobile.ui.chat.surface.timeline.rows.TimelineRenderSink
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A live run's steps append without restructuring its row: when a run's first step is followed by
 * its second (the lone first message becoming a run block), the row keeps its composition (no
 * dispose and remount of the run's row), and the frame that lands the second step costs about what
 * the third's does: for a run of bare tool calls, and for a run that opens with prose (whose message
 * was drawn through a separate lone-message branch and rebuilt when the second step landed).
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

    /** A run that opens with prose (drawn as a lone step) and then works. */
    @Test
    fun aRunOpeningWithProseAppendsItsFirstToolWithoutRebuilding() = appendSteps(firstStep = { run, _ -> prose(run) })

    /**
     * Plays the run once to warm the JIT and class loading (a first use of the live run's code would
     * otherwise be measured), then again in a fresh composition, measured.
     */
    private fun appendSteps(firstStep: (String, Int) -> UiMessage) {
        landSteps(firstStep)
        val landed = landSteps(firstStep)
        landed.forEach { (index, landing) ->
            println("step $index: ${landing.frameMillis} ms, events=${landing.events}, composed=${landing.composed}")
        }
        val second = landed.getValue(2)
        assertTrue(second.events.none { it == "dispose $RUN_KEY" }, "the run's row was torn down at its second step: ${second.events}")
        assertTrue(second.events.none { it == "mount $RUN_KEY" }, "the run's row was built again at its second step: ${second.events}")
        val third = landed.getValue(3).frameMillis.coerceAtLeast(1.0)
        assertTrue(
            second.frameMillis <= third * RELATIVE_BUDGET + ABSOLUTE_SLACK_MILLIS,
            "landing the second step took ${second.frameMillis} ms against the third's $third ms",
        )
    }

    /** One run, its steps landing one frame at a time: each landing's frame time, probe events and row compositions. */
    private fun landSteps(firstStep: (String, Int) -> UiMessage): Map<Int, StepLanding> {
        var landed: Map<Int, StepLanding> = emptyMap()
        messages = history()
        runComposeUiTest {
            TimelineRenderProbe.sink = TimelineRenderSink { name, key, _, _, _, _ ->
                if (name != "resident") events += "$name $key"
            }
            mainClock.autoAdvance = false
            show()
            settle()
            messages = messages + firstStep(RUN_ID, 1)
            settle()
            landed = (2..LAST_STEP).associateWith { index ->
                events.clear()
                composed.clear()
                messages = messages + step(RUN_ID, index)
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
        tool("old-1", "old-run", 1),
        tool("old-2", "old-run", 2),
        UiMessage(id = "old-a", role = "assistant", content = PROSE, timestamp = ts(3), runId = "old-run"),
        UiMessage(id = "u0", role = "user", content = "Check the build and the tests", timestamp = ts(4)),
    )

    private fun step(run: String, index: Int) = tool("$run-step-$index", run, index)

    private fun prose(run: String) =
        UiMessage(id = "$run-step-1", role = "assistant", content = LIVE_PROSE, timestamp = ts(11), runId = run)

    private fun tool(id: String, runId: String, index: Int) = UiMessage(
        id = id,
        role = "assistant",
        content = "",
        timestamp = ts(10 + index),
        runId = runId,
        toolCalls = listOf(
            UiToolCall(
                name = "Bash",
                arguments = """{"command":"./gradlew :module$index:check --console=plain"}""",
                result = "BUILD SUCCESSFUL in ${index}s",
                status = "success",
                toolCallId = "$id-call",
            ),
        ),
    )

    private fun ts(second: Int) = "2026-11-17T09:00:%02dZ".format(second)

    private companion object {
        const val RUN_ID = "live-run"
        const val LIVE_PROSE = "Checking the build first, then the tests.\n\nI'll report what fails."
        const val RUN_KEY = "run-$RUN_ID"
        const val LAST_STEP = 4
        const val SETTLE_FRAMES = 30
        const val NANOS_PER_MILLI = 1_000_000.0
        const val RELATIVE_BUDGET = 3.0
        const val ABSOLUTE_SLACK_MILLIS = 8.0
        val PROSE = (1..12).joinToString("\n\n") { "Paragraph $it of the earlier reply, with **bold** and `code`." }
    }
}
