@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.transport.appserver.AppServerBackgroundProcess
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.MascotSeat
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The background-process shelf sits one small gap ([LettaDimens.Space.sm]) above the prompt, the
 * spacing between every stacked composer element, on the pointer card and the Touch bar at every
 * review width.
 */
class ComposerShelfGapUiTest {
    private val composer = ChatComposerUiState(
        text = "",
        permissionMode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted),
        backgroundProcesses = persistentListOf(
            AppServerBackgroundProcess("task-1", type = "agent_task", description = "Render a clip"),
        ),
    )

    /**
     * With [mascotAway] the agent has a mascot whose character stands elsewhere (the agent pane), so the
     * companion slot beside the card is closed: its seat must not make the card's row taller.
     */
    private fun shelfSitsOneGapAbove(width: Dp, style: LettaInput, mascotAway: Boolean = false) = runComposeUiTest {
        val shell = FakeMascotShell(AGENT)
        setContent {
            shell.Provide {
                MaterialTheme {
                    CompositionLocalProvider(LocalLettaHost provides LettaHost(input = style)) {
                        if (mascotAway) MascotSeat(AGENT, MascotStage.AGENT_PANE_HERO, size = 48.dp, empty = {})
                        Box(Modifier.width(width)) {
                            ChatComposerPanel(
                                ComposerInputs(
                                    composer = composer,
                                    uiState = ChatUiState(agentId = AGENT.takeIf { mascotAway }),
                                    actions = RecordingChatActions(),
                                ),
                            )
                        }
                    }
                }
            }
        }
        if (mascotAway) shell.transport.transportTo(AGENT, MascotStage.AGENT_PANE_HERO)
        waitForIdle()
        if (mascotAway) assertEquals(MascotStage.AGENT_PANE_HERO, shell.transport.activeStage(AGENT))
        val shelf = bounds(SHELF)
        val prompt = bounds(if (style == LettaInput.Touch) ComposerTestTags.TOUCH_BAR else ComposerTestTags.CARD)
        val gap = prompt.top - shelf.bottom
        val want = LettaDimens.Space.sm
        assertTrue(abs((gap - want).value) < 0.5f, "shelf-to-prompt gap at $width ($style) was $gap, want $want")
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    @Test
    fun pointerCardAt760() = shelfSitsOneGapAbove(760.dp, LettaInput.Pointer)

    @Test
    fun pointerCardAt1200() = shelfSitsOneGapAbove(1200.dp, LettaInput.Pointer)

    @Test
    fun pointerCardWithTheCompanionAwayAt1200() = shelfSitsOneGapAbove(1200.dp, LettaInput.Pointer, mascotAway = true)

    @Test
    fun touchBarAt360() = shelfSitsOneGapAbove(360.dp, LettaInput.Touch)

    @Test
    fun touchBarAt700() = shelfSitsOneGapAbove(700.dp, LettaInput.Touch)

    private companion object {
        const val SHELF = "background_process_shelf"
        const val AGENT = "agent-1"
    }
}
