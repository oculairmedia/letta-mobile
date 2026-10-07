package com.letta.mobile.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.letta.mobile.feature.chat.screen.AgentScaffoldTestTags
import com.letta.mobile.feature.chat.screen.AgentScaffoldTopChromeLayout
import com.letta.mobile.ui.chat.AgentIdentity
import com.letta.mobile.ui.chat.AgentIdentityPillTestTags
import com.letta.mobile.ui.test.setLettaTestContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-bglj6.1.22: the chat header and the phone canvas mode draw the one shared agent
 * pill ([com.letta.mobile.ui.chat.AgentIdentityPill]), at the same size and in the same spot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Tag("integration")
class AgentScaffoldTopChromeUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun headerAndCanvasModeDrawTheSameSharedPill() {
        var headerHidden by mutableStateOf(false)
        var clicks = 0
        val identity = AgentIdentity(
            agentId = "agent-pill-1",
            name = "PillBot",
            isFavorite = true,
            isPinned = true,
            onClick = { clicks++ },
            onLongClick = {},
        )
        composeRule.setLettaTestContent {
            AgentScaffoldTopChromeLayout(
                headerHidden = headerHidden,
                identity = identity,
                searchField = null,
                onMenuClick = {},
                scrollBehavior = null,
            )
        }

        val pill = hasTestTag(AgentIdentityPillTestTags.PILL)
        composeRule.onAllNodes(pill and hasAnyAncestor(hasTestTag(AgentScaffoldTestTags.HEADER))).assertCountEquals(1)
        composeRule.onAllNodesWithTag(AgentScaffoldTestTags.CANVAS_IDENTITY_PILL).assertCountEquals(0)
        val headerBounds = composeRule.onNodeWithTag(AgentIdentityPillTestTags.PILL).getUnclippedBoundsInRoot()

        headerHidden = true
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag(AgentScaffoldTestTags.HEADER).assertCountEquals(0)
        composeRule.onAllNodesWithTag(AgentScaffoldTestTags.MENU_BUTTON).assertCountEquals(0)
        composeRule.onAllNodes(pill and hasAnyAncestor(hasTestTag(AgentScaffoldTestTags.CANVAS_IDENTITY_PILL)))
            .assertCountEquals(1)
        val canvasBounds = composeRule.onNodeWithTag(AgentIdentityPillTestTags.PILL).getUnclippedBoundsInRoot()
        // One element: the same size, and where the header drew it, so switching modes keeps it still.
        assertEquals(headerBounds, canvasBounds)

        composeRule.onNodeWithTag(AgentScaffoldTestTags.CONVERSATION_PICKER_TRIGGER).performClick()
        assertEquals(1, clicks)
    }
}
