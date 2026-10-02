@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import com.letta.mobile.ui.chat.surface.RecordingChatActions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.MascotSeat
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.theme.ChatMascotDimens
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * The agent's mascot beside the shared composer (letta-mobile-bglj6.1): its seat registers with
 * the window's transport, so the slot closes while the character stands at another seat (the
 * agent pane) and reopens when it comes back - in the full card and in the docked bar.
 */
class ComposerCompanionUiTest {
    private val agent = "agent-1"

    private fun ComposeUiTest.showPanel(
        shell: FakeMascotShell,
        mode: ChatSurfaceMode,
        agentPaneOpen: () -> Boolean = { false },
        agentId: String? = agent,
    ) {
        setContent {
            shell.Provide {
                MaterialTheme {
                    Column(Modifier.width(900.dp)) {
                        // The agent pane's hero seat, as the desktop sidebar declares it.
                        if (agentPaneOpen()) MascotSeat(agent, MascotStage.AGENT_PANE_HERO, 80.dp, empty = {})
                        ChatComposerPanel(
                            composer = ChatComposerUiState(),
                            uiState = ChatUiState(agentId = agentId),
                            actions = RecordingChatActions(),
                            capabilities = ChatSurfaceCapabilities.Default,
                            host = ChatSurfaceHost(),
                            platform = ChatSurfacePlatform.Default,
                            mode = mode,
                            onIntent = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun companionSlotOpensBesideTheFullComposer() = runComposeUiTest {
        val shell = FakeMascotShell(agent)
        showPanel(shell, ChatSurfaceMode.FullScreen)
        waitForIdle()
        onNodeWithTag(ComposerCompanionTags.SLOT).assertWidthIsEqualTo(ChatMascotDimens.composerCompanionSlot)
        assertNotNull(shell.registry.inputBounds.value, "the prompt field publishes where the mascot can look")
    }

    @Test
    fun slotEmptiesWhileTheMascotStandsInTheAgentPaneAndRefillsOnReturn() = runComposeUiTest {
        val shell = FakeMascotShell(agent)
        var paneOpen by mutableStateOf(false)
        showPanel(shell, ChatSurfaceMode.FullScreen, agentPaneOpen = { paneOpen })
        waitForIdle()
        onNodeWithTag(ComposerCompanionTags.SLOT).assertWidthIsEqualTo(ChatMascotDimens.composerCompanionSlot)

        // The shell opens the sidebar: the character flies to the pane's seat.
        shell.transport.transportTo(agent, MascotStage.AGENT_PANE_HERO)
        paneOpen = true
        waitForIdle()
        onNodeWithTag(ComposerCompanionTags.SLOT).assertWidthIsEqualTo(0.dp)

        // The pane closes: its seat goes away and the character returns to the composer.
        paneOpen = false
        waitForIdle()
        onNodeWithTag(ComposerCompanionTags.SLOT).assertWidthIsEqualTo(ChatMascotDimens.composerCompanionSlot)
    }

    @Test
    fun dockedBarCarriesTheCompanionAndHandsItOffToo() = runComposeUiTest {
        val shell = FakeMascotShell(agent)
        var paneOpen by mutableStateOf(false)
        showPanel(shell, ChatSurfaceMode.Docked, agentPaneOpen = { paneOpen })
        waitForIdle()
        onNodeWithTag(ComposerTestTags.DOCKED_BAR).assertExists()
        onNodeWithTag(ComposerCompanionTags.SLOT).assertWidthIsEqualTo(ChatMascotDimens.composerCompanionSlot)

        shell.transport.transportTo(agent, MascotStage.AGENT_PANE_HERO)
        paneOpen = true
        waitForIdle()
        onNodeWithTag(ComposerCompanionTags.SLOT).assertWidthIsEqualTo(0.dp)

        shell.transport.rest(agent)
        paneOpen = false
        waitForIdle()
        onNodeWithTag(ComposerCompanionTags.SLOT).assertWidthIsEqualTo(ChatMascotDimens.composerCompanionSlot)
    }

    @Test
    fun noCompanionWithoutAMascotForTheAgent() = runComposeUiTest {
        showPanel(FakeMascotShell(), ChatSurfaceMode.FullScreen, agentId = "agent-without-identity")
        waitForIdle()
        onNodeWithTag(ComposerCompanionTags.SLOT).assertDoesNotExist()
        onNodeWithTag(ComposerTestTags.INPUT).assertExists()
    }
}
