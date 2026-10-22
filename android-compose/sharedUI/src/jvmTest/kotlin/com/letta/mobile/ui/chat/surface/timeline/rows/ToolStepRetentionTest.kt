@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolStepExpansions
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb
import com.letta.mobile.data.chat.tools.bundled.BundledToolPresenters
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.tools.LocalToolPresenters
import com.letta.mobile.ui.chat.tools.LocalToolStepExpansions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-2ccpv: a long timeline of collapsed steps costs a line each and nothing more: no
 * inset (and so no copy of any output) is built, and no per-step open state is kept, however far
 * it is scrolled. Opening one step builds one inset and keeps one key.
 */
class ToolStepRetentionTest {
    /** The bundled Bash line, counting every inset it is asked for. */
    private class CountingBash : ToolPresenter {
        var insets = 0
        override val id = "bash"
        override val matcher = ToolMatcher(listOf("Bash"))
        override fun line(call: ToolCallFacts, scope: ToolPresenterScope) = ToolStepLine(ToolVerb.Ran, target = "echo", presenterId = id)
        override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? {
            insets++
            return ToolInset.Text(call.result.orEmpty())
        }
    }

    private val calls = (1..STEPS).map { index ->
        UiToolCall(
            name = "Bash",
            arguments = """{"command":"echo $index"}""",
            result = "x".repeat(OUTPUT_CHARS),
            status = "success",
            toolCallId = "call-$index",
        )
    }

    @Test
    fun aLongTimelineOfCollapsedStepsKeepsNoOpenStateAndBuildsNoInset() = runComposeUiTest {
        val bash = CountingBash()
        val expansions = ToolStepExpansions()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalToolPresenters provides BundledToolPresenters.registry.register(bash),
                    LocalToolStepExpansions provides expansions,
                ) {
                    LazyColumn(Modifier.height(VIEWPORT.dp).testTag(LIST)) {
                        items(calls, key = { it.toolCallId.orEmpty() }) { call ->
                            ToolStep(call, call.toolCallId.orEmpty(), rowCallbacks())
                        }
                    }
                }
            }
        }

        onNodeWithTag(LIST).performScrollToIndex(STEPS - 1)
        onNodeWithTag(LIST).performScrollToIndex(0)
        runOnIdle {
            assertEquals(0, bash.insets, "a collapsed step built its inset")
            assertEquals(emptyList(), expansions.toggled.value, "collapsed steps kept open state")
        }

        onAllNodesWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).onFirst().performClick()
        runOnIdle {
            assertEquals(1, bash.insets)
            assertEquals(listOf("call-1"), expansions.toggled.value)
        }
    }

    private companion object {
        const val STEPS = 300
        const val OUTPUT_CHARS = 20_000
        const val VIEWPORT = 400
        const val LIST = "steps"
    }
}
