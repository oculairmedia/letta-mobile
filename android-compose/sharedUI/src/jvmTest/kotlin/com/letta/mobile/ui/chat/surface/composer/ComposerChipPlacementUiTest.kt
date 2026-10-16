@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.chat.surface.LocalChatPlatformStyle
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Where the permission-mode and context chips sit: the mode chip in the card's control row beside
 * the model and effort chips, the context chip at the right end of the keyboard-hint footer (or in
 * the control row where there is no footer), and the Touch bar's mode chip above the bar.
 */
class ComposerChipPlacementUiTest {
    private val composer = ChatComposerUiState(
        text = "",
        model = ChatModelUiState(currentHandle = null, currentLabel = "Model"),
        permissionMode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted),
        contextUsage = ContextWindowUsageState(usage = ContextWindowUsage.total(usedTokens = 36_000, windowTokens = 100_000)),
    )

    private fun ComposeUiTest.showPanel(
        width: Dp = 800.dp,
        keyboardHints: Boolean = true,
        style: ChatPlatformStyle = ChatPlatformStyle.Pointer,
        composer: ChatComposerUiState = this@ComposerChipPlacementUiTest.composer,
    ) = setContent {
        MaterialTheme {
            CompositionLocalProvider(LocalChatPlatformStyle provides style) {
                Box(Modifier.width(width)) {
                    ChatComposerPanel(
                        ComposerInputs(
                            composer = composer,
                            actions = RecordingChatActions(),
                            platform = ChatSurfacePlatform(showKeyboardHints = keyboardHints),
                        ),
                    )
                }
            }
        }
    }

    private fun chipIn(chip: String, container: String): SemanticsMatcher =
        hasTestTag(chip) and hasAnyAncestor(hasTestTag(container))

    private fun ComposeUiTest.count(matcher: SemanticsMatcher): Int = onAllNodes(matcher).fetchSemanticsNodes().size

    private fun ComposeUiTest.waitUntilExactlyOneExists(matcher: SemanticsMatcher) =
        waitUntil(timeoutMillis = WAIT_MILLIS) { count(matcher) == 1 }

    private fun ComposeUiTest.waitUntilDoesNotExist(matcher: SemanticsMatcher) =
        waitUntil(timeoutMillis = WAIT_MILLIS) { count(matcher) == 0 }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }

    @Test
    fun theModeChipSitsInTheControlRowAndTheContextChipEndsTheHintFooter() = runComposeUiTest {
        showPanel()

        waitUntilExactlyOneExists(chipIn(ComposerTestTags.PERMISSION_MODE_CHIP, ComposerTestTags.CONTROLS))
        waitUntilExactlyOneExists(chipIn(ComposerTestTags.CONTEXT_CHIP, ComposerTestTags.HINT_FOOTER))
        waitUntilDoesNotExist(chipIn(ComposerTestTags.CONTEXT_CHIP, ComposerTestTags.CONTROLS))

        // The mode chip follows the model chip on the same line.
        val model = onNodeWithTag(ComposerTestTags.MODEL_CHIP).getUnclippedBoundsInRoot()
        val mode = onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).getUnclippedBoundsInRoot()
        assertTrue(mode.left >= model.right, "mode chip at ${mode.left} is not after the model chip ending ${model.right}")
        assertTrue(mode.top < model.bottom && model.top < mode.bottom, "mode chip $mode is not on the model chip's line $model")

        // The context chip is on the hint's line, right-aligned in the footer.
        val footer = onNodeWithTag(ComposerTestTags.HINT_FOOTER).getUnclippedBoundsInRoot()
        val hint = onNodeWithTag(ComposerTestTags.HINT, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val context = onNodeWithTag(ComposerTestTags.CONTEXT_CHIP).getUnclippedBoundsInRoot()
        assertTrue(abs((footer.right - context.right).value) < 1f, "context chip ends at ${context.right}, footer at ${footer.right}")
        assertTrue(context.left >= hint.right, "context chip at ${context.left} overlaps the hint ending ${hint.right}")
        assertTrue(context.top < hint.bottom && hint.top < context.bottom, "context chip $context is not on the hint's line $hint")
    }

    @Test
    fun bothChipsKeepTheirPopoversInTheirNewPlaces() = runComposeUiTest {
        showPanel()

        waitUntilExactlyOneExists(hasTestTag(ComposerTestTags.PERMISSION_MODE_CHIP))
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).performClick()
        waitUntilExactlyOneExists(hasTestTag(ComposerTestTags.PERMISSION_MODE_POPOVER))
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_OPTION + AppServerPermissionMode.Standard.name).performClick()
        waitUntilDoesNotExist(hasTestTag(ComposerTestTags.PERMISSION_MODE_POPOVER))

        onNodeWithTag(ComposerTestTags.CONTEXT_CHIP).performClick()
        waitUntilExactlyOneExists(hasTestTag(ComposerTestTags.CONTEXT_POPOVER))
    }

    @Test
    fun theContextIndicatorIsAPlainReadingNotADropdown() = runComposeUiTest {
        showPanel()

        waitUntilExactlyOneExists(chipIn(ComposerTestTags.CONTEXT_CHIP, ComposerTestTags.HINT_FOOTER))
        // A picker chip still ends with its chevron (so the tag is real)...
        waitUntil(timeoutMillis = WAIT_MILLIS) { countUnmerged(chevronIn(ComposerTestTags.MODEL_CHIP)) == 1 }
        // ...the context indicator has none, and nothing announces it as a dropdown.
        assertEquals(0, countUnmerged(chevronIn(ComposerTestTags.CONTEXT_CHIP)))
        val indicator = onNodeWithTag(ComposerTestTags.CONTEXT_CHIP).fetchSemanticsNode()
        assertNotEquals(Role.DropdownList, indicator.config.getOrNull(SemanticsProperties.Role))
        onNodeWithTag(ComposerTestTags.CONTEXT_CHIP).assertTextContains("36", substring = true)

        // Its breakdown is still a click away.
        onNodeWithTag(ComposerTestTags.CONTEXT_CHIP).performClick()
        waitUntilExactlyOneExists(hasTestTag(ComposerTestTags.CONTEXT_POPOVER))
    }

    private fun chevronIn(chip: String): SemanticsMatcher =
        hasTestTag(ComposerTestTags.CHIP_CHEVRON) and hasAnyAncestor(hasTestTag(chip))

    private fun ComposeUiTest.countUnmerged(matcher: SemanticsMatcher): Int =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun theModeNoteStaysAboveTheCard() = runComposeUiTest {
        val locked = composer.permissionMode!!.copy(unavailableReason = "Not supported over Iroh yet")
        showPanel(composer = composer.copy(permissionMode = locked))

        waitUntilExactlyOneExists(hasTestTag(ComposerTestTags.PERMISSION_MODE_NOTE))
        waitUntilDoesNotExist(chipIn(ComposerTestTags.PERMISSION_MODE_NOTE, ComposerTestTags.CARD))
        val note = onNodeWithTag(ComposerTestTags.PERMISSION_MODE_NOTE).getUnclippedBoundsInRoot()
        val card = onNodeWithTag(ComposerTestTags.CARD).getUnclippedBoundsInRoot()
        assertTrue(note.bottom <= card.top, "note $note is not above the card $card")
    }

    @Test
    fun withoutAHintFooterTheContextChipStaysInTheControlRow() = runComposeUiTest {
        showPanel(keyboardHints = false)

        waitUntilExactlyOneExists(chipIn(ComposerTestTags.CONTEXT_CHIP, ComposerTestTags.CONTROLS))
        waitUntilExactlyOneExists(chipIn(ComposerTestTags.PERMISSION_MODE_CHIP, ComposerTestTags.CONTROLS))
        waitUntilDoesNotExist(hasTestTag(ComposerTestTags.HINT_FOOTER))
    }

    @Test
    fun aNarrowCardWrapsTheChipsAndKeepsEverythingInside() = runComposeUiTest {
        showPanel(width = 360.dp, keyboardHints = false)

        waitUntilExactlyOneExists(chipIn(ComposerTestTags.PERMISSION_MODE_CHIP, ComposerTestTags.CONTROLS))
        waitUntilExactlyOneExists(chipIn(ComposerTestTags.CONTEXT_CHIP, ComposerTestTags.CONTROLS))
        val card = onNodeWithTag(ComposerTestTags.CARD).getUnclippedBoundsInRoot()
        listOf(ComposerTestTags.SEND, ComposerTestTags.MODEL_CHIP, ComposerTestTags.PERMISSION_MODE_CHIP, ComposerTestTags.CONTEXT_CHIP)
            .forEach { tag ->
                val bounds = onNodeWithTag(tag).getUnclippedBoundsInRoot()
                assertTrue(bounds.left >= card.left && bounds.right <= card.right, "$tag $bounds overflows the card $card")
            }
        // The mode chip wraps below the primary row.
        val send = onNodeWithTag(ComposerTestTags.SEND).getUnclippedBoundsInRoot()
        val mode = onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).getUnclippedBoundsInRoot()
        assertTrue(mode.top >= send.bottom, "mode chip $mode did not wrap below Send $send")
    }

    @Test
    fun theTouchBarKeepsTheModeChipAboveIt() = runComposeUiTest {
        showPanel(width = 360.dp, keyboardHints = false, style = ChatPlatformStyle.Touch)

        waitUntilExactlyOneExists(hasTestTag(ComposerTestTags.PERMISSION_MODE_CHIP))
        waitUntilDoesNotExist(chipIn(ComposerTestTags.PERMISSION_MODE_CHIP, ComposerTestTags.TOUCH_BAR))
        val bar = onNodeWithTag(ComposerTestTags.TOUCH_BAR).getUnclippedBoundsInRoot()
        val mode = onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).getUnclippedBoundsInRoot()
        assertTrue(mode.bottom <= bar.top, "mode chip $mode is not above the Touch bar $bar")
    }
}
