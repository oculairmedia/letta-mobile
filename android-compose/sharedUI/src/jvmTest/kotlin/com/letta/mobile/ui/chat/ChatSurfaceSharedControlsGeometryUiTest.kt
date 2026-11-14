@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.messaging.AgentMessageDeliveryState
import com.letta.mobile.data.messaging.AgentMessageDirection
import com.letta.mobile.data.messaging.AgentMessageProvenance
import com.letta.mobile.ui.chat.provenance.AgentMessageProvenanceLabel
import com.letta.mobile.ui.chat.surface.composer.ComposerActionChip
import com.letta.mobile.ui.chat.surface.composer.ComposerChipLabel
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import com.letta.mobile.ui.chat.surface.composer.TouchOpenChatButton
import com.letta.mobile.ui.components.DisclosureChevronDefaults
import com.letta.mobile.ui.components.LettaDisclosureHeader
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.TouchComposerDimens
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The chat surface's disclosure and menu marks come from the shared controls (LettaDisclosureHeader,
 * LettaUnfoldButton, LettaMenuIndicator): their glyphs keep their token sizes and stay inside
 * the frame from the phone breakpoint to a wide window, however long the text beside them.
 */
class ChatSurfaceSharedControlsGeometryUiTest {

    @Test
    fun provenanceHeaderKeepsItsChevronInsideTheFrameAndOpensTheDetails() = atEveryWidth { width ->
        var expanded by mutableStateOf(false)
        show(width) {
            AgentMessageProvenanceLabel(provenance = provenance, expanded = expanded, onToggleExpand = { expanded = !expanded })
        }
        val frame = bounds(FRAME)
        val chevron = chevronBounds()
        // Names longer than any frame ellipsize, so the chevron closes the line at the frame's edge.
        assertNear(frame.right, chevron.right)
        assertNear(LettaDimens.Control.icon, chevron.right - chevron.left)
        assertNear(LettaDimens.Control.icon, chevron.bottom - chevron.top)
        onNodeWithText("Direction:", useUnmergedTree = true).assertDoesNotExist()

        onNodeWithTag(DisclosureChevronDefaults.TestTag, useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("Direction:", useUnmergedTree = true).assertExists()
    }

    @Test
    fun disclosureHeaderEndsInItsStateWordsThenTheChevron() = atEveryWidth { width ->
        show(width) {
            LettaDisclosureHeader(expanded = false, onToggle = {}) {
                Text(LONG_NAME, modifier = Modifier.weight(1f), maxLines = 1)
                Text("Details")
            }
        }
        val frame = bounds(FRAME)
        val chevron = chevronBounds()
        val label = onNodeWithText("Details", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertNear(frame.right, chevron.right)
        assertNear(chevron.left - LettaDimens.Space.sm, label.right)
        assertNear(LettaDimens.Control.icon, chevron.right - chevron.left)
    }

    @Test
    fun agentPillKeepsItsSwitcherMarkInsideThePill() = atEveryWidth { width ->
        show(width) {
            AgentIdentityPill(
                AgentIdentity(agentId = "agent-geometry", name = LONG_NAME, isFavorite = true, isPinned = true, onClick = {}, onLongClick = {}),
            )
        }
        val frame = bounds(FRAME)
        val pill = bounds(AgentIdentityPillTestTags.PILL)
        val indicator = onNodeWithContentDescription("Switch agent", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(pill.right <= frame.right + TOLERANCE, "pill $pill overflows frame $frame")
        assertTrue(indicator.right <= pill.right && indicator.left >= pill.left, "indicator $indicator outside pill $pill")
        assertNear(LettaDimens.Space.lg, indicator.right - indicator.left)
    }

    @Test
    fun composerChipEndsInTheMenuIndicatorAtTheSmallGlyphSize() = atEveryWidth { width ->
        show(width) {
            ComposerActionChip(label = ComposerChipLabel(text = LONG_NAME, suffix = "High"), onClick = {})
        }
        val frame = bounds(FRAME)
        val chevron = bounds(ComposerTestTags.CHIP_CHEVRON)
        assertTrue(chevron.right <= frame.right + TOLERANCE, "chip chevron $chevron overflows frame $frame")
        assertNear(LettaDimens.Control.iconSm, chevron.right - chevron.left)
        assertNear(LettaDimens.Control.iconSm, chevron.bottom - chevron.top)
    }

    @Test
    fun touchOpenChatButtonKeepsItsTargetAndMatchesTheBarsGlyphs() = atEveryWidth { width ->
        show(width) { TouchOpenChatButton(onOpen = {}) }
        val target = bounds(ComposerTestTags.TOUCH_RESTORE)
        val chevron = chevronBounds()
        assertNear(TouchComposerDimens.actionTarget, target.right - target.left)
        assertNear(TouchComposerDimens.actionTarget, target.bottom - target.top)
        assertNear(TouchComposerDimens.actionIcon, chevron.right - chevron.left)
        assertNear((target.left + target.right) / 2, (chevron.left + chevron.right) / 2)
        assertNear((target.top + target.bottom) / 2, (chevron.top + chevron.bottom) / 2)
    }

    private val provenance = AgentMessageProvenance(
        direction = AgentMessageDirection.INBOUND,
        fromAgentId = "agent-from",
        toAgentId = "agent-to",
        fromAgentName = LONG_NAME,
        toAgentName = LONG_NAME,
        msgId = "msg-geometry",
        deliveryState = AgentMessageDeliveryState.SENT,
    )

    private fun atEveryWidth(assertion: ComposeUiTest.(Dp) -> Unit) {
        for (width in WIDTHS) {
            runDesktopComposeUiTest(width = 1400, height = 900) { assertion(width) }
        }
    }

    private fun ComposeUiTest.show(width: Dp, content: @Composable () -> Unit) {
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MaterialTheme {
                    Box(Modifier.width(width).testTag(FRAME)) { Column { content() } }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.chevronBounds(): DpRect =
        onNodeWithTag(DisclosureChevronDefaults.TestTag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun assertNear(expected: Dp, actual: Dp) {
        assertTrue(abs(expected.value - actual.value) <= TOLERANCE.value, "expected $expected, actual $actual (tolerance $TOLERANCE)")
    }

    private companion object {
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
        val TOLERANCE = 0.5.dp
        const val FRAME = "chat-surface-controls-frame"
        val LONG_NAME = "An agent whose name runs well past the widest chat column ".repeat(4)
    }
}
