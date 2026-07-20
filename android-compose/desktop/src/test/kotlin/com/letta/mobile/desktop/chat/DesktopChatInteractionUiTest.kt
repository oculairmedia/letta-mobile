@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopChatInteractionUiTest {
    @Test
    fun assistantMarkdownCanShrinkAfterStreamReconciliation() = runComposeUiTest {
        val longerText = "x".repeat(91)
        val shorterText = "x".repeat(87)
        var markdown by mutableStateOf("**$longerText**")
        setContent {
            MaterialTheme {
                AgentText(text = markdown, isError = false)
            }
        }

        onNodeWithText(longerText).assertExists()
        runOnIdle { markdown = "**$shorterText**" }
        onNodeWithText(shorterText).assertExists()
    }

    @Test
    fun assistantCopyActionIsFocusableDiscoverableAndUsablySized() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopMessageBubble(
                    UiMessage(
                        id = "assistant-1",
                        role = "assistant",
                        content = "Selectable response",
                        timestamp = "2026-07-19T12:00:00Z",
                    ),
                )
            }
        }

        val copy = onNodeWithContentDescription("Copy response")
        copy.assertExists().assertHasClickAction()
        copy.performSemanticsAction(SemanticsActions.RequestFocus)
        copy.assertIsFocused()
        val bounds = copy.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= with(density) { 36.dp.toPx() })
        assertTrue(bounds.height >= with(density) { 36.dp.toPx() })
    }

    @Test
    fun manualToolDisclosureSurvivesRunningToCompletedRefresh() = runComposeUiTest {
        var tool by mutableStateOf(toolCall(status = "running", result = null))
        setContent {
            MaterialTheme {
                ToolCard(toolCall = tool, disclosureKey = "call-1")
            }
        }

        onNodeWithTag("tool-card-body").assertExists()
        onNodeWithTag("tool-card-toggle").performClick()
        onNodeWithTag("tool-card-body").assertDoesNotExist()
        runOnIdle { tool = tool.copy(status = "completed", result = "done") }
        onNodeWithTag("tool-card-body").assertDoesNotExist()
    }

    @Test
    fun completedToolsStartCollapsedAndFailuresStartExpanded() = runComposeUiTest {
        var tool by mutableStateOf(toolCall(status = "completed", result = "done"))
        setContent {
            MaterialTheme { ToolCard(toolCall = tool, disclosureKey = tool.status.orEmpty()) }
        }

        onNodeWithTag("tool-card-body").assertDoesNotExist()
        onNodeWithTag("tool-failure-badge", useUnmergedTree = true).assertDoesNotExist()
        runOnIdle { tool = toolCall(status = "failed", result = "boom") }
        onNodeWithTag("tool-card-body").assertExists()
        onNodeWithTag("tool-failure-badge", useUnmergedTree = true).assertExists()
    }

    @Test
    fun externalComposerResetMovesCaretToEnd() = runComposeUiTest {
        var text by mutableStateOf("draft")
        setContent {
            MaterialTheme {
                ComposerInputSurface(
                    ComposerInputSurfaceParams(
                        state = composerState(text),
                        actions = composerActions(onTextChanged = { text = it }),
                        canSend = text.isNotBlank(),
                        matchedCommands = emptyList(),
                    ),
                )
            }
        }

        runOnIdle { text = "externally reset" }
        val input = onNodeWithTag("composer-input")
        input.assertTextEquals("externally reset")
        input.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.TextSelectionRange,
                TextRange("externally reset".length),
            ),
        )
    }

    @Test
    fun narrowComposerKeepsEveryControlAndSendReachable() = runComposeUiTest {
        var sends = 0
        setContent {
            MaterialTheme {
                Box(Modifier.width(420.dp)) {
                    ComposerInputSurface(
                        ComposerInputSurfaceParams(
                            state = composerState("ready"),
                            actions = composerActions(onSend = { sends++ }),
                            canSend = true,
                            matchedCommands = emptyList(),
                        ),
                    )
                }
            }
        }

        onNodeWithContentDescription("Attach").assertExists()
        onNodeWithText("Model").assertExists()
        onNodeWithText("Unrestricted").assertExists()
        onNodeWithText("Medium").assertExists()
        onNodeWithTag("composer-controls-secondary").assertExists()
        onNodeWithTag("composer-send").assertExists().assertIsEnabled().performClick()
        runOnIdle { assertEquals(1, sends) }
    }

    @Test
    fun enterShortcutsCommandsAndDisabledSendFollowPolicy() {
        var sends = 0
        var commandRuns = 0
        var cleared = "unchanged"
        fun handled(
            shift: Boolean = false,
            ctrl: Boolean = false,
            canSend: Boolean = true,
            commands: List<ComposerCommand> = emptyList(),
        ) = composerEnterKeyHandled(
            ComposerEnterKeyParams(
                eventKey = Key.Enter,
                eventType = KeyEventType.KeyDown,
                shiftPressed = shift,
                ctrlPressed = ctrl,
                matchedCommands = commands,
                canSend = canSend,
                onTextChanged = { cleared = it },
                onSend = { sends++ },
            ),
        )

        assertTrue(handled())
        assertTrue(handled(ctrl = true))
        assertFalse(handled(shift = true))
        assertFalse(handled(canSend = false))
        assertTrue(
            handled(
                canSend = false,
                commands = listOf(ComposerCommand("new", "New chat") { commandRuns++ }),
            ),
        )
        assertEquals(2, sends)
        assertEquals(1, commandRuns)
        assertEquals("", cleared)
    }

    @Test
    fun composerStateBridgePreservesImeCompositionUntilExternalTextActuallyChanges() {
        val composing = TextFieldValue(
            text = "é",
            selection = TextRange(1),
            composition = TextRange(0, 1),
        )

        assertEquals(composing, reconcileComposerFieldValue(composing, "é"))
        assertEquals(
            TextFieldValue(text = "reset", selection = TextRange(5)),
            reconcileComposerFieldValue(composing, "reset"),
        )
    }

    @Test
    fun completedImageToolsStartExpanded() = runComposeUiTest {
        val tool = UiToolCall(
            name = "generate_image",
            arguments = "{}",
            result = "ok",
            status = "success",
            toolCallId = "img-1",
            generatedImageAttachments = listOf(
                com.letta.mobile.data.model.UiImageAttachment(
                    base64 = "aaaa",
                    mediaType = "image/png",
                ),
            ),
        )
        assertTrue(tool.shouldInitiallyExpand())
        setContent {
            MaterialTheme { ToolCard(toolCall = tool, disclosureKey = "img-1") }
        }
        onNodeWithTag("tool-card-body").assertExists()
    }

    @Test
    fun truncatedToolOutputExplainsCopyScopeAndSupportsKeyboardScrolling() = runComposeUiTest {
        val output = (1..41).joinToString("\n") { index ->
            "line $index ${"x".repeat(120)}"
        }
        setContent {
            MaterialTheme { ToolOutputBlock(output) }
        }

        onNodeWithText("Showing 40 of 41 lines · Copy includes all output").assertExists()
        val scrollable = onNodeWithContentDescription(
            "Tool output. Use left and right arrow keys to scroll horizontally.",
        )
        scrollable.performSemanticsAction(SemanticsActions.RequestFocus)
        scrollable.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        val scrollbarBounds = onNodeWithTag("tool-output-scrollbar").fetchSemanticsNode().boundsInRoot
        assertTrue(scrollbarBounds.height >= with(density) { 10.dp.toPx() })
    }

    private fun toolCall(status: String, result: String?) = UiToolCall(
        name = "shell",
        arguments = "echo hello",
        result = result,
        status = status,
        toolCallId = "call-1",
    )

    private fun composerState(text: String) = ComposerBarState(
        text = text,
        pendingImageAttachments = emptyList(),
        enabled = true,
        modelLabel = "Model",
        modelOptions = emptyList(),
        commands = emptyList(),
        mentionables = emptyList(),
        placeholder = "Message",
    )

    private fun composerActions(
        onTextChanged: (String) -> Unit = {},
        onSend: () -> Unit = {},
    ) = ComposerBarActions(
        onModelSelected = {},
        onTextChanged = onTextChanged,
        onSend = onSend,
        onAttachImage = {},
        onRemoveImageAttachment = {},
    )
}
