@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.a11y

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.ui.chat.surface.composer.ComposerIconButton
import com.letta.mobile.ui.chat.surface.timeline.ThinkingStatusText
import com.letta.mobile.ui.chat.surface.timeline.rows.AgentText
import com.letta.mobile.ui.chat.surface.timeline.rows.AgentTextParams
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowTestTags
import com.letta.mobile.ui.chat.surface.timeline.rows.CompactionDividerRow
import com.letta.mobile.ui.chat.surface.timeline.rows.RenderRow
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.settings.AccessibilitySettingsSection
import com.letta.mobile.ui.settings.Frame
import com.letta.mobile.ui.settings.SettingsSectionTags
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellCanvasRowModel
import com.letta.mobile.ui.shell.sidebar.ShellConversationRowModel
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceHost
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceSlots
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceState
import com.letta.mobile.ui.shell.workspace.ShellSidebarControl
import com.letta.mobile.ui.shell.workspace.ShellWindowChrome
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderState
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigation
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigationActions
import com.letta.mobile.ui.theme.HighContrastPreference
import com.letta.mobile.ui.theme.LocalHighContrast
import com.letta.mobile.ui.theme.LocalHighContrastPreference
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.ui.theme.lettaColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
        val store = InMemorySecureSettingsStore()
        val preference = HighContrastPreference(store)
        val normalOutline = lettaColorScheme(dark = false, highContrast = false).outline
        val contrastOutline = lettaColorScheme(dark = false, highContrast = true).outline
        var outerOutline: Color = Color.Unspecified
        var nestedOutline: Color = Color.Unspecified
        var highContrastActive = false

        setContent {
            CompositionLocalProvider(LocalHighContrastPreference provides preference) {
                SharedMaterialTheme(darkTheme = false) {
                    outerOutline = MaterialTheme.colorScheme.outline
                    highContrastActive = LocalHighContrast.current
                    Column {
                        AccessibilitySettingsSection()
                        SharedMaterialTheme(darkTheme = false) {
                            nestedOutline = MaterialTheme.colorScheme.outline
                        }
                    }
                }
            }
        }

        assertNull(preference.override.value)
        assertFalse(highContrastActive)
        assertEquals(normalOutline, outerOutline)
        assertEquals(normalOutline, nestedOutline)

        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + "appearance-high-contrast").performClick()
        waitForIdle()

        assertTrue(highContrastActive)
        assertEquals(contrastOutline, outerOutline)
        assertEquals(contrastOutline, nestedOutline)
        assertEquals(true, preference.override.value)
        assertEquals(true, HighContrastPreference(store).override.value)
    }

    @Test
    fun tabOrderPutsSidebarBeforeTimelineBeforeComposer() = runComposeUiTest {
        setContent {
            Frame {
                // Mount in reverse order (composer, timeline, sidebar) to verify traversal semantics govern order.
                Column {
                    Box(
                        Modifier
                            .testTag("region-composer")
                            .accessibilityTraversal(AccessibilityTraversal.COMPOSER)
                            .focusable(),
                    ) { Text("Composer") }
                    Box(
                        Modifier
                            .testTag("region-timeline")
                            .accessibilityTraversal(AccessibilityTraversal.TIMELINE)
                            .focusable(),
                    ) { Text("Timeline") }
                    Box(
                        Modifier
                            .testTag("region-sidebar")
                            .accessibilityTraversal(AccessibilityTraversal.SIDEBAR)
                            .focusable(),
                    ) { Text("Sidebar") }
                }
            }
        }

        val regions = listOf("region-composer", "region-timeline", "region-sidebar").map { tag ->
            val node = onNodeWithTag(tag).fetchSemanticsNode()
            val isGroup = node.config.getOrNull(SemanticsProperties.IsTraversalGroup)
            assertEquals(true, isGroup, "$tag must be a traversal group")
            tag to (node.config.getOrNull(SemanticsProperties.TraversalIndex) ?: Float.MAX_VALUE)
        }
        val orderedTags = regions.sortedBy { it.second }.map { it.first }
        assertEquals(listOf("region-sidebar", "region-timeline", "region-composer"), orderedTags)
    }
}

@Composable
private fun AccessibilityAuditFixture() {
    val workspaceState = LettaShellWorkspaceState(
        header = ShellWorkspaceHeaderState(
            title = "Workspace",
            sidebar = ShellSidebarControl(collapsed = false, onToggle = {}),
        ),
        navigation = ShellWorkspaceNavigation(
            rail = ShellLibraryRailState(
                agents = listOf("agent-a" to "Ada", "agent-b" to "Bo"),
                focus = ShellRailFocus(selectedAgentId = "agent-a"),
            ),
            panel = ShellAgentPanelState(
                agent = ShellPanelAgent(name = "Ada", agentId = "agent-a"),
                selectedSection = LensDestination.Schedules,
                conversations = listOf(
                    ShellConversationRowModel(id = "c1", title = "Audit chat", preview = "Preview", timeLabel = "2m"),
                ),
                canvases = listOf(ShellCanvasRowModel(id = CanvasId("k1"), title = "Canvas", timeLabel = "1h")),
            ),
        ),
    )
    val workspaceHost = LettaShellWorkspaceHost(
        actions = ShellWorkspaceNavigationActions(),
        chrome = ShellWindowChrome.Inline,
        slots = LettaShellWorkspaceSlots(main = { modifier -> AccessibilityAuditMainColumn(modifier) }),
    )
    LettaShellWorkspace(
        state = workspaceState,
        host = workspaceHost,
        modifier = Modifier.size(width = 1440.dp, height = 900.dp),
    )
}

@Composable
private fun AccessibilityAuditMainColumn(modifier: Modifier = Modifier) {
    Column(modifier) {
        AccessibilitySettingsSection()
        Row {
            ComposerIconButton(icon = LettaIcons.Add, contentDescription = "Attach", onClick = {})
        }
        ThinkingStatusText(messages = emptyList())
        CompactionDividerRow(summary = "Compacted context")
        RenderRow(
            ChatRenderItem.Single(
                message = UiMessage(
                    id = "user-1",
                    role = "user",
                    content = "Audit prompt",
                    timestamp = "2026-07-19T12:00:00Z",
                    imageAttachments = listOf(UiImageAttachment(mimeType = "image/png", base64 = "")),
                ),
                groupPosition = GroupPosition.None,
            ),
        )
        RenderRow(
            ChatRenderItem.Single(
                message = UiMessage(
                    id = "assistant-1",
                    role = "assistant",
                    content = "Settled reply",
                    timestamp = "2026-07-19T12:01:00Z",
                    reasoning = "Verified clickables",
                    toolCalls = listOf(
                        UiToolCall(
                            name = "shell",
                            arguments = "echo ok",
                            result = "ok",
                            status = "completed",
                            toolCallId = "call-1",
                        ),
                    ),
                ),
                groupPosition = GroupPosition.None,
            ),
        )
    }
}

private fun SemanticsNode.allNodes(): List<SemanticsNode> = buildList {
    fun walk(node: SemanticsNode) {
        add(node)
        node.children.forEach(::walk)
    }
    walk(this@allNodes)
}
