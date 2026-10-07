@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.composer.ComposerCompanionTags
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.MascotTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-bglj6.1: the minimised dock is the agent's mascot over its prompt bar, with no
 * panel. A sent prompt makes it think; the reply lands in a speech bubble that opens the panel
 * when tapped and stays dismissed until the next prompt. The clock is driven by hand.
 */
class CollapsedDockUiTest {
    private val agent = "agent-1"

    private val prompt = UiMessage(id = "u1", role = "user", content = "Sketch a kitchen layout.", timestamp = "2026-09-30T18:02:00Z")
    private val reply = UiMessage(
        id = "a1",
        role = "assistant",
        content = "Here's an L-shaped plan.",
        timestamp = "2026-09-30T18:02:09Z",
        runId = "run-1",
    )

    private class Port(messages: List<UiMessage>, composer: ChatComposerUiState = ChatComposerUiState()) : ChatSessionPort {
        override val uiState = MutableStateFlow(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-1"),
                messages = messages.toPersistentList(),
                isLoadingMessages = false,
                agentName = "Meridian",
                agentId = "agent-1",
            ),
        )
        override val composer = MutableStateFlow(composer)
        val recording = RecordingChatActions()
        override val actions: ChatActions = recording

        fun update(transform: (ChatUiState) -> ChatUiState) {
            uiState.value = transform(uiState.value)
        }
    }

    private class Harness {
        var geometry by mutableStateOf(ChatDockGeometry(collapsed = true))
        val reported = mutableListOf<ChatDockGeometry>()
        var agentPaneOpens = 0
    }

    private fun ComposeUiTest.show(port: Port, shell: FakeMascotShell = FakeMascotShell(agent)): Harness {
        val harness = Harness()
        mainClock.autoAdvance = false
        setContent {
            shell.Provide {
                MaterialTheme {
                    ChatSurface(
                        port = port,
                        presentation = ChatSurfacePresentation.CanvasFirst,
                        onIntent = {},
                        host = ChatSurfaceHost(openCanvas = {}, openAgentPane = { harness.agentPaneOpens++ }),
                        canvas = { _ -> Box(Modifier.fillMaxSize()) },
                        dockGeometry = harness.geometry,
                        onDockGeometryChange = {
                            harness.geometry = it
                            harness.reported += it
                        },
                    )
                }
            }
        }
        settle()
        return harness
    }

    private fun ComposeUiTest.settle() {
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        waitForIdle()
    }

    @Test
    fun collapsedShowsTheMascotOverTheBarAndNoPanel() = runComposeUiTest {
        val shell = FakeMascotShell(agent)
        show(Port(listOf(prompt, reply)), shell)
        onNodeWithTag(DOCK_COLLAPSED_TAG).assertExists()
        onNodeWithTag(DOCK_SURFACE_TAG).assertDoesNotExist()
        onNodeWithTag(DOCK_HEADER_TAG).assertDoesNotExist()
        onNodeWithTag(DOCKED_REPLY_TAG).assertDoesNotExist()
        onNodeWithTag(DOCK_COLLAPSED_MASCOT_TAG).assertExists()
        onNodeWithTag(ComposerTestTags.DOCKED_BAR).assertExists()
        // The mascot stands above the bar, not in a companion slot beside it.
        onNodeWithTag(ComposerCompanionTags.SLOT).assertDoesNotExist()
        val mascot = onNodeWithTag(DOCK_COLLAPSED_MASCOT_TAG).getBoundsInRoot()
        val bar = onNodeWithTag(ComposerTestTags.DOCKED_BAR).getBoundsInRoot()
        assertTrue(mascot.bottom <= bar.top, "mascot $mascot above bar $bar")
        // The same seat the composer companion uses, so the agent pane can still fly it over.
        assertNotNull(shell.transport.seat(MascotTransport.SeatKey(agent, MascotStage.COMPOSER_COMPANION)))
        assertEquals(MascotStage.COMPOSER_COMPANION, shell.transport.activeStage(agent))
    }

    @Test
    fun sendingThinksThenTheStreamingReplyFillsTheBubble() = runComposeUiTest {
        val port = Port(emptyList(), ChatComposerUiState(text = "Sketch a kitchen layout.", canSend = true))
        show(port)
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).assertDoesNotExist()

        onNodeWithTag(ComposerTestTags.SEND).performClick()
        settle()
        assertEquals(1, port.recording.count("send"))
        // The owner echoes the prompt and starts the run: the agent thinks, no bubble yet.
        port.update { it.copy(messages = persistentListOf(prompt), isStreaming = true) }
        settle()
        onNodeWithTag(DOCK_COLLAPSED_THINKING_TAG).assertExists()
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).assertDoesNotExist()

        port.update { it.copy(messages = persistentListOf(prompt, reply.copy(content = "Here's an L"))) }
        settle()
        onNodeWithTag(DOCK_COLLAPSED_THINKING_TAG).assertDoesNotExist()
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).assertContentDescriptionContains("Meridian replied: Here's an L", substring = true)

        port.update { it.copy(messages = persistentListOf(prompt, reply), isStreaming = false) }
        settle()
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).assertContentDescriptionContains("Here's an L-shaped plan.", substring = true)
    }

    @Test
    fun aRunningToolShowsAWorkingLine() = runComposeUiTest {
        val port = Port(listOf(prompt))
        show(port)
        port.update {
            it.copy(
                messages = persistentListOf(prompt, reply.copy(content = "Measuring first.")),
                isStreaming = true,
                pendingTools = persistentListOf(com.letta.mobile.ui.chat.render.PendingToolCall("t1", "measure", 0L)),
            )
        }
        settle()
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).assertContentDescriptionContains("Running measure", substring = true)
    }

    @Test
    fun tappingTheBubbleRestoresThePanel() = runComposeUiTest {
        val harness = show(Port(listOf(prompt, reply)))
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).performClick()
        settle()
        assertFalse(harness.geometry.collapsed)
        onNodeWithTag(DOCK_SURFACE_TAG).assertExists()
        onNodeWithTag(DOCKED_REPLY_TAG).assertExists()
        onNodeWithTag(DOCK_COLLAPSED_TAG).assertDoesNotExist()
    }

    @Test
    fun theRestoreControlAndTheHeaderSwitchBetweenTheTwoStates() = runComposeUiTest {
        val harness = show(Port(listOf(prompt, reply)))
        val collapsed = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        onNodeWithTag(DOCK_RESTORE_TAG).performClick()
        settle()
        assertFalse(harness.geometry.collapsed)
        val panel = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        assertEquals(collapsed.bottom, panel.bottom)
        onNodeWithTag(DOCK_COLLAPSE_TAG).performClick()
        settle()
        assertTrue(harness.geometry.collapsed)
        onNodeWithTag(DOCK_COLLAPSED_MASCOT_TAG).assertExists()
    }

    @Test
    fun dismissingHidesTheBubbleUntilTheNextPrompt() = runComposeUiTest {
        val port = Port(listOf(prompt, reply))
        val harness = show(port)
        onNodeWithTag(DOCK_COLLAPSED_DISMISS_TAG).performClick()
        settle()
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).assertDoesNotExist()
        assertTrue(harness.geometry.collapsed, "dismissing only hides the bubble")

        val next = UiMessage(id = "u2", role = "user", content = "Add an island.", timestamp = "2026-09-30T18:03:00Z")
        val answer = UiMessage(id = "a2", role = "assistant", content = "Island added.", timestamp = "2026-09-30T18:03:05Z", runId = "run-2")
        port.update { it.copy(messages = persistentListOf(prompt, reply, next, answer)) }
        settle()
        onNodeWithTag(DOCK_COLLAPSED_BUBBLE_TAG).assertContentDescriptionContains("Island added.", substring = true)
    }

    @Test
    fun anApprovalShowsANeedsInputChipThatOpensThePanel() = runComposeUiTest {
        val ask = reply.copy(
            content = "",
            approvalRequest = UiApprovalRequest("req-1", listOf(UiApprovalToolCall("t1", "delete_board", "{}"))),
        )
        val harness = show(Port(listOf(prompt, ask)))
        onNodeWithTag(DOCK_COLLAPSED_NEEDS_INPUT_TAG).performClick()
        settle()
        assertFalse(harness.geometry.collapsed)
    }

    @Test
    fun aFormWaitingBeforeAnyPromptShowsItsChipAndCanBeDismissed() = runComposeUiTest {
        // No messages, so no turn: the bubble still has the form to tell.
        val port = Port(emptyList())
        val form = A2uiSurfaceState(surfaceId = "surface-1", rootComponentId = null, components = emptyMap())
        port.update { it.copy(a2uiSurfaces = persistentMapOf("surface-1" to form)) }
        show(port)
        onNodeWithTag(DOCK_COLLAPSED_NEEDS_INPUT_TAG).assertExists()
        onNodeWithTag(DOCK_COLLAPSED_DISMISS_TAG).performClick()
        settle()
        onNodeWithTag(DOCK_COLLAPSED_NEEDS_INPUT_TAG).assertDoesNotExist()
    }

    @Test
    fun draggingTheMascotMovesTheDockAndReportsTheGeometry() = runComposeUiTest {
        val harness = show(Port(listOf(prompt, reply)))
        val before = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        onNodeWithTag(DOCK_COLLAPSED_MASCOT_TAG).performTouchInput { swipe(center, center + Offset(-150f, -120f)) }
        settle()
        val after = onNodeWithTag(DOCK_PANEL_TAG).getBoundsInRoot()
        assertTrue(after.left < before.left, "moved left: $before -> $after")
        assertTrue(after.top < before.top, "moved up: $before -> $after")
        assertTrue(harness.reported.isNotEmpty())
        assertTrue(harness.geometry.collapsed)
        assertTrue(harness.geometry.anchorX < 0.5f && harness.geometry.anchorY < 1f, "${harness.geometry}")
    }

    @Test
    fun tappingTheMascotOpensTheAgentPaneWithoutALayer() = runComposeUiTest {
        // Without a transport layer the seat draws the live mascot itself, hit area included.
        val harness = show(Port(listOf(prompt, reply)), FakeMascotShell(agent, layerMounted = false))
        onNodeWithTag(DOCK_COLLAPSED_MASCOT_TAG).performClick()
        settle()
        assertEquals(1, harness.agentPaneOpens)
        assertTrue(harness.geometry.collapsed)
    }

    private companion object {
        const val SETTLE_MILLIS = 2_000L
    }
}
