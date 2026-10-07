@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.LocalChatPlatformStyle
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.theme.TouchComposerDimens
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-bglj6.1.9 / bglj6.1.23: the Touch composer bar's parity with the legacy composer. */
class TouchComposerBarUiTest {
    @Composable
    private fun TouchPanel(inputs: ComposerInputs) {
        MaterialTheme {
            CompositionLocalProvider(LocalChatPlatformStyle provides ChatPlatformStyle.Touch) {
                ChatComposerPanel(inputs)
            }
        }
    }

    private fun ready(text: String = "ready") = ChatComposerUiState(text = text, canSend = true)

    @Test
    fun plusAnnouncesAttachImageWhenThatIsAllItDoes() = runComposeUiTest {
        setContent { TouchPanel(ComposerInputs(composer = ready(), actions = RecordingChatActions())) }
        onNodeWithContentDescription("Attach image").assertExists()
        assertEquals(0, onAllNodesWithContentDescriptionCount("Add to message"))
    }

    @Test
    fun plusAnnouncesTheSheetWhenItOffersMoreThanOneThing() = runComposeUiTest {
        setContent {
            TouchPanel(
                ComposerInputs(
                    composer = ready(),
                    actions = RecordingChatActions(),
                    host = ChatSurfaceHost(openCanvas = {}),
                ),
            )
        }
        onNodeWithContentDescription("Add to message").assertExists()
    }

    @Test
    fun theFieldIsGreyedWhileTheOwnerTakesNoInput() = runComposeUiTest {
        setContent {
            TouchPanel(ComposerInputs(composer = ready(text = "").copy(canSend = false, acceptsInput = false), actions = RecordingChatActions()))
        }
        onNodeWithTag(ComposerTestTags.INPUT).assertIsNotEnabled()
    }

    @Test
    fun theFieldTakesInputByDefault() = runComposeUiTest {
        setContent { TouchPanel(ComposerInputs(composer = ready(text = ""), actions = RecordingChatActions())) }
        onNodeWithTag(ComposerTestTags.INPUT).assertIsEnabled()
    }

    @Test
    fun slashSuggestionsAreAChipStripThatRunsAndUninstalls() = runComposeUiTest {
        val action = ChatComposerCommand(id = "new", label = "/new", description = "Start a new conversation")
        val skill = ChatComposerCommand(id = "/review", label = "/review", fillsComposer = true, removable = true)
        val actions = RecordingChatActions()
        setContent {
            TouchPanel(ComposerInputs(composer = ready("/").copy(commands = persistentListOf(action, skill)), actions = actions))
        }
        // Chips, not the pointer card's rows: no description text and no overflow buttons.
        onNodeWithTag(ComposerTestTags.COMMAND_SUGGESTIONS).assertExists()
        assertEquals(0, onAllNodesWithContentDescriptionCount("More options for /review"))
        onNodeWithText("Start a new conversation").assertDoesNotExist()

        onNodeWithTag(ComposerTestTags.COMMAND_ROW + "/review").performTouchInput { longClick() }
        onNodeWithText("Uninstall /review").performClick()
        runOnIdle { assertEquals(listOf(skill), actions.uninstalled) }

        onNodeWithTag(ComposerTestTags.COMMAND_ROW + "new").performClick()
        runOnIdle { assertTrue(actions.commandsRun.contains(action)) }
    }

    @Test
    fun theBarsPaddingNeverGrowsByTheNavigationInset() {
        // Flush to the screen edge (legacy bottomInsetDp = 0): resting padding with the keyboard down.
        assertEquals(TouchComposerDimens.restingVerticalPadding, touchBarVerticalPadding(imePx = 0))
        assertEquals(TouchComposerDimens.compactVerticalPadding, touchBarVerticalPadding(imePx = 400))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithContentDescriptionCount(label: String): Int =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(label)).fetchSemanticsNodes().size
}
