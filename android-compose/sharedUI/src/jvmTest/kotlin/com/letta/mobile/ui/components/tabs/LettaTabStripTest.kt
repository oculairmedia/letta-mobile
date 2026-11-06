@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.components.tabs

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.center
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.material3.MaterialTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LettaTabStripTest {
    private val threeTabs = listOf(
        LettaTab("conversation-1", "First", "Ada"),
        LettaTab("conversation-2", "Second", "Grace"),
        LettaTab("conversation-3", "Third", "Alan"),
    )

    @Test
    fun activeTabExposesCloseActionWithoutPointerHover() = runComposeUiTest {
        var closedId: String? = null
        setContent {
            MaterialTheme {
                LettaTabStrip(
                    tabs = listOf(LettaTab("conversation-1", "First", "Ada")),
                    activeKey = "conversation-1",
                    actions = LettaTabStripActions(
                        onSelect = {},
                        onClose = { closedId = it },
                    ),
                )
            }
        }

        onNodeWithContentDescription("Close First tab").performClick()

        assertEquals("conversation-1", closedId)
    }

    // The chevron picker is gone: unified search moved to a persistent field in
    // the shared shell header (ShellHeader's SearchSlot), which searches
    // agents and canvases too, not just this agent's conversations. What remains
    // on the strip is the "+".
    @Test
    fun plusStartsANewConversation() = runComposeUiTest {
        var newConversations = 0
        setContent {
            MaterialTheme {
                LettaTabStrip(
                    tabs = listOf(LettaTab("conversation-1", "First", "Ada")),
                    activeKey = "conversation-1",
                    actions = LettaTabStripActions(
                        onNew = { newConversations++ },
                        newLabel = "New conversation tab",
                    ),
                )
            }
        }

        onNodeWithContentDescription("New conversation tab").performClick()
        assertEquals(1, newConversations)
    }

    @Test
    fun plainClickStillSelectsTab() = runComposeUiTest {
        var selectedId: String? = null
        setContent {
            MaterialTheme {
                LettaTabStrip(
                    tabs = threeTabs,
                    activeKey = "conversation-1",
                    actions = LettaTabStripActions(
                        onSelect = { selectedId = it },
                        onClose = {},
                    ),
                )
            }
        }

        onNodeWithText("Second").performClick()

        assertEquals("conversation-2", selectedId)
        // letta-mobile-ckmub.3.25: the open tab reads selected to assistive tech.
        onNodeWithTag(LettaTabStripTags.tab("conversation-1")).assertIsSelected()
        onNodeWithTag(LettaTabStripTags.tab("conversation-2")).assertIsNotSelected()
    }

    @Test
    fun draggingTabAcrossNeighborFiresReorderWithTargetIndex() = runComposeUiTest {
        var reorderedId: String? = null
        var reorderedTarget: Int? = null
        setContent {
            MaterialTheme {
                LettaTabStrip(
                    tabs = threeTabs,
                    activeKey = "conversation-1",
                    actions = LettaTabStripActions(
                        onSelect = {},
                        onClose = {},
                        onReorder = { conversationId, targetIndex ->
                        reorderedId = conversationId
                        reorderedTarget = targetIndex
                    },
                    ),
                )
            }
        }

        // Drag the first tab ("First") to the right, past the second
        // tab's center, then release. This should cross the reorder
        // threshold and land "First" at index 1 -- not further: these tabs
        // are minimum-width (132dp, short labels), so a bigger move would
        // legitimately overshoot into the third tab's territory too.
        onNodeWithText("First").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(x = 200f, y = 0f))
            release()
        }
        // sh.calvin.reorderable calls onSettle only after its drop-settle
        // spring animation finishes, not synchronously on release.
        waitForIdle()

        assertEquals(
            "conversation-1",
            reorderedId,
            "drag gesture never invoked onReorder: the gesture is not reaching LettaTabStrip at all",
        )
        assertEquals(1, reorderedTarget)
    }

    @Test
    fun shortDragThatDoesNotCrossNeighborIsNoOp() = runComposeUiTest {
        var reorderCalled = false
        setContent {
            MaterialTheme {
                LettaTabStrip(
                    tabs = threeTabs,
                    activeKey = "conversation-1",
                    actions = LettaTabStripActions(
                        onSelect = {},
                        onClose = {},
                        onReorder = { _, _ -> reorderCalled = true },
                    ),
                )
            }
        }

        onNodeWithText("First").performMouseInput {
            moveTo(center)
            press()
            // A tiny move — past touch slop, but nowhere near a neighbor's
            // center — must not trigger a reorder.
            moveBy(Offset(x = 8f, y = 0f))
            release()
        }
        waitForIdle()

        assertNull(if (reorderCalled) "reorder" else null)
    }
}
