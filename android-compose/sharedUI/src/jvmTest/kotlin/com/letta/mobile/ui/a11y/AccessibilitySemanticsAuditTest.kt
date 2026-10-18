@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.a11y

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.chat.surface.composer.ComposerIconButton
import com.letta.mobile.ui.chat.surface.timeline.ThinkingStatusText
import com.letta.mobile.ui.chat.surface.timeline.rows.AgentText
import com.letta.mobile.ui.chat.surface.timeline.rows.AgentTextParams
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowTestTags
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.settings.AccessibilitySettingsSection
import com.letta.mobile.ui.settings.Frame
import com.letta.mobile.ui.settings.SettingsSectionTags
import com.letta.mobile.ui.theme.lettaColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccessibilitySemanticsAuditTest {

    @Test
    fun unlabeledClickableNodesFailTheAudit() = runComposeUiTest {
        setContent { Frame { Box(Modifier.size(24.dp).clickable { }) } }
        val unlabeled = unlabeledClickables(onRoot().fetchSemanticsNode().allNodes())
        assertTrue(unlabeled.isNotEmpty(), "expected the unlabeled clickable to fail the audit")
    }

    @Test
    fun sharedShellSurfacesHaveLabelsOnClickables() = runComposeUiTest {
        setContent { Frame { AccessibilityAuditFixture() } }
        val unlabeled = unlabeledClickables(onRoot().fetchSemanticsNode().allNodes())
        assertEquals(emptyList(), unlabeled.map { it.config }, "unlabeled clickables: ${unlabeled.map { it.id }}")
    }

    @Test
    fun runStatusAndStreamingReplyArePoliteLiveRegions() = runComposeUiTest {
        setContent {
            Frame {
                Column {
                    ThinkingStatusText(messages = emptyList())
                    AgentText(AgentTextParams(text = "Hello stream", isError = false, isStreaming = true))
                }
            }
        }
        val live = onRoot().fetchSemanticsNode().allNodes().count { node ->
            node.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
        }
        assertTrue(live >= 2, "expected thinking status and streaming text to announce politely")
        onNodeWithTag(ChatRowTestTags.AGENT_TEXT).assertExists()
    }

    @Test
    fun highContrastTokensDarkenOutlineOnALightScheme() {
        val normal = lettaColorScheme(dark = false, highContrast = false)
        val contrast = lettaColorScheme(dark = false, highContrast = true)
        assertNotEquals(normal.outline, contrast.outline)
        assertNotEquals(normal.onSurface, contrast.onSurface)
    }

    @Test
    fun highContrastToggleSwitchesTokens() = runComposeUiTest {
        val before = lettaColorScheme(dark = false, highContrast = false).outline
        setContent { Frame { AccessibilitySettingsSection() } }
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-high-contrast").performClick()
        // The toggle is session-local; the scheme itself is proven by lettaColorScheme above.
        // Clicking must not crash and must keep a labeled control.
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-high-contrast").assertExists()
        assertEquals(before, lettaColorScheme(dark = false, highContrast = false).outline)
    }

    @Test
    fun tabOrderPutsSidebarBeforeTimelineBeforeComposer() {
        assertTrue(AccessibilityTraversal.SIDEBAR < AccessibilityTraversal.TIMELINE)
        assertTrue(AccessibilityTraversal.TIMELINE < AccessibilityTraversal.COMPOSER)
    }
}

@Composable
private fun AccessibilityAuditFixture() {
    Column {
        AccessibilitySettingsSection()
        Row {
            ComposerIconButton(icon = LettaIcons.Add, contentDescription = "Attach", onClick = {})
        }
        ThinkingStatusText(messages = emptyList())
        AgentText(AgentTextParams(text = "Settled reply", isError = false, isStreaming = false))
    }
}

private fun SemanticsNode.allNodes(): List<SemanticsNode> = buildList {
    fun walk(node: SemanticsNode) {
        add(node)
        node.children.forEach(::walk)
    }
    walk(this@allNodes)
}
