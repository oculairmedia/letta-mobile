package com.letta.mobile.data.canvas.workspace

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteBackend
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.notes.NotebookNoteSession
import com.letta.mobile.data.canvas.notes.NotebookNoteSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared-notes browser over a host that decides every call: the listing grants nothing, an open
 * rechecks the grant, an edit reaches the host's note, and a grant withdrawn while the note is open
 * marks it and still lets the person leave.
 */
class SharedNoteBrowserTest {
    private val host = BrowserHost()
    private val browser = SharedNoteBrowser(host)

    @Test
    fun aHostSharingNothingListsAnEmptyListNotAFailure() = runTest {
        host.granted.clear()
        browser.refresh()
        assertEquals(SharedNoteListing.Listed(emptyList()), browser.state.value.listing)
        assertFalse(browser.state.value.loading)
    }

    @Test
    fun anUnreachableHostIsUnavailableAndAThrowingDirectoryIsToo() = runTest {
        host.reachable = false
        browser.refresh()
        assertIs<SharedNoteListing.Unavailable>(browser.state.value.listing)

        val throwing = SharedNoteBrowser(object : SharedNotebooks {
            override val directory = SharedNoteDirectory { error("socket closed") }
            override val opener = host.opener
        })
        throwing.refresh()
        assertEquals(SharedNoteListing.Unavailable("socket closed"), throwing.state.value.listing)
    }

    @Test
    fun openingAListedNoteMountsItAndAnEditReachesTheHost() = runTest {
        browser.refresh()
        val listed = assertIs<SharedNoteListing.Listed>(browser.state.value.listing).notes.single()

        assertTrue(browser.open(listed.id))
        val note = assertNotNull(browser.state.value.workspace?.state?.value?.current).note
        note.edit("edited")
        assertTrue(note.save())

        assertEquals("edited", host.markdown.getValue(listed.id))
        assertNull(browser.state.value.openProblem)
    }

    @Test
    fun aNoteRevokedAfterListingDoesNotOpenAndSaysWhy() = runTest {
        browser.refresh()
        host.granted.clear()

        assertFalse(browser.open(NOTE))
        assertNull(browser.state.value.workspace)
        assertEquals(DocumentWorkspaceProblem.DENIED, browser.state.value.openProblem)

        host.granted += NOTE
        host.reachable = false
        assertFalse(browser.open(NOTE))
        assertEquals(DocumentWorkspaceProblem.UNAVAILABLE, browser.state.value.openProblem)
    }

    @Test
    fun aGrantWithdrawnWhileOpenIsMarkedAndTheNoteCanStillBeLeft() = runTest {
        assertTrue(browser.open(NOTE))
        val workspace = assertNotNull(browser.state.value.workspace)
        val note = assertNotNull(workspace.state.value.current).note
        note.edit("unsaved after revoke")

        host.granted.clear()
        assertFalse(note.save())
        assertTrue(workspace.withdrawn())
        assertFalse(workspace.close(), "the durability barrier alone cannot leave an unsavable draft")

        assertTrue(browser.closeNote())
        assertNull(browser.state.value.workspace)
        assertEquals(SharedNoteListing.Listed(emptyList()), browser.state.value.listing, "leaving lists again")
        assertEquals("shared text", host.markdown.getValue(NOTE), "nothing reached the host after the revoke")
    }

    @Test
    fun closingADirtyNoteTheHostStillSharesSavesItFirst() = runTest {
        assertTrue(browser.open(NOTE))
        val note = assertNotNull(browser.state.value.workspace?.state?.value?.current).note
        note.edit("saved on the way out")

        assertTrue(browser.closeNote())
        assertEquals("saved on the way out", host.markdown.getValue(NOTE))
        assertNull(browser.state.value.workspace)
    }

    @Test
    fun theWorkspacesOwnCloseReturnsToTheList() = runTest {
        assertTrue(browser.open(NOTE))
        val workspace = assertNotNull(browser.state.value.workspace)
        browser.workspaceEmptied()
        assertNotNull(browser.state.value.workspace, "a mounted note is not emptied")

        assertTrue(workspace.close())
        browser.workspaceEmptied()
        assertNull(browser.state.value.workspace)
        assertIs<SharedNoteListing.Listed>(browser.state.value.listing)
    }

    private companion object {
        val NOTE = NotebookDocumentId("0123456789abcdef0123456789abcdef")
    }

    /** A host holding one note; [granted] and [reachable] decide every call, as the real host does. */
    private class BrowserHost : SharedNotebooks, NotebookNoteBackend {
        val markdown = mutableMapOf(NOTE to "shared text")
        val granted = mutableSetOf(NOTE)
        var reachable = true

        override val directory = SharedNoteDirectory {
            if (reachable) SharedNoteListing.Listed(granted.map { SharedNote(it, "Plan") }) else SharedNoteListing.Unavailable("down")
        }

        override val opener = DocumentWorkspaceOpener { id ->
            when (load(id)) {
                is NotebookNoteLoad.Found -> DocumentWorkspaceOpen.Opened(DocumentWorkspaceSessions(NotebookNoteSession(id, this), null))
                NotebookNoteLoad.Denied -> DocumentWorkspaceOpen.Denied
                else -> DocumentWorkspaceOpen.Unavailable
            }
        }

        override suspend fun load(id: NotebookDocumentId): NotebookNoteLoad = when {
            !reachable -> NotebookNoteLoad.Unavailable("down")
            id !in granted -> NotebookNoteLoad.Denied
            else -> markdown[id]?.let { NotebookNoteLoad.Found(NotebookNoteSnapshot(id, "Plan", it)) } ?: NotebookNoteLoad.NotFound
        }

        override suspend fun save(id: NotebookDocumentId, expected: String, replacement: String): NotebookNoteSave {
            val current = when (val loaded = load(id)) {
                is NotebookNoteLoad.Found -> loaded.snapshot
                NotebookNoteLoad.Denied -> return NotebookNoteSave.Denied
                else -> return NotebookNoteSave.Unavailable("down")
            }
            if (current.markdown != expected) return NotebookNoteSave.Conflict(current)
            markdown[id] = replacement
            return NotebookNoteSave.Saved(current.copy(markdown = replacement))
        }

        override suspend fun flush(applied: NotebookNoteSnapshot): NotebookNoteSave =
            if (applied.id in granted) NotebookNoteSave.Saved(applied) else NotebookNoteSave.Denied
    }
}
