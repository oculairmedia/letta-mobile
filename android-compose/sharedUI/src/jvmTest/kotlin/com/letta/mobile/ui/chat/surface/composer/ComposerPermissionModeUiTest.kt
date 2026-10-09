@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    private fun composer(state: ChatPermissionModeUiState?) =
        ChatComposerUiState(text = "ready", canSend = true, permissionMode = state)

    @Test
    fun theChipShowsTheConfirmedModeAndOpensThePicker() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent {
            MaterialTheme {
                ChatComposerPanel(
                    ComposerInputs(
                        composer = composer(ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted)),
                        actions = actions,
                    ),
                )
            }
        }

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
        setContent {
            MaterialTheme {
                ChatComposerPanel(
                    ComposerInputs(
                        composer = composer(
                            ChatPermissionModeUiState(
                                selected = AppServerPermissionMode.Unrestricted,
                                pending = AppServerPermissionMode.Standard,
                            ),
                        ),
                        actions = RecordingChatActions(),
                    ),
                )
            }
        }

        onNodeWithText("Changing to Ask for approval…").assertExists()
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).assertIsNotEnabled()
    }

    @Test
    fun whereTheModeCannotBeChangedTheChipIsLockedWithItsReason() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ChatComposerPanel(
                    ComposerInputs(
                        composer = composer(
                            ChatPermissionModeUiState(
                                selected = AppServerPermissionMode.Unrestricted,
                                unavailableReason = "Not supported over Iroh yet",
                            ),
                        ),
                        actions = RecordingChatActions(),
                    ),
                )
            }
        }

        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).assertIsNotEnabled()
        onNodeWithText("Not supported over Iroh yet").assertExists()
    }

    @Test
    fun anUnconfirmedChangeSaysSo() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ChatComposerPanel(
                    ComposerInputs(
                        composer = composer(ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted, failed = true)),
                        actions = RecordingChatActions(),
                    ),
                )
            }
        }

        onNodeWithText("The server did not confirm the change").assertExists()
        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).assertIsEnabled()
    }

    @Test
    fun noChipForAnOwnerWithoutAMode() = runComposeUiTest {
        setContent { MaterialTheme { ChatComposerPanel(ComposerInputs(composer = composer(null), actions = RecordingChatActions())) } }

        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).assertDoesNotExist()
    }

    @Test
    fun theCompactDockStaysOneBarWithoutTheChip() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ChatComposerPanel(
                    ComposerInputs(
                        composer = composer(ChatPermissionModeUiState(AppServerPermissionMode.Unrestricted)),
                        actions = RecordingChatActions(),
                        mode = ChatSurfaceMode.Docked,
                    ),
                )
            }
        }

        onNodeWithTag(ComposerTestTags.PERMISSION_MODE_CHIP).assertDoesNotExist()
    }
}
