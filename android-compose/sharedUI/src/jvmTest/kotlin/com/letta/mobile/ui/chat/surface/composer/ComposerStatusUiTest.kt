@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The prompt's attached status: the composer's error rides the prompt's top edge with a dismiss, the
 * nearly-full context warning its bottom edge (both on the Touch bar's top edge), and neither shows
 * when there is nothing to say.
 */
class ComposerStatusUiTest {
    private val composer = ChatComposerUiState(
        text = "",
        model = ChatModelUiState(currentHandle = null, currentLabel = "Model"),
    )

    private fun usage(used: Int) = ContextWindowUsageState(usage = ContextWindowUsage.total(usedTokens = used, windowTokens = WINDOW))

    private fun ComposeUiTest.showPanel(
        composer: ChatComposerUiState,
        actions: RecordingChatActions = RecordingChatActions(),
        style: LettaInput = LettaInput.Pointer,
        mode: ChatSurfaceMode = ChatSurfaceMode.FullScreen,
        width: Dp = 800.dp,
    ) = setContent {
        MaterialTheme {
            CompositionLocalProvider(LocalLettaHost provides LettaHost(input = style)) {
                Box(Modifier.width(width)) {
                    ChatComposerPanel(ComposerInputs(composer = composer, actions = actions, mode = mode))
                }
            }
        }
    }

    private fun ComposeUiTest.count(matcher: SemanticsMatcher): Int = onAllNodes(matcher).fetchSemanticsNodes().size

    private fun ComposeUiTest.waitForCount(matcher: SemanticsMatcher, expected: Int) =
        waitUntil(timeoutMillis = WAIT_MILLIS) { count(matcher) == expected }

    @Test
    fun theErrorRidesThePromptsTopEdgeAndDismisses() = runComposeUiTest {
        val actions = RecordingChatActions()
        showPanel(composer.copy(error = "Failed to send message."), actions)

        waitForCount(hasTestTag(ComposerTestTags.ERROR), 1)
        assertEquals(1, count(hasText("Failed to send message.") and hasAnyAncestor(hasTestTag(ComposerTestTags.ERROR))))
        val band = onNodeWithTag(ComposerTestTags.ERROR).getUnclippedBoundsInRoot()
        val card = onNodeWithTag(ComposerTestTags.CARD).getUnclippedBoundsInRoot()
        // The card sits in the band: the band starts above it and the card's bottom is the band's.
        assertTrue(band.top < card.top, "the error line is above the card")
        assertEquals(band.bottom, card.bottom)

        onNode(hasContentDescription("Dismiss") and hasAnyAncestor(hasTestTag(ComposerTestTags.ERROR))).performClick()
        assertEquals(1, actions.count("clearComposerError"))
    }

    @Test
    fun aNearlyFullContextWindowWarnsUnderThePrompt() = runComposeUiTest {
        showPanel(composer.copy(contextUsage = usage(used = 92_000)))

        waitForCount(hasTestTag(ComposerTestTags.CONTEXT_WARNING), 1)
        assertEquals(1, count(hasText("92%", substring = true) and hasAnyAncestor(hasTestTag(ComposerTestTags.CONTEXT_WARNING))))
        val band = onNodeWithTag(ComposerTestTags.CONTEXT_WARNING).getUnclippedBoundsInRoot()
        val card = onNodeWithTag(ComposerTestTags.CARD).getUnclippedBoundsInRoot()
        assertEquals(band.top, card.top)
        assertTrue(band.bottom > card.bottom, "the warning line is under the card")
        // A warning has nothing to dismiss: it goes when the window has room again.
        assertEquals(0, count(hasContentDescription("Dismiss") and hasAnyAncestor(hasTestTag(ComposerTestTags.CONTEXT_WARNING))))
    }

    @Test
    fun belowTheThresholdNothingIsAttached() = runComposeUiTest {
        showPanel(composer.copy(contextUsage = usage(used = 89_000)))

        waitForCount(hasTestTag(ComposerTestTags.CARD), 1)
        assertEquals(0, count(hasTestTag(ComposerTestTags.CONTEXT_WARNING)))
        assertEquals(0, count(hasTestTag(ComposerTestTags.ERROR)))
    }

    @Test
    fun theErrorAndTheWarningShowTogether() = runComposeUiTest {
        showPanel(composer.copy(error = "Could not attach that image.", contextUsage = usage(used = 95_000)))

        waitForCount(hasTestTag(ComposerTestTags.ERROR), 1)
        waitForCount(hasTestTag(ComposerTestTags.CONTEXT_WARNING), 1)
        val error = onNodeWithTag(ComposerTestTags.ERROR).getUnclippedBoundsInRoot()
        val warning = onNodeWithTag(ComposerTestTags.CONTEXT_WARNING).getUnclippedBoundsInRoot()
        assertTrue(error.top < warning.top && error.bottom == warning.bottom, "the warning band sits inside the error band")
    }

    @Test
    fun theDockCarriesTheErrorButNotTheWarning() = runComposeUiTest {
        showPanel(composer.copy(error = "Send blocked.", contextUsage = usage(used = 95_000)), mode = ChatSurfaceMode.Docked)

        waitForCount(hasTestTag(ComposerTestTags.ERROR), 1)
        assertEquals(0, count(hasTestTag(ComposerTestTags.CONTEXT_WARNING)))
    }

    @Test
    fun theTouchBarCarriesBothOnItsTopEdge() = runComposeUiTest {
        showPanel(composer.copy(error = "Send blocked.", contextUsage = usage(used = 95_000)), style = LettaInput.Touch, width = 360.dp)

        waitForCount(hasTestTag(ComposerTestTags.ERROR), 1)
        waitForCount(hasTestTag(ComposerTestTags.CONTEXT_WARNING), 1)
        val warning = onNodeWithTag(ComposerTestTags.CONTEXT_WARNING).getUnclippedBoundsInRoot()
        val bar = onNodeWithTag(ComposerTestTags.TOUCH_BAR).getUnclippedBoundsInRoot()
        assertTrue(warning.top < bar.top, "the warning line is above the bar")
        assertEquals(warning.bottom, bar.bottom)
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val WINDOW = 100_000
    }
}
