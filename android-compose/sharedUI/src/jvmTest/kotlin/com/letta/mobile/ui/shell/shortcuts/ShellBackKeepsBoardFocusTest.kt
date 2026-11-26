@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.letta.mobile.ui.shell.shortcuts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.ui.canvas.CanvasWorkspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The system Back under the shell's shortcut root must not cost the canvas board its keyboard
 * focus: the board takes focus so its keys (Delete, Esc, undo) reach it on a window with a hardware
 * keyboard. A Back that reaches the host leaves focus where it was, and the board's next key still
 * acts on the board.
 */
class ShellBackKeepsBoardFocusTest {
    private fun ComposeUiTest.showBoardUnderTheShortcutRoot(): CanvasSession {
        val session = runBlocking {
            CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "Board", initialSceneJson = ""))
        }
        setContent {
            ProvideShortcutHub {
                val root = rememberShellShortcuts(ShellShortcutBinding(ShortcutPlatform.Android, WorkspaceShortcuts) {})
                Box(Modifier.fillMaxSize().then(root)) {
                    CanvasWorkspace(session = session, showTitle = false)
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasContentDescription(BOARD)).fetchSemanticsNodes().isNotEmpty() }
        return session
    }

    @Test
    fun theBoardKeepsItsKeysAfterABack() = runComposeUiTest {
        val session = showBoardUnderTheShortcutRoot()
        // A note is the board's active item; Delete on the focused board removes it.
        onNodeWithContentDescription("Add note").performClick()
        waitUntil(timeoutMillis = 5_000) { session.documents().size == 1 }
        val board = onNodeWithContentDescription(BOARD)
        board.requestFocus()
        waitForIdle()

        board.performKeyPress(KeyEvent(Key.Back, KeyEventType.KeyDown))
        board.performKeyPress(KeyEvent(Key.Back, KeyEventType.KeyUp))
        waitForIdle()

        board.assertIsFocused()
        board.performKeyInput { pressKey(Key.Delete) }
        waitUntil(timeoutMillis = 5_000) { session.documents().isEmpty() }
        assertEquals(0, session.documents().size)
    }

    private companion object {
        const val BOARD = "Canvas workspace"
    }
}
