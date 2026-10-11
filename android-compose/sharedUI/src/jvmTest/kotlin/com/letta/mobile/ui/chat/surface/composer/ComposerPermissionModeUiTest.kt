@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-bzvro.13 (F13): the permission-mode chip above the composer. */
class ComposerPermissionModeUiTest {
    private val unrestricted = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted)

    private fun ComposeUiTest.showPanel(
        state: ChatPermissionModeUiState?,
        actions: RecordingChatActions = RecordingChatActions(),
        mode: ChatSurfaceMode = ChatSurfaceMode.FullScreen,
    ) = setContent {
        MaterialTheme {
            ChatComposerPanel(
                ComposerInputs(
                    composer = ChatComposerUiState(text = "ready", canSend = true, permissionMode = state),
                    actions = actions,
                    mode = mode,
                ),
            )
        }
    }

    @Test
    fun theChipShowsTheConfirmedModeAndOpensThePicker() = runComposeUiTest {
        val actions = RecordingChatActions()
        showPanel(unrestricted, actions)

        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).assertIsEnabled()
        onNodeWithText("Approve all").assertExists()
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_POPOVER).assertDoesNotExist()

        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).performClick()
        AppServerPermissionMode.entries.forEach { mode ->
            onNodeWithTag(ComposerTestTags.PERMISSION_MODE_OPTION + mode.name).assertExists()
        }

        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_OPTION + AppServerPermissionMode.Standard.name).performClick()
        runOnIdle { assertEquals(listOf(AppServerPermissionMode.Standard), actions.permissionModes) }
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_POPOVER).assertDoesNotExist()
    }

    @Test
    fun aRequestedChangeShowsPendingUntilTheServerEchoesIt() = runComposeUiTest {
        showPanel(unrestricted.copy(pending = AppServerPermissionMode.Standard))

        onNodeWithText("Changing to Ask for approval…").assertExists()
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).assertIsNotEnabled()
    }

    @Test
    fun whereTheModeCannotBeChangedTheChipIsLockedWithItsReason() = runComposeUiTest {
        showPanel(unrestricted.copy(unavailableReason = "Not supported over Iroh yet"))

        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).assertIsNotEnabled()
        onNodeWithText("Not supported over Iroh yet").assertExists()
    }

    @Test
    fun anUnconfirmedChangeSaysSo() = runComposeUiTest {
        showPanel(unrestricted.copy(selected = AppServerPermissionMode.Strict, unconfirmed = AppServerPermissionMode.Unrestricted))

        // The chip names the requested mode as unconfirmed; it does not claim the old one.
        onNodeWithText("Approve all (unconfirmed)").assertExists()
        onNodeWithText("The server did not confirm the change").assertExists()
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).assertIsEnabled()
    }

    @Test
    fun aChoiceForAnUnstartedConversationSaysWhenItApplies() = runComposeUiTest {
        showPanel(unrestricted.copy(selected = AppServerPermissionMode.Strict, appliesOnStart = true))

        onNodeWithText("Applies when the conversation starts").assertExists()
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).assertIsEnabled()
    }

    @Test
    fun aChoiceThatCouldNotBeSavedSaysSo() = runComposeUiTest {
        showPanel(unrestricted.copy(selected = AppServerPermissionMode.Strict, notSaved = true))

        onNodeWithText("Could not be saved; applies to this session only").assertExists()
    }

    @Test
    fun noChipForAnOwnerWithoutAMode() = runComposeUiTest {
        showPanel(null)

        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).assertDoesNotExist()
    }

    @Test
    fun theCompactDockStaysOneBarWithoutTheChip() = runComposeUiTest {
        showPanel(unrestricted, mode = ChatSurfaceMode.Docked)

        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).assertDoesNotExist()
    }

    /** letta-mobile-bzvro.40 (G48): Shift+Tab at the prompt moves to the next mode, as Letta Desktop does. */
    @Test
    fun shiftTabCyclesToTheNextMode() = runComposeUiTest {
        val actions = RecordingChatActions()
        showPanel(ChatPermissionModeUiState(selected = AppServerPermissionMode.Standard), actions)

        onNodeWithTag(ComposerTestTags.INPUT).requestFocus().performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) }
        }

        runOnIdle { assertEquals(listOf(AppServerPermissionMode.AcceptEdits), actions.permissionModes) }
    }

    @Test
    fun shiftTabDoesNothingWhileAChangeIsPending() = runComposeUiTest {
        val actions = RecordingChatActions()
        val pending = ChatPermissionModeUiState(selected = AppServerPermissionMode.Standard, pending = AppServerPermissionMode.Strict)
        showPanel(pending, actions)

        onNodeWithTag(ComposerTestTags.INPUT).requestFocus().performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) }
        }

        runOnIdle { assertEquals(emptyList(), actions.permissionModes) }
    }

    @Test
    fun theCycleWrapsAndFollowsTheOptions() {
        val options = AppServerPermissionMode.entries
        val last = ChatPermissionModeUiState(selected = options.last())

        assertEquals(options.first(), nextPermissionMode(last))
        assertEquals(null, nextPermissionMode(last.copy(unavailableReason = "locked")))
        assertEquals(null, nextPermissionMode(null))
    }
}
