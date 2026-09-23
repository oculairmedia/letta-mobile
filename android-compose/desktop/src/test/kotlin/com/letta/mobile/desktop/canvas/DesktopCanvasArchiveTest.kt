@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.desktop.DesktopAgentSidebarActions
import com.letta.mobile.desktop.DesktopAgentSidebarState
import com.letta.mobile.desktop.DesktopDestination
import com.letta.mobile.desktop.SidebarConversationList
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Canvases are archived like conversations: set aside, kept, restorable. */
class DesktopCanvasArchiveTest {

    @Test
    fun theArchiveIsKeptBesideTheCanvasesAndIsNotOneOfThem() = runBlocking {
        val dir = Files.createTempDirectory("canvas-archive")
        val documents = DesktopCanvasDocumentStore(dir)
        documents.upsert(CanvasDocument(id = CanvasId("one"), title = "One"))
        DesktopCanvasArchiveStore(dir).setArchived(CanvasId("one"), true)

        // A fresh instance reads it back: the archive survives a restart.
        assertEquals(setOf(CanvasId("one")), DesktopCanvasArchiveStore(dir).archivedIds())
        // And the document store still lists exactly the canvases.
        assertEquals(listOf("one"), documents.listAll().map { it.id.value })

        DesktopCanvasArchiveStore(dir).setArchived(CanvasId("one"), false)
        assertTrue(DesktopCanvasArchiveStore(dir).archivedIds().isEmpty())
    }

    @Test
    fun hoveringACanvasIconOffersArchive() = runComposeUiTest {
        val toggled = mutableListOf<Pair<CanvasId, Boolean>>()
        val canvases = listOf(CanvasDocument(id = CanvasId("a"), title = "Roadmap"), CanvasDocument(id = CanvasId("b"), title = "Old board"))
        setContent {
            Column {
                SidebarConversationList(
                    state = sidebarState(canvases, archived = setOf(CanvasId("b"))),
                    actions = sidebarActions { id, archived -> toggled += id to archived },
                )
            }
        }

        onNodeWithText("Roadmap").performMouseInput { moveTo(center) }
        onNodeWithContentDescription("Archive canvas").performClick()
        onNodeWithText("Old board").performMouseInput { moveTo(center) }
        onNodeWithContentDescription("Restore canvas").performClick()
        assertEquals(listOf(CanvasId("a") to true, CanvasId("b") to false), toggled)
    }

    private fun sidebarState(canvases: List<CanvasDocument>, archived: Set<CanvasId>) = DesktopAgentSidebarState(
        agentName = "Agent",
        agentOrbIndex = 0,
        conversations = emptyList(),
        selectedConversationId = null,
        thinkingConversationId = null,
        archiveFilter = ConversationArchiveFilter.All,
        selectedDestination = DesktopDestination.Home,
        mode = com.letta.mobile.data.lens.WorkPlayMode.Work,
        canvases = canvases,
        archivedCanvasIds = archived,
    )

    private fun sidebarActions(onArchiveCanvas: (CanvasId, Boolean) -> Unit) = DesktopAgentSidebarActions(
        onArchiveFilterChange = {},
        onArchiveConversation = { _, _ -> },
        onModeChange = {},
        onDestinationSelected = {},
        onConversationSelected = {},
        onDeleteConversation = {},
        onNewChat = {},
        onEditAgent = {},
        onArchiveCanvas = onArchiveCanvas,
    )
}
