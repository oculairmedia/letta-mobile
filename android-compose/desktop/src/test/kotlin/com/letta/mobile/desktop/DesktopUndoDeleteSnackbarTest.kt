@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-bzvro.31: the undo bar after a delete that only archived the chat. */
class DesktopUndoDeleteSnackbarTest {

    @Test
    fun undoHandsBackTheArchivedConversation() = runComposeUiTest {
        var undone: String? = null
        var expired: String? = null
        setContent {
            MaterialTheme {
                DesktopUndoDeleteSnackbar("conv-1", onUndo = { undone = it }, onExpire = { expired = it })
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTextCount(UNDO_DELETE_MESSAGE) > 0 }
        onNodeWithText(UNDO_DELETE_MESSAGE).assertIsDisplayed()
        onNodeWithText(UNDO_DELETE_ACTION).performClick()
        waitForIdle()
        assertEquals("conv-1", undone)
        assertNull(expired)
    }

    @Test
    fun dismissingKeepsTheDeletion() = runComposeUiTest {
        var undone: String? = null
        var expired: String? = null
        setContent {
            MaterialTheme {
                DesktopUndoDeleteSnackbar("conv-1", onUndo = { undone = it }, onExpire = { expired = it })
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTextCount(UNDO_DELETE_MESSAGE) > 0 }
        onNodeWithContentDescription("Dismiss").performClick()
        waitForIdle()
        assertEquals("conv-1", expired)
        assertNull(undone)
    }

    @Test
    fun nothingPendingShowsNothing() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopUndoDeleteSnackbar(null, onUndo = {}, onExpire = {})
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodesWithTextCount(UNDO_DELETE_MESSAGE))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
