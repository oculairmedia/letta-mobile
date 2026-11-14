@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowTestTags
import com.letta.mobile.ui.chat.surface.timeline.rows.awaitText
import com.letta.mobile.ui.chat.surface.timeline.rows.renderState
import com.letta.mobile.ui.chat.surface.timeline.rows.rowCallbacks
import com.letta.mobile.ui.chat.surface.timeline.rows.rowContext
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A notice line keeps the timeline's rhythm, measured between the ink of neighbouring rows laid out
 * with the list's own spacing (each row's leading space decided against the rows around it), at
 * every width on touch and pointer:
 * - prose to a notice is the gap from prose to a run header;
 * - a notice to the tool step its report woke is the gap from a run header to its first step;
 * - that step to the prose after it is the gap from a run's last step to its prose;
 * - a notice to the prose after it is the gap from a folded run's header to the prose after the run.
 */
class TimelineNoticeRhythmUiTest {
    @Test
    fun aNoticeSpacesLikeARunHeader() {
        for (width in listOf(360.dp, 700.dp, 760.dp, 1200.dp)) {
            for (host in listOf(LettaHost.Touch, LettaHost.Pointer)) check(Environment(width, host))
        }
    }

    private fun check(environment: Environment) {
        val notice = measure(environment, listOf(prose, report, after))
        val woken = measure(environment, listOf(prose, report, step, after))
        val run = measure(environment, listOf(prose, openRun), collapsedRunIds = setOf(RUN_ID))
        val folded = measure(environment, listOf(prose, foldedRun, after))
        val gaps = listOf(
            Gap("prose -> notice vs prose -> run header", notice.gap(PROSE, NOTICE), run.gap(PROSE, HEADER)),
            Gap("notice -> woken step vs run header -> first step", woken.gap(NOTICE, STEP), run.gap(HEADER, STEP)),
            Gap("woken step -> prose vs run step -> its prose", woken.gap(STEP, AFTER), run.gap(STEP, AFTER)),
            Gap("notice -> prose vs folded run header -> prose", notice.gap(NOTICE, AFTER), folded.gap(HEADER, AFTER)),
        )
        val off = gaps.filterNot { abs(it.measured.value - it.expected.value) <= TOLERANCE.value }
        assertEquals(emptyList(), off, "$environment")
    }

    /** Lays [items] out as the list does and returns each line's ink bounds by its label. */
    private fun measure(environment: Environment, items: List<ChatRenderItem>, collapsedRunIds: Set<String> = emptySet()): Ink {
        var ink = Ink(emptyMap())
        runDesktopComposeUiTest(width = 1400, height = 1600) {
            setContent {
                Host(environment) {
                    val context = rowContext(itemState = renderState(collapsedRunIds = collapsedRunIds), newestMessageId = NEWEST_ID)
                    val bindings = TimelineRowBindings(TimelineRowContexts(context, streaming = null), rowCallbacks())
                    items.forEachIndexed { index, item ->
                        TimelineItemRow(item, bindings, older = items.getOrNull(index - 1), newer = items.getOrNull(index + 1))
                    }
                }
            }
            awaitText(PROSE)
            awaitText(AFTER)
            ink = Ink(listOf(PROSE, NOTICE, HEADER, STEP, AFTER).mapNotNull { label -> textBounds(label)?.let { label to it } }.toMap())
        }
        return ink
    }

    private fun ComposeUiTest.textBounds(label: String): DpRect? {
        if (label == STEP) {
            val texts = onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and hasAnyAncestor(hasTestTag(ChatRowTestTags.TOOL_CARD_TOGGLE)),
                useUnmergedTree = true,
            )
            return List(texts.fetchSemanticsNodes().size) { texts[it].getUnclippedBoundsInRoot() }.minByOrNull { it.left }
        }
        val nodes = onAllNodes(hasText(label, substring = true), useUnmergedTree = true)
        return List(nodes.fetchSemanticsNodes().size) { nodes[it].getUnclippedBoundsInRoot() }.minByOrNull { it.bottom - it.top }
    }

    private class Ink(val bounds: Map<String, DpRect>) {
        /** From the bottom of [upper]'s ink to the top of [lower]'s. */
        fun gap(upper: String, lower: String): Dp = bounds.getValue(lower).top - bounds.getValue(upper).bottom
    }

    private data class Gap(val what: String, val measured: Dp, val expected: Dp)

    private class Environment(val width: Dp, val host: LettaHost) {
        override fun toString() = "at $width on $host"
    }

    @Composable
    private fun Host(environment: Environment, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalReducedMotion provides true, LocalLettaHost provides environment.host) {
            MaterialTheme {
                Column(Modifier.width(environment.width)) { content() }
            }
        }
    }

    private val bash = UiToolCall(name = "Bash", arguments = """{"command":"ls"}""", result = "ok", status = "success")

    private val prose =
        ChatRenderItem.Single(UiMessage(id = "prose", role = "assistant", content = PROSE, timestamp = T0), GroupPosition.First)

    private val report = ChatRenderItem.Single(
        UiMessage(
            id = "report", role = "assistant", content = "", timestamp = T1,
            subagentNotification = UiSubagentNotification("completed", NOTICE, "$ git fetch\n\n[stdout]\nok", null, null, "bash_12"),
        ),
        GroupPosition.Middle,
    )

    private val step = ChatRenderItem.Single(
        UiMessage(id = "step", role = "assistant", content = "", timestamp = T1, toolCalls = listOf(bash.copy(toolCallId = "call-step"))),
        GroupPosition.Middle,
    )

    private val after =
        ChatRenderItem.Single(UiMessage(id = NEWEST_ID, role = "assistant", content = AFTER, timestamp = T2), GroupPosition.Last)

    /** A run opened by its owner: its header, its tool step, then its prose. */
    private val openRun = ChatRenderItem.RunBlock(
        runId = RUN_ID,
        messages = listOf(
            UiMessage(id = "run-step", role = "assistant", content = "", timestamp = T1, runId = RUN_ID, toolCalls = listOf(bash)),
            UiMessage(id = NEWEST_ID, role = "assistant", content = AFTER, timestamp = T2, runId = RUN_ID),
        ).map { it to GroupPosition.Last },
    )

    /** A settled run of one tool call: folded to its header. */
    private val foldedRun = ChatRenderItem.RunBlock(
        runId = "run-folded",
        messages = listOf(
            UiMessage(id = "folded-step", role = "assistant", content = "", timestamp = T1, runId = "run-folded", toolCalls = listOf(bash)),
        ).map { it to GroupPosition.None },
    )

    private companion object {
        const val RUN_ID = "run-rhythm"
        const val NEWEST_ID = "after"
        const val PROSE = "Shall I start it?"
        const val NOTICE = "Background command \"Fetch origin/main\" completed"
        const val HEADER = "Worked"
        const val STEP = "step"
        const val AFTER = "All green."
        const val T0 = "2026-10-11T16:00:00Z"
        const val T1 = "2026-10-11T16:01:00Z"
        const val T2 = "2026-10-11T16:02:00Z"
        val TOLERANCE = 0.5.dp
    }
}
