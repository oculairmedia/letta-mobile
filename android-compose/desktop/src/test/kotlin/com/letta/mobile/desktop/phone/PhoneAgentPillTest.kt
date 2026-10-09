@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.ui.chat.AgentIdentityPillTestTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-vgouv: the phone preview draws the shared [com.letta.mobile.ui.chat.AgentIdentityPill]
 * in its chat header and, alone, in canvas mode - the one pill Android draws.
 */
class PhoneAgentPillTest {

    @Test
    fun theChatHeaderDrawsTheSharedPillBetweenTheMenuAndTheCanvasButton() = runDesktopComposeUiTest(width = 400, height = 300) {
        mainClock.autoAdvance = false
        var switched = 0
        var canvas = 0
        setContent {
            MaterialTheme {
                PhoneChatHeader(
                    identity = phoneAgentIdentity("agent-1", "Meridian") { switched += 1 },
                    onMenu = {},
                    onCanvas = { canvas += 1 },
                    onHeightChange = {},
                )
            }
        }
        mainClock.advanceTimeBy(500)
        onNodeWithTag(PhoneShellTags.CHAT_HEADER).assertExists()
        onNodeWithTag(AgentIdentityPillTestTags.PILL, useUnmergedTree = true).assertExists()
        onNodeWithText("Meridian", useUnmergedTree = true).assertExists()

        onNodeWithTag(AgentIdentityPillTestTags.TRIGGER, useUnmergedTree = true).performClick()
        assertEquals(1, switched, "tapping the pill opens the agents panel, the phone's switcher")
        assertEquals(0, canvas)
    }

    @Test
    fun theCanvasModeDrawsThePillAloneWithNoHeader() = runDesktopComposeUiTest(width = 400, height = 300) {
        mainClock.autoAdvance = false
        var switched = 0
        setContent {
            MaterialTheme {
                PhoneCanvasIdentityPill(phoneAgentIdentity("agent-1", "Meridian") { switched += 1 })
            }
        }
        mainClock.advanceTimeBy(500)
        onNodeWithTag(PhoneShellTags.CANVAS_IDENTITY_PILL).assertExists()
        onNodeWithTag(PhoneShellTags.CHAT_HEADER).assertDoesNotExist()
        onNodeWithTag(AgentIdentityPillTestTags.PILL, useUnmergedTree = true).assertExists()
        onNodeWithTag(AgentIdentityPillTestTags.TRIGGER, useUnmergedTree = true).performClick()
        assertEquals(1, switched)
    }

    @Test
    fun aLongNameNeverReachesTheTrailingSideWhereTheBoardKeepsItsActions() = runDesktopComposeUiTest(width = 400, height = 300) {
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                PhoneCanvasIdentityPill(phoneAgentIdentity("agent-1", "A very long agent name that would run across the whole phone screen") {})
            }
        }
        mainClock.advanceTimeBy(500)
        val right = onNodeWithTag(AgentIdentityPillTestTags.PILL, useUnmergedTree = true).getBoundsInRoot().right
        assertTrue(right.value <= 400 * CANVAS_PILL_MAX_WIDTH_FRACTION, "the pill ends at $right")
    }

    @Test
    fun theCanvasPillReportsItsHeightSoTheBoardReservesItsRoom() = runDesktopComposeUiTest(width = 400, height = 300) {
        mainClock.autoAdvance = false
        var reported = 0f
        setContent {
            MaterialTheme {
                PhoneCanvasIdentityPill(phoneAgentIdentity("agent-1", "Meridian") {}, onHeightChange = { reported = it.value })
            }
        }
        mainClock.advanceTimeBy(500)
        assertTrue(reported > 0f, "the frame adds this to the board's top inset")
    }

    @Test
    fun aBlankAgentNameFallsBackToChat() {
        assertEquals("Chat", phoneAgentIdentity(null, "  ") {}.name)
        assertEquals("", phoneAgentIdentity(null, null) {}.agentId)
    }
}
