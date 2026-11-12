@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.letta.mobile.data.canvas.mount.client.DeviceFallbackCanvasMountClient
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.canvas.mount.CloseCanvasPaneShownOnBoard
import com.letta.mobile.ui.canvas.mount.ConversationCanvasFrame
import com.letta.mobile.ui.canvas.mount.ProvideConversationCanvasPicker
import com.letta.mobile.ui.canvas.mount.TestHostMounts
import com.letta.mobile.ui.chat.surface.LocalChatCanvasOnBoard
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.host.LocalOverlayHost
import com.letta.mobile.ui.host.LocalPaneWindowClass
import com.letta.mobile.ui.host.OverlayHost
import com.letta.mobile.ui.host.PaneWindowClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The side menu's canvases. With a conversation on screen a pick mounts the canvas for it: in
 * canvas mode the board swaps in place and nothing opens; in chat mode it also opens as before (a
 * side pane), and canvas mode then closes that pane. "Open in side panel" opens a pane and leaves the
 * mount alone. A host without mounts behaves the same on screen, on this device only, and says so.
 */
class ShellCanvasPickTest {
    private val target = ConversationCanvasTarget(AgentId("agent-1"), ConversationId("conv-1"))
    private val boardA = CanvasId("canvas-a")
    private val boardB = CanvasId("canvas-b")
    private val state = ShellAgentPanelState(
        agent = ShellPanelAgent(name = "Meridian", agentId = "agent-1"),
        conversations = listOf(ShellConversationRowModel(id = "conv-1", title = "Planning", preview = "", timeLabel = "1m")),
        canvases = listOf(
            ShellCanvasRowModel(id = boardA, title = "Board A", timeLabel = "2m"),
            ShellCanvasRowModel(id = boardB, title = "Board B", timeLabel = "3h"),
        ),
    )

    /** What the host was asked to open, and the side pane it shows (a host's pane, in a test). */
    private class Opened {
        val canvases = mutableListOf<CanvasId>()
        val inPane = mutableListOf<CanvasId>()
        var mounted = 0
        var pane by mutableStateOf<CanvasId?>(null)
    }

    private fun actions(opened: Opened) = ShellAgentPanelActions(
        onOpenCanvas = {
            opened.canvases += it
            opened.pane = it
        },
        onOpenCanvasInPane = {
            opened.inPane += it
            opened.pane = it
        },
        onCanvasMounted = { opened.mounted++ },
        onNewCanvas = {},
    )

    /** A stand-in host: the row menu's entries as plain buttons under the row. */
    private val menuAsButtons = LettaHost(
        rowMenu = { items, content ->
            Column {
                content()
                items.forEach { Text("menu:${it.label}", Modifier.clickable(onClick = it.onClick)) }
            }
        },
    )

    @Composable
    private fun Panel(picker: ConversationCanvasPicker?, opened: Opened, overlays: OverlayHost = OverlayHost()) {
        MaterialTheme {
            CompositionLocalProvider(LocalOverlayHost provides overlays, LocalPaneWindowClass provides PaneWindowClass.Wide) {
                ProvideConversationCanvasPicker(picker) {
                    ShellAgentPanel(state, actions(opened), Modifier.width(320.dp).height(720.dp))
                }
            }
        }
    }

    /** The panel beside the conversation's page (its board seat in [onBoard] mode) and the host's pane. */
    @Composable
    private fun Window(picker: ConversationCanvasPicker, opened: Opened, onBoard: Boolean) {
        MaterialTheme {
            CompositionLocalProvider(LocalOverlayHost provides OverlayHost()) {
                ProvideConversationCanvasPicker(picker) {
                    Row {
                        ShellAgentPanel(state, actions(opened), Modifier.width(320.dp).height(720.dp))
                        CompositionLocalProvider(LocalChatCanvasOnBoard provides onBoard) {
                            ConversationCanvasFrame(target) { shown -> Text("board:" + (shown?.value ?: OWN)) }
                        }
                        CloseCanvasPaneShownOnBoard(opened.pane) { opened.pane = null }
                        opened.pane?.let { Text("pane:${it.value}") }
                    }
                }
            }
        }
    }

    private fun rowTag(id: CanvasId): String = "${ShellAgentPanelTags.LIBRARY}-row-canvases:canvas-${id.value}"

    private fun picker(client: CanvasMountClient) = ConversationCanvasPicker(ConversationCanvasResolver(client))

    private fun ComposeUiTest.pickB() {
        onNodeWithText("Board B").performClick()
        waitForIdle()
    }

    @Test
    fun aPickInCanvasModeMountsItInPlaceAndOpensNoPane() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true)
        val picker = picker(mounts).apply { focus.enter(target) }
        val opened = Opened()
        setContent { Panel(picker, opened) }

        pickB()

        assertEquals(boardB, mounts.stored[target])
        assertTrue(opened.canvases.isEmpty())
        assertNull(opened.pane)
        assertEquals(1, opened.mounted)
        onNodeWithTag(rowTag(boardB)).assertIsSelected()
        onNodeWithTag(rowTag(boardA)).assertIsNotSelected()
        onNodeWithTag(ShellCanvasTags.USE_OWN).assertExists()
    }

    @Test
    fun aPickInChatModeOpensAPaneAndMountsAndCanvasModeThenClosesThePane() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true)
        val picker = picker(mounts)
        val opened = Opened()
        var onBoard by mutableStateOf(false)
        setContent { Window(picker, opened, onBoard) }
        waitForIdle()

        pickB()

        assertEquals(boardB, mounts.stored[target])
        assertEquals(listOf(boardB), opened.canvases)
        onNodeWithText("pane:${boardB.value}").assertExists()
        onNodeWithTag(rowTag(boardB)).assertIsSelected()

        onBoard = true
        waitForIdle()

        onNodeWithText("pane:${boardB.value}").assertDoesNotExist()
        onNodeWithText("board:${boardB.value}").assertExists()
        assertEquals(boardB, mounts.stored[target])
    }

    @Test
    fun withNoConversationOnScreenAPickOnlyOpens() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true)
        val opened = Opened()
        setContent { Panel(picker(mounts), opened) }

        pickB()

        assertEquals(listOf(boardB), opened.canvases)
        assertTrue(mounts.stored.isEmpty())
        onNodeWithTag(ShellCanvasTags.USE_OWN).assertDoesNotExist()
    }

    @Test
    fun openInSidePanelOpensAPaneInCanvasModeAndLeavesTheMount() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true).apply { stored[target] = boardB }
        val picker = picker(mounts).apply { focus.enter(target) }
        val opened = Opened()
        setContent { CompositionLocalProvider(LocalLettaHost provides menuAsButtons) { Panel(picker, opened) } }
        waitForIdle()

        // Only Board A offers it: Board B is the board on show behind the chat.
        onNodeWithText("menu:Open in side panel").performClick()
        waitForIdle()

        assertEquals(listOf(boardA), opened.inPane)
        assertEquals(boardA, opened.pane)
        assertTrue(opened.canvases.isEmpty())
        assertEquals(boardB, mounts.stored[target])
        onNodeWithTag(rowTag(boardB)).assertIsSelected()
    }

    @Test
    fun openInSidePanelIsNotOfferedWithoutASidePane() = runComposeUiTest {
        val picker = picker(TestHostMounts()).apply { focus.enter(target) }
        setContent {
            CompositionLocalProvider(LocalLettaHost provides menuAsButtons) {
                CompositionLocalProvider(LocalPaneWindowClass provides PaneWindowClass.Compact) {
                    ProvideConversationCanvasPicker(picker) {
                        ShellAgentPanel(state, actions(Opened()), Modifier.width(320.dp).height(720.dp))
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithText("menu:Open in side panel").assertDoesNotExist()
    }

    @Test
    fun theWayBackPutsTheConversationOnItsOwnBoardInEitherMode() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true).apply { stored[target] = boardB }
        val picker = picker(mounts).apply { focus.show(target) }
        setContent { Panel(picker, Opened()) }
        waitForIdle()

        // Chat mode.
        onNodeWithTag(ShellCanvasTags.USE_OWN).performClick()
        waitForIdle()
        assertEquals(target.ownCanvas, mounts.stored[target])
        onNodeWithTag(ShellCanvasTags.USE_OWN).assertDoesNotExist()
        onNodeWithTag(rowTag(boardB)).assertIsNotSelected()

        // Canvas mode.
        picker.focus.enter(target)
        pickB()
        onNodeWithTag(ShellCanvasTags.USE_OWN).performClick()
        waitForIdle()
        assertEquals(target.ownCanvas, mounts.stored[target])
        onNodeWithTag(ShellCanvasTags.USE_OWN).assertDoesNotExist()
    }

    @Test
    fun aRefusedPickIsToldInPlainWordsAndTheBoardStays() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true).apply { refusal = CanvasMountProblem.Denied }
        val picker = picker(mounts).apply { focus.enter(target) }
        val overlays = OverlayHost()
        setContent { Panel(picker, Opened(), overlays) }

        pickB()

        assertNull(mounts.stored[target])
        val message = assertNotNull(overlays.snackbar).overlay.message
        assertTrue(message.contains("switch"), message)
        onNodeWithTag(rowTag(boardB)).assertIsNotSelected()
    }

    @Test
    fun aHostWithoutMountsMountsOnThisDeviceAndSaysSo() = runComposeUiTest {
        val picker = picker(DeviceFallbackCanvasMountClient(CanvasMountClient.Unsupported)).apply { focus.enter(target) }
        val opened = Opened()
        setContent { Panel(picker, opened) }

        pickB()

        assertTrue(opened.canvases.isEmpty())
        onNodeWithTag(rowTag(boardB)).assertIsSelected()
        onNodeWithText("This device only; agent keeps its own canvas").assertExists()
        onNodeWithTag(ShellCanvasTags.USE_OWN).performClick()
        waitForIdle()
        onNodeWithText("This device only; agent keeps its own canvas").assertDoesNotExist()
    }

    @Test
    fun aHostWithMountsShowsNoDeviceHint() = runComposeUiTest {
        val mounts = TestHostMounts(supported = true).apply { stored[target] = boardB }
        val picker = picker(DeviceFallbackCanvasMountClient(mounts)).apply { focus.enter(target) }
        setContent { Panel(picker, Opened()) }
        waitForIdle()

        onNodeWithText("On this conversation’s board").assertExists()
        onNodeWithText("This device only; agent keeps its own canvas").assertDoesNotExist()
    }

    @Test
    fun theCanvasesHeaderOffersANewCanvasAndTheChatsAJump() = runComposeUiTest {
        setContent { Panel(null, Opened()) }
        onNodeWithTag(ShellCanvasTags.NEW).assertExists()
        onNodeWithTag(ShellCanvasTags.JUMP).performClick()
        waitForIdle()
        onNodeWithText("Board B").assertExists()
    }

    private companion object {
        const val OWN = "own"
    }
}
