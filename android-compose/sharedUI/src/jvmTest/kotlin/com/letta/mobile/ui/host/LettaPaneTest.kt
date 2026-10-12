@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.context.AgentContextCardActions
import com.letta.mobile.ui.context.AgentContextCardBinding
import com.letta.mobile.ui.context.AgentContextCardDeps
import com.letta.mobile.ui.context.AgentContextCardHost
import com.letta.mobile.ui.context.AgentContextFocus
import com.letta.mobile.ui.context.AgentContextTags
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-ckmub.3.1: the shared container contract (P1) and the surfaces migrated onto it. */
class LettaPaneTest {
    @Test
    fun intentsResolveByInput() {
        assertEquals(LettaPaneKind.Sheet, LettaHost.Touch.resolve(LettaPaneKind.Adaptive()))
        assertEquals(LettaPaneKind.Popover(PaneAnchor.BelowEnd), LettaHost.Pointer.resolve(LettaPaneKind.Adaptive(PaneAnchor.BelowEnd)))
        assertEquals(LettaPaneKind.Sheet, LettaHost.Touch.resolve(LettaPaneKind.Modal))
        assertEquals(LettaPaneKind.Dialog, LettaHost.Pointer.resolve(LettaPaneKind.Modal))
        // A concrete kind is the surface's explicit choice on every host.
        assertEquals(LettaPaneKind.Alert, LettaHost.Touch.resolve(LettaPaneKind.Alert))
        assertEquals(LettaPaneKind.Dialog, LettaHost.Touch.resolve(LettaPaneKind.Dialog))
    }

    @Test
    fun anAdaptivePaneIsASheetOnTouch() = runComposeUiTest {
        setContent { Host(LettaHost.Touch) { Pane(LettaPaneKind.Adaptive()) } }
        onNodeWithTag(LettaPaneTags.SHEET).assertExists()
        onNodeWithTag(BODY).assertExists()
        onNodeWithTag(LettaPaneTags.POPOVER).assertDoesNotExist()
    }

    @Test
    fun anAdaptivePaneIsAPopoverOnPointer() = runComposeUiTest {
        setContent { Host(LettaHost.Pointer) { Pane(LettaPaneKind.Adaptive()) } }
        onNodeWithTag(LettaPaneTags.POPOVER).assertExists()
        onNodeWithTag(BODY).assertExists()
        onNodeWithTag(LettaPaneTags.SHEET).assertDoesNotExist()
    }

    @Test
    fun escapeDismissesADialog() = escapeDismisses(LettaPaneKind.Dialog)

    @Test
    fun escapeDismissesAPopover() = escapeDismisses(LettaPaneKind.Popover())

    @Test
    fun escapeDismissesAnAlert() = escapeDismisses(LettaPaneKind.Alert)

    private fun escapeDismisses(kind: LettaPaneKind) = runComposeUiTest {
        var open by mutableStateOf(true)
        setContent { Host(LettaHost.Pointer) { if (open) Pane(kind, onDismiss = { open = false }) } }
        onNodeWithTag(BODY).assertExists()
        onNodeWithTag(BODY).requestFocus().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(false, open)
        onNodeWithTag(BODY).assertDoesNotExist()
    }

    @Test
    fun theConfirmationAsksThenConfirmsOrCancels() = runComposeUiTest {
        var shown by mutableStateOf(true)
        val outcomes = mutableListOf<String>()
        val request = LettaConfirmRequest(title = "Delete chat?", message = "Gone for good.", confirmLabel = "Delete")
        setContent {
            Host(LettaHost.Touch) {
                if (shown) {
                    LettaConfirmPane(request, onConfirm = { outcomes += "confirm"; shown = false }, onDismiss = { outcomes += "dismiss"; shown = false })
                }
            }
        }
        onNodeWithTag(LettaPaneTags.ALERT).assertExists()
        onNodeWithText("Delete chat?").assertExists()
        onNodeWithText("Gone for good.").assertExists()
        onNodeWithText("Cancel").performClick()
        assertEquals(listOf("dismiss"), outcomes)
        shown = true
        waitForIdle()
        onNodeWithText("Delete").performClick()
        assertEquals(listOf("dismiss", "confirm"), outcomes)
    }

    @Test
    fun theAgentContextSheetOpensThroughTheHost() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val recording = LettaHost.Pointer.copy(
            sheet = { spec, content -> opened += "sheet"; Column { Text(spec.testTag.orEmpty()); content() } },
            popover = { spec, content -> opened += "popover"; Column { Text(spec.testTag.orEmpty()); content() } },
        )
        setContent { Host(recording) { AgentContextCardHost(AgentContextCardBinding(contextDeps, AgentContextCardActions(onModelSelected = {})), contextFocus) } }
        onNodeWithTag(AgentContextTags.CARD).performClick()
        waitForIdle()
        assertEquals("popover", opened.distinct().single())
        onNodeWithText(AgentContextTags.SHEET).assertExists()
    }

    @Test
    fun theAgentContextSheetIsABottomSheetOnTouch() = runComposeUiTest {
        val opened = mutableListOf<String>()
        val recording = LettaHost.Touch.copy(
            sheet = { _, content -> opened += "sheet"; Column(content = content) },
            popover = { _, content -> opened += "popover"; Column(content = content) },
        )
        setContent { Host(recording) { AgentContextCardHost(AgentContextCardBinding(contextDeps, AgentContextCardActions(onModelSelected = {})), contextFocus) } }
        onNodeWithTag(AgentContextTags.CARD).performClick()
        waitForIdle()
        assertEquals("sheet", opened.distinct().single())
    }

    @Test
    fun everyPopupMenuIsTheHostsMenu() = runComposeUiTest {
        val menus = mutableListOf<Boolean>()
        val recording = LettaHost.Touch.copy(menu = { expanded, _, content -> menus += expanded; if (expanded) Column(content = content) })
        val picked = mutableListOf<String>()
        setContent {
            Host(recording) {
                Box { LettaPopupMenu(expanded = true, onDismiss = {}, items = listOf(LettaMenuItem("Rename") { picked += "rename" })) }
            }
        }
        onNodeWithText("Rename").performClick()
        waitForIdle()
        assertEquals(true, menus.first())
        assertEquals(listOf("rename"), picked)
    }

    @Composable
    private fun Host(host: LettaHost, content: @Composable () -> Unit) {
        MaterialTheme { ProvideLettaHost(host, content) }
    }

    @Composable
    private fun Pane(kind: LettaPaneKind, onDismiss: () -> Unit = {}) {
        LettaPane(kind, onDismiss = onDismiss, title = "Pane", testTag = BODY) { Text("Pane body") }
    }

    private val contextDeps = AgentContextCardDeps(ContextTokenReadings(), breakdown = null, compaction = null, pickerSource = null)
    private val contextFocus = AgentContextFocus(
        agentId = "agent-1",
        conversationId = null,
        modelLabel = "Claude Opus",
        modelValue = null,
        effort = null,
        windowTokens = 200_000,
        turnRunning = false,
    )

    private companion object {
        const val BODY = "pane-body"
    }
}
