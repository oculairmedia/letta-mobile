@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.NotebookCanvasDocumentStore
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.canvas.notes.LocalNotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookCanvasIdentity
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.ui.canvas.boards.ProvideCanvasHostWithBoards
import com.letta.mobile.ui.canvas.canvasViewerOptionTag
import com.letta.mobile.ui.canvas.notes.NOTEBOOK_NOTE_VIEWER_ID
import com.letta.mobile.ui.canvas.notes.notebookCanvasViewers
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellWorkspaceNoteModuleTest {
    @Test
    fun boardAndShellNoteShareTheDraftAndOneSaveAfterEitherCloses() {
        val path = Files.createTempDirectory("shell-note-")
        try {
            NotebookLocalStore(path, "shell-note-peer").use { notebooks ->
                val store = NotebookCanvasDocumentStore(notebooks)
                val id = CanvasId("shell-note-board")
                runBlocking { CanvasSession.create(store, CanvasCreateOptions(canvasId = id)) }
                val live = CanvasSessionRegistry()
                val boards = CanvasBoardSessions(store, live = live)
                val viewers = notebookCanvasViewers(NotebookCanvasIdentity(notebooks), LocalNotebookNoteBackend(notebooks))
                val showBoard = mutableStateOf(true)
                val showNote = mutableStateOf(true)
                runDesktopComposeUiTest(width = 1800, height = 800) {
                    setContent {
                        val layout = ShellLayoutConfig(version = ShellLayoutConfig.VERSION, lanes = mapOf(
                            ShellLanes.MAIN to listOf(ModuleSlot(ShellViews.PAGE)) +
                                if (showBoard.value) listOf(ModuleSlot(ShellViews.CANVAS, id.value)) else emptyList(),
                            ShellLanes.SIDE to if (showNote.value) {
                                listOf(ModuleSlot(RuntimeViewId("shell.note"), id.value))
                            } else emptyList(),
                        ))
                        ProvideCanvasHostWithBoards(live, viewers, boards) {
                            LettaShellWorkspace(
                                LettaShellWorkspaceState(
                                    header = ShellWorkspaceHeaderState(title = "Notes"),
                                    navigation = navigation(),
                                    layout = ShellWorkspaceLayout(lanes = layout),
                                ),
                                host(),
                                Modifier,
                            )
                        }
                    }
                    onNodeWithTag(canvasViewerOptionTag(NOTEBOOK_NOTE_VIEWER_ID)).performClick()
                    waitUntil(timeoutMillis = Timeout) { onAllNodesWithTag(Markdown).fetchSemanticsNodes().size == 2 }
                    onAllNodesWithTag(Markdown)[1].performTextReplacement("one retained draft")
                    onAllNodesWithTag(Markdown)[0].assertTextContains("one retained draft")
                    runOnIdle {
                        assertTrue(live.get(id) != null)
                        assertEquals(1, boards.holds(id), "note view does not acquire a redundant board session")
                        showBoard.value = false
                    }
                    onNodeWithTag(Markdown).assertTextContains("one retained draft")
                    runOnIdle { showNote.value = false }
                    assertTrue(runBlocking { viewers.prepareClose() })
                    val notebook = runBlocking { NotebookCanvasIdentity(notebooks).notebookOf(id) }!!
                    val saved = runBlocking { LocalNotebookNoteBackend(notebooks).load(notebook) }
                    val found = saved as com.letta.mobile.data.canvas.notes.NotebookNoteLoad.Found
                    assertEquals("one retained draft", found.snapshot.markdown)
                }
            }
        } finally {
            path.toFile().deleteRecursively()
        }
    }

    private fun navigation() = ShellWorkspaceNavigation(
        rail = com.letta.mobile.ui.shell.rail.ShellLibraryRailState(
            agents = emptyList(), focus = com.letta.mobile.ui.shell.rail.ShellRailFocus(),
        ),
        panel = com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState(
            agent = com.letta.mobile.ui.shell.sidebar.ShellPanelAgent(name = "Ada", agentId = "agent-a"),
        ),
    )

    private fun host() = LettaShellWorkspaceHost(
        slots = LettaShellWorkspaceSlots(main = { Box(it) }, agentCard = {}, sidePane = {}, overlays = {}),
        actions = ShellWorkspaceNavigationActions(),
        chrome = ShellWindowChrome.Hoisted {},
        drawer = ShellWorkspaceDrawer(), events = ShellWorkspaceEvents(),
    )

    private companion object {
        const val Markdown = "notebook-note-markdown"
        const val Timeout = 10_000L
    }
}
