package com.letta.mobile.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.letta.mobile.feature.chat.screen.RunBlock
import com.letta.mobile.feature.chat.screen.RunBlockTestTags

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class RunBlockStreamingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dotsKeepStablePositionsWhenStreamingStepAppendsAndSettles() {
        val firstFrameMessages = listOf(
            message(id = "reasoning-1", content = "Looking at the run", isReasoning = true),
            message(id = "assistant-1", content = "Initial answer"),
        )
        val messages = mutableStateOf(firstFrameMessages)
        val rowCompositionCounts = mutableMapOf<String, Int>()

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    RunBlock(
                        messages = messages.value,
                        collapsed = false,
                        onToggleCollapsed = {},
                        isStreaming = messages.value.any { it.isPending },
                    ) { message, _, rowModifier ->
                        SideEffect {
                            rowCompositionCounts[message.id] =
                                rowCompositionCounts.getOrDefault(message.id, 0) + 1
                        }
                        Box(
                            modifier = rowModifier
                                .height(40.dp)
                                .testTag("run-row-${message.id}"),
                        ) {
                            Text(text = message.content.ifBlank { message.id })
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val firstFrameTops = dotTops("reasoning-1", "assistant-1")
        assertPositiveAndStrictlyIncreasing(firstFrameTops)
        val firstFrameCompositions = rowCompositionCounts.toMap()

        composeRule.runOnIdle {
            messages.value = firstFrameMessages + message(
                id = "assistant-2",
                content = "Streaming tail",
                isPending = true,
            )
        }
        composeRule.waitForIdle()

        val streamingTops = dotTops("reasoning-1", "assistant-1", "assistant-2")
        assertPositiveAndStrictlyIncreasing(streamingTops)
        assertNearlyEquals(firstFrameTops[0], streamingTops[0])
        assertNearlyEquals(firstFrameTops[1], streamingTops[1])
        val streamingCompositions = rowCompositionCounts.toMap()
        assertRecompositionsBounded(
            before = firstFrameCompositions,
            after = streamingCompositions,
            stage = "streaming append",
            ids = arrayOf("reasoning-1", "assistant-1"),
        )

        composeRule.runOnIdle {
            messages.value = firstFrameMessages + message(
                id = "assistant-2",
                content = "Streaming tail",
                isPending = false,
            )
        }
        composeRule.waitForIdle()

        val settledTops = dotTops("reasoning-1", "assistant-1", "assistant-2")
        assertPositiveAndStrictlyIncreasing(settledTops)
        streamingTops.zip(settledTops).forEach { (streaming, settled) ->
            assertNearlyEquals(streaming, settled)
        }
        assertRecompositionsBounded(
            before = streamingCompositions,
            after = rowCompositionCounts.toMap(),
            stage = "streaming settle",
            ids = arrayOf("reasoning-1", "assistant-1", "assistant-2"),
        )
    }

    private fun dotTops(vararg ids: String): List<Float> =
        ids.map { id ->
            composeRule.onNodeWithTag(RunBlockTestTags.dot(id))
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        }

    private fun assertPositiveAndStrictlyIncreasing(tops: List<Float>) {
        tops.forEach { top ->
            assertTrue("Expected dot top to be below the root origin, got $top", top > 0f)
        }
        tops.zipWithNext().forEach { (previous, next) ->
            assertTrue("Expected dot positions to increase, got $tops", next > previous)
        }
    }

    private fun assertNearlyEquals(
        expected: Float,
        actual: Float,
    ) {
        assertEquals(expected, actual, 0.5f)
    }

    private fun assertRecompositionsBounded(
        before: Map<String, Int>,
        after: Map<String, Int>,
        stage: String,
        ids: Array<String>,
    ) {
        ids.forEach { id ->
            val delta = after.getOrDefault(id, 0) - before.getOrDefault(id, 0)
            assertTrue(
                "Expected at most 2 row recompositions for $id during $stage, got $delta; " +
                    "before=$before after=$after",
                delta <= 2,
            )
        }
    }

    private fun message(
        id: String,
        content: String,
        isPending: Boolean = false,
        isReasoning: Boolean = false,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = "2026-05-16T00:00:00Z",
        runId = "run-1",
        stepId = id,
        isPending = isPending,
        isReasoning = isReasoning,
    )
}
