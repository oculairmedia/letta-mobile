@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.mount.client.CanvasMountClient
import com.letta.mobile.data.canvas.mount.client.CanvasMountProblem
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasPicker
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasResolver
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasTarget
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.canvas.mount.ProvideConversationCanvasPicker
import com.letta.mobile.ui.canvas.mount.TestHostMounts
import com.letta.mobile.ui.host.LocalOverlayHost
import com.letta.mobile.ui.host.OverlayHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The side menu's canvases: in canvas mode a pick mounts the canvas for the conversation on screen. */
class ShellCanvasPickTest {
    private val target = ConversationCanvasTarget(AgentId("agent-1"), ConversationId("conv-1"))
    private val boardB = CanvasId("canvas-b")
    private val state = ShellAgentPanelState(
        agent = ShellPanelAgent(name = "Meridian", agentId = "agent-1"),
        conversations = listOf(ShellConversationRowModel(id = "conv-1", title = "Planning", preview = "", timeLabel = "1m")),
        canvases = listOf(
            ShellCanvasRowModel(id = CanvasId("canvas-a"), title = "Board A", timeLabel = "2m"),
            ShellCanvasRowModel(id = boardB, title = "Board B", timeLabel = "3h"),
        ),
    )

    private class Opened {
        val canvases = mutableListOf<CanvasId>()
        var mounted = 0
    }

    private fun actions(opened: Opened) = ShellAgentPanelActions(
        onOpenCanvas = { opened.canvases += it },
        onCanvasMounted = { opened.mounted++ },
        onNewCanvas = {},
    )

    @Composable
    private fun Panel(picker: ConversationCanvasPicker?, opened: Opened, overlays: OverlayHost) {
        MaterialTheme {
            CompositionLocalProvider(LocalOverlayHost provides overlays) {
                ProvideConversationCanvasPicker(picker) {
                    ShellAgentPanel(state, actions(opened), Modifier.width(320.dp).height(720.dp))
                }
            }
        }
    }

    private fun rowTag(id: CanvasId): String = "${ShellAgentPanelTags.LIBRARY}-row-canvases:canvas-${id.value}"

    private fun ComposeUiTest.pickB() {
        onNodeWithText("Board B").performClick()
        waitForIdle()
    }

    @Test
    fun aPickInCanvasModeMountsItAndMarksItCurrent() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true)
        val picker = ConversationCanvasPicker(ConversationCanvasResolver(mounts)).apply { focus.enter(target) }
        val opened = Opened()
        setContent { Panel(picker, opened, OverlayHost()) }

        pickB()

        assertEquals(boardB, mounts.stored[target])
        assertTrue(opened.canvases.isEmpty())
        assertEquals(1, opened.mounted)
        onNodeWithTag(rowTag(boardB)).assertIsSelected()
        onNodeWithTag(rowTag(CanvasId("canvas-a"))).assertIsNotSelected()
        onNodeWithTag(ShellCanvasTags.USE_OWN).assertExists()
    }

    @Test
    fun aPickOutsideCanvasModeOpensAsBefore() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true)
        val opened = Opened()
        setContent { Panel(ConversationCanvasPicker(ConversationCanvasResolver(mounts)), opened, OverlayHost()) }

        pickB()

        assertEquals(listOf(boardB), opened.canvases)
        assertTrue(mounts.stored.isEmpty())
        onNodeWithTag(ShellCanvasTags.USE_OWN).assertDoesNotExist()
    }

    @Test
    fun theWayBackPutsTheConversationOnItsOwnBoard() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true).apply { stored[target] = boardB }
        val picker = ConversationCanvasPicker(ConversationCanvasResolver(mounts)).apply { focus.enter(target) }
        setContent { Panel(picker, Opened(), OverlayHost()) }
        waitForIdle()

        onNodeWithTag(ShellCanvasTags.USE_OWN).performClick()
        waitForIdle()

        assertEquals(target.ownCanvas, mounts.stored[target])
        onNodeWithTag(ShellCanvasTags.USE_OWN).assertDoesNotExist()
        onNodeWithTag(rowTag(boardB)).assertIsNotSelected()
    }

    @Test
    fun aRefusedPickIsToldInPlainWordsAndTheBoardStays() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true).apply { refusal = CanvasMountProblem.Denied }
        val picker = ConversationCanvasPicker(ConversationCanvasResolver(mounts)).apply { focus.enter(target) }
        val overlays = OverlayHost()
        setContent { Panel(picker, Opened(), overlays) }

        pickB()

        assertNull(mounts.stored[target])
        val message = assertNotNull(overlays.snackbar).overlay.message
        assertTrue(message.contains("switch"), message)
        onNodeWithTag(rowTag(boardB)).assertIsNotSelected()
    }

    @Test
    fun aHostWithoutMountsShowsNoMountAffordance() = runComposeUiTest {
        val picker = ConversationCanvasPicker(ConversationCanvasResolver(CanvasMountClient.Unsupported)).apply { focus.enter(target) }
        val opened = Opened()
        setContent { Panel(picker, opened, OverlayHost()) }

        pickB()

        assertEquals(listOf(boardB), opened.canvases)
        onNodeWithTag(ShellCanvasTags.USE_OWN).assertDoesNotExist()
        onNodeWithTag(rowTag(boardB)).assertIsNotSelected()
    }

    @Test
    fun theCanvasesHeaderOffersANewCanvasAndTheChatsAJump() = runComposeUiTest {
        setContent { Panel(null, Opened(), OverlayHost()) }
        onNodeWithTag(ShellCanvasTags.NEW).assertExists()
        onNodeWithTag(ShellCanvasTags.JUMP).performClick()
        waitForIdle()
        onNodeWithText("Board B").assertExists()
    }
}
