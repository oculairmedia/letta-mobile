package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.model.CanvasId
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

    // Hovering a canvas row to archive it is the shared panel's (ShellAgentPanelTest).
}
