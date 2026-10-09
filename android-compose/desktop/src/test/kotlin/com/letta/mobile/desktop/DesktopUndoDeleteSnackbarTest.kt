@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.desktop.chat.DesktopDeletionUndo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-bzvro.31: the undo bar after a delete that did not destroy the chat. */
class DesktopUndoDeleteSnackbarTest {

    private fun offer(behavior: ConversationDeleteBehavior) = DesktopDeletionUndo.Offer(
        conversationId = "conv-1",
        wasArchived = false,
        behavior = behavior,
        backendGeneration = 1L,
    )

    @Test
    fun undoHandsBackTheDeletedConversation() = runComposeUiTest {
        val offer = offer(ConversationDeleteBehavior.RemovesFromLists)
        var undone: DesktopDeletionUndo.Offer? = null
        var expired: DesktopDeletionUndo.Offer? = null
        setContent {
            MaterialTheme {
                DesktopUndoDeleteSnackbar(offer, onUndo = { undone = it }, onExpire = { expired = it })
            }
        }
        val message = undoDeleteMessage(offer.behavior)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTextCount(message) > 0 }
        onNodeWithText(message).assertIsDisplayed()
        onNodeWithText(UNDO_DELETE_ACTION).performClick()
        waitForIdle()
        assertEquals(offer, undone)
        assertNull(expired)
    }

    @Test
    fun dismissingKeepsTheDeletion() = runComposeUiTest {
        val offer = offer(ConversationDeleteBehavior.RemovesFromLists)
        var undone: DesktopDeletionUndo.Offer? = null
        var expired: DesktopDeletionUndo.Offer? = null
        setContent {
            MaterialTheme {
                DesktopUndoDeleteSnackbar(offer, onUndo = { undone = it }, onExpire = { expired = it })
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTextCount(undoDeleteMessage(offer.behavior)) > 0 }
        onNodeWithContentDescription("Dismiss").performClick()
        waitForIdle()
        assertEquals(offer, expired)
        assertNull(undone)
    }

    @Test
    fun theBarSaysWhereTheChatWentAndNeverPromisesAnArchiveItDoesNotHave() {
        // MovesToArchived keeps it under the Archived filter; RemovesFromLists does not.
        assertEquals("Moved to Archived", undoDeleteMessage(ConversationDeleteBehavior.MovesToArchived))
        assertEquals("Removed from your chats", undoDeleteMessage(ConversationDeleteBehavior.RemovesFromLists))
    }

    @Test
    fun nothingPendingShowsNothing() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopUndoDeleteSnackbar(null, onUndo = {}, onExpire = {})
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodesWithTextCount(undoDeleteMessage(ConversationDeleteBehavior.RemovesFromLists)))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
