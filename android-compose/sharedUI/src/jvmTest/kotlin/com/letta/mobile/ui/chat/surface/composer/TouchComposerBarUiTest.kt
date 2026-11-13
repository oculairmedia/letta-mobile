@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.theme.TouchComposerDimens
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost

/** The Touch composer bar's parity with the legacy composer, and the permission mode's line under it. */
class TouchComposerBarUiTest {
    @Composable
    private fun TouchPanel(inputs: ComposerInputs) {
        MaterialTheme {
            CompositionLocalProvider(LocalLettaHost provides LettaHost(input = LettaInput.Touch)) {
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

    /** The permission mode is the quiet line under the bar's row, not a row of the "+" sheet. */
    @Test
    fun theLineUnderTheBarOffersThePermissionModeAndItsPicker() = runComposeUiTest {
        val actions = RecordingChatActions()
        val mode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted)
        setContent { TouchPanel(ComposerInputs(composer = ready().copy(permissionMode = mode), actions = actions)) }

        // The "+" is attach-only: the mode left its sheet.
        onNodeWithContentDescription("Attach image").assertExists()
        onNode(hasTestTag(ComposerTestTags.TOUCH_UNDER_BAR) and hasAnyDescendant(hasTestTag(ComposerTestTags.PERMISSION_MODE_LINE)))
            .assertExists()
        onNodeWithText("Approve all").assertExists()
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_LINE).performClick()
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_POPOVER).assertExists()
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_OPTION + AppServerPermissionMode.Standard.name).performClick()

        runOnIdle { assertEquals(listOf(AppServerPermissionMode.Standard), actions.permissionModes) }
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_POPOVER).assertDoesNotExist()
    }

    @Test
    fun aLockedModeLineStaysGreyedWithItsReasonAboveTheBar() = runComposeUiTest {
        val locked = ChatPermissionModeUiState(
            selected = AppServerPermissionMode.Standard,
            unavailableReason = "Not supported over Iroh yet",
        )
        setContent { TouchPanel(ComposerInputs(composer = ready().copy(permissionMode = locked), actions = RecordingChatActions())) }

        onNodeWithText("Not supported over Iroh yet").assertExists()
        onNodeWithText("Ask for approval").assertExists()
        onNode(hasTestTag(ComposerTestTags.PERMISSION_MODE_LINE) and hasText("Ask for approval"))
            .assertIsNotEnabled()
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_LINE).performClick()
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_POPOVER).assertDoesNotExist()
    }

    @Test
    fun theCanvasDockLeavesThePermissionModeOutOfThePlus() = runComposeUiTest {
        val mode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted)
        setContent {
            TouchPanel(
                ComposerInputs(
                    composer = ready().copy(permissionMode = mode),
                    actions = RecordingChatActions(),
                    mode = ChatSurfaceMode.Docked,
                ),
            )
        }
        // The dock stays one compact bar: no line under it, and the "+" is attach-only.
        onNodeWithContentDescription("Attach image").assertExists()
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_LINE).assertDoesNotExist()
        onNodeWithTag(ComposerTestTags.TOUCH_UNDER_BAR).assertDoesNotExist()
    }

    /** The line shows on the full page with the keyboard down; it steps aside with the keyboard up, and is never docked. */
    @Test
    fun theUnderBarLineStepsAsideForTheKeyboardAndTheDock() {
        assertTrue(touchUnderBarShown(docked = false, keyboardOpen = false))
        assertFalse(touchUnderBarShown(docked = false, keyboardOpen = true))
        assertFalse(touchUnderBarShown(docked = true, keyboardOpen = false))
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
