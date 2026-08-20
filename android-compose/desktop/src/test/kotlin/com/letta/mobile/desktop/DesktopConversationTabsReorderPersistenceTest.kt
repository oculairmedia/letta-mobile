@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.center
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.desktopshell.ConversationTabsReducer
import com.letta.mobile.data.desktopshell.ConversationTabsState
import kotlin.test.Test
import kotlin.test.assertTrue

// Regression coverage for letta-mobile#1249's second finding: PR #1247's own
// UI tests only asserted that onReorder *fired* with the right target index,
// never that the row went on to actually render in the new order — so a bug
// where the callback is right but the committed order never sticks (or
// briefly renders, then visually snaps back to where the drag started) went
// uncaught. This reproduces the real production wiring pattern (a
// reducer-backed state var feeding `tabs` back into
// DesktopConversationTabRow, exactly like
// LettaDesktopApp.reorderConversationTab / conversationTabsState) and
// asserts on rendered tab *position* after the drop, not just the callback.
class DesktopConversationTabsReorderPersistenceTest {
    @Test
    fun committedReorderIsReflectedInRenderedOrder() = runComposeUiTest {
        val idToTab = mapOf(
            "conversation-1" to DesktopConversationTab("conversation-1", "First", "Ada"),
            "conversation-2" to DesktopConversationTab("conversation-2", "Second", "Grace"),
            "conversation-3" to DesktopConversationTab("conversation-3", "Third", "Alan"),
        )
        setContent {
            var state by remember {
                mutableStateOf(ConversationTabsState(idToTab.keys.toList()))
            }
            val tabs = state.openConversationIds.mapNotNull { idToTab[it] }
            DesktopMaterialTheme {
                DesktopConversationTabRow(
                    tabs = tabs,
                    activeConversationId = "conversation-1",
                    onSelect = {},
                    onClose = {},
                    onReorder = { conversationId, targetIndex ->
                        state = ConversationTabsReducer.reorder(state, conversationId, targetIndex)
                    },
                )
            }
        }

        val firstLeftBefore = onNodeWithText("First").fetchSemanticsNode().boundsInRoot.left
        val secondLeftBefore = onNodeWithText("Second").fetchSemanticsNode().boundsInRoot.left
        assertTrue(firstLeftBefore < secondLeftBefore, "sanity: First starts left of Second")

        onNodeWithText("First").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(x = 250f, y = 0f))
            moveBy(Offset(x = 10f, y = 0f))
            release()
        }

        waitForIdle()

        val firstLeftAfter = onNodeWithText("First").fetchSemanticsNode().boundsInRoot.left
        val secondLeftAfter = onNodeWithText("Second").fetchSemanticsNode().boundsInRoot.left
        assertTrue(
            secondLeftAfter < firstLeftAfter,
            "reorder did not stick: expected Second to render left of First after " +
                "dragging First past it, but First is still left of (or at) Second " +
                "(First=$firstLeftAfter, Second=$secondLeftAfter)",
        )
    }
}
