@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.center
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopConversationTabsUiTest {
    private val threeTabs = listOf(
        DesktopConversationTab("conversation-1", "First", "Ada"),
        DesktopConversationTab("conversation-2", "Second", "Grace"),
        DesktopConversationTab("conversation-3", "Third", "Alan"),
    )

    @Test
    fun activeTabExposesCloseActionWithoutPointerHover() = runComposeUiTest {
        var closedId: String? = null
        setContent {
            DesktopMaterialTheme {
                DesktopConversationTabRow(
                    tabs = listOf(DesktopConversationTab("conversation-1", "First", "Ada")),
                    activeConversationId = "conversation-1",
                    onSelect = {},
                    onClose = { closedId = it },
                )
            }
        }

        onNodeWithContentDescription("Close First tab").performClick()

        assertEquals("conversation-1", closedId)
    }

    @Test
    fun plainClickStillSelectsTab() = runComposeUiTest {
        var selectedId: String? = null
        setContent {
            DesktopMaterialTheme {
                DesktopConversationTabRow(
                    tabs = threeTabs,
                    activeConversationId = "conversation-1",
                    onSelect = { selectedId = it },
                    onClose = {},
                )
            }
        }

        onNodeWithText("Second").performClick()

        assertEquals("conversation-2", selectedId)
    }

    @Test
    fun draggingTabAcrossNeighborFiresReorderWithTargetIndex() = runComposeUiTest {
        var reorderedId: String? = null
        var reorderedTarget: Int? = null
        setContent {
            DesktopMaterialTheme {
                DesktopConversationTabRow(
                    tabs = threeTabs,
                    activeConversationId = "conversation-1",
                    onSelect = {},
                    onClose = {},
                    onReorder = { conversationId, targetIndex ->
                        reorderedId = conversationId
                        reorderedTarget = targetIndex
                    },
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
            "drag gesture never invoked onReorder — the gesture is not reaching DesktopConversationTabRow at all",
        )
        assertEquals(1, reorderedTarget)
    }

    @Test
    fun shortDragThatDoesNotCrossNeighborIsNoOp() = runComposeUiTest {
        var reorderCalled = false
        setContent {
            DesktopMaterialTheme {
                DesktopConversationTabRow(
                    tabs = threeTabs,
                    activeConversationId = "conversation-1",
                    onSelect = {},
                    onClose = {},
                    onReorder = { _, _ -> reorderCalled = true },
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
