package com.letta.mobile.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.motion.ChatMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatusTimelineUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun collapsibleStatusRow_exposesStateDescriptionAndExpandCollapseActions() {
        var expanded by mutableStateOf(false)

        composeTestRule.setContent {
            CollapsibleStatusRow(
                title = "Tool Execution",
                statusLabel = "Completed",
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                Text("Result content")
            }
        }

        val rowNode = composeTestRule.onNodeWithText("Tool Execution")
        rowNode.assertExists()
        rowNode.assertHasClickAction()

        // Verify initial state description when collapsed
        val collapsedSemantics = rowNode.fetchSemanticsNode()
        assertEquals("Completed, Collapsed", collapsedSemantics.config[SemanticsProperties.StateDescription])

        // Perform native accessibility expand action
        rowNode.performSemanticsAction(SemanticsActions.Expand)
        assertTrue(expanded)

        // Verify updated state description when expanded
        val expandedSemantics = rowNode.fetchSemanticsNode()
        assertEquals("Completed, Expanded", expandedSemantics.config[SemanticsProperties.StateDescription])

        // Perform native accessibility collapse action
        rowNode.performSemanticsAction(SemanticsActions.Collapse)
        assertFalse(expanded)
    }

    @Test
    fun collapsibleStatusRow_guaranteesMinimum48dpTouchTargetHeight() {
        composeTestRule.setContent {
            CollapsibleStatusRow(
                title = "Short Title",
                expanded = false,
                onExpandedChange = {},
            )
        }

        composeTestRule.onNodeWithText("Short Title")
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun timelineNode_interactiveTarget_guaranteesMinimum48dpTouchTarget() {
        var clicked = false

        composeTestRule.setContent {
            TimelineNode(
                icon = LettaIcons.Check,
                contentDescription = "Confirm step",
                onClick = { clicked = true },
                onClickLabel = "Confirm step",
            )
        }

        val node = composeTestRule.onNodeWithContentDescription("Confirm step")
        node.assertExists()
        node.assertWidthIsAtLeast(48.dp)
        node.assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun timelineConnector_andRailContainers_areExcludedFromAccessibilityTree() {
        composeTestRule.setContent {
            StatusTimelineItem(
                node = { TimelineNode(icon = LettaIcons.Tool) },
                showTopConnector = true,
                showBottomConnector = true,
            ) {
                Text("Content item")
            }
        }

        // Rail lines and non-interactive node circles must not produce unmerged accessibility nodes
        val root = composeTestRule.onRoot()
        val allChildren = root.fetchSemanticsNode().children
        // Only the content item text should be exposed as a focusable/accessible node
        assertTrue(allChildren.size <= 2)
    }

    @Test
    fun reducedMotionPolicy_isImmediateAndHonored() {
        val reducedPolicy = ChatMotionPolicy.Reduced
        var expanded by mutableStateOf(false)

        composeTestRule.setContent {
            CollapsibleStatusRow(
                title = "Reduced Motion Step",
                expanded = expanded,
                onExpandedChange = { expanded = it },
                motionPolicy = reducedPolicy,
            ) {
                Text("Instant Detail Text")
            }
        }

        composeTestRule.onNodeWithText("Instant Detail Text").assertDoesNotExist()

        // Toggle expanded
        expanded = true
        composeTestRule.waitForIdle()

        // Detail text immediately exists without frame delays under reduced motion
        composeTestRule.onNodeWithText("Instant Detail Text").assertExists()
    }
}
