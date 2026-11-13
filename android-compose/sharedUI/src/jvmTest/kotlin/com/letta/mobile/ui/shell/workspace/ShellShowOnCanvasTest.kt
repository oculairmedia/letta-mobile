@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.mount.client.CanvasMountProblem
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasPicker
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasResolver
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasTarget
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.canvas.mount.ProvideConversationCanvasPicker
import com.letta.mobile.ui.canvas.mount.TestHostMounts
import com.letta.mobile.ui.chat.surface.LocalChatCanvasBringToBoard
import com.letta.mobile.ui.host.LocalOverlayHost
import com.letta.mobile.ui.host.OverlayHost
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A chat card's "Show on canvas" for an artifact on another canvas switches the conversation's
 * board to it through the side menu's pick, then frames; on the board already on show it only
 * frames; a refused switch is told and frames nothing.
 */
class ShellShowOnCanvasTest {
    private val target = ConversationCanvasTarget(AgentId("agent-1"), ConversationId("conv-1"))
    private val boardB = CanvasId("canvas-b")

    private class Seen {
        var bringToBoard: ((CanvasId, () -> Unit) -> Unit)? = null
        var framed = 0
        var mounted = 0
        val opened = mutableListOf<CanvasId>()
    }

    @Composable
    private fun Window(picker: ConversationCanvasPicker, seen: Seen, overlays: OverlayHost) {
        val panel = ShellAgentPanelActions(onOpenCanvas = { seen.opened += it }, onCanvasMounted = { seen.mounted++ })
        MaterialTheme {
            CompositionLocalProvider(LocalOverlayHost provides overlays) {
                ProvideConversationCanvasPicker(picker) {
                    ProvideShowOnCanvas(panel) {
                        val bring = LocalChatCanvasBringToBoard.current
                        SideEffect { seen.bringToBoard = bring }
                    }
                }
            }
        }
    }

    private fun picker(mounts: TestHostMounts) = ConversationCanvasPicker(ConversationCanvasResolver(mounts)).apply { focus.enter(target) }

    @Test
    fun anArtifactOnAnotherCanvasSwitchesTheBoardThenFrames() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true)
        val seen = Seen()
        setContent { Window(picker(mounts), seen, OverlayHost()) }
        waitForIdle()

        runOnIdle { assertNotNull(seen.bringToBoard)(boardB) { seen.framed++ } }
        waitForIdle()

        assertEquals(boardB, mounts.stored[target])
        assertEquals(1, seen.mounted)
        assertEquals(1, seen.framed)
        assertTrue(seen.opened.isEmpty(), "canvas mode swaps the board; no pane opens")
    }

    @Test
    fun anArtifactOnTheBoardOnShowOnlyFrames() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true).apply { stored[target] = boardB }
        val seen = Seen()
        setContent { Window(picker(mounts), seen, OverlayHost()) }
        waitForIdle()

        runOnIdle { assertNotNull(seen.bringToBoard)(boardB) { seen.framed++ } }
        waitForIdle()

        assertEquals(1, seen.framed)
        assertEquals(0, seen.mounted, "no switch was made")
        assertEquals(boardB, mounts.stored[target])
    }

    @Test
    fun aRefusedSwitchIsToldAndFramesNothing() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true).apply { refusal = CanvasMountProblem.Denied }
        val seen = Seen()
        val overlays = OverlayHost()
        setContent { Window(picker(mounts), seen, overlays) }
        waitForIdle()

        runOnIdle { assertNotNull(seen.bringToBoard)(boardB) { seen.framed++ } }
        waitForIdle()

        assertEquals(0, seen.framed)
        assertNull(mounts.stored[target])
        assertNotNull(overlays.snackbar)
    }
}
