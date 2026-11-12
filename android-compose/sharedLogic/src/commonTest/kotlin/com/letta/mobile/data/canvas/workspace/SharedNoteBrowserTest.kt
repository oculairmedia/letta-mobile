package com.letta.mobile.data.canvas.workspace

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.notes.NotebookNoteSession
import com.letta.mobile.data.canvas.notes.NoteTestBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
        host.revoke()
        browser.refresh()
        assertEquals(SharedNoteListing.Listed(emptyList()), browser.state.value.listing)
        assertFalse(browser.state.value.loading)
    }

    @Test
    fun anUnreachableHostIsUnavailableAndAThrowingDirectoryIsToo() = runTest {
        host.unreach()
        browser.refresh()
        assertIs<SharedNoteListing.Unavailable>(browser.state.value.listing)

        val throwing = SharedNoteBrowser(object : SharedNotebooks {
            override val directory = SharedNoteDirectory { _ -> error("socket closed") }
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

        assertEquals("edited", host.backend.source.markdown)
        assertNull(browser.state.value.openProblem)
    }

    @Test
    fun aNoteRevokedAfterListingDoesNotOpenAndSaysWhy() = runTest {
        browser.refresh()
        host.revoke()

        assertFalse(browser.open(NOTE))
        assertNull(browser.state.value.workspace)
        assertEquals(DocumentWorkspaceProblem.DENIED, browser.state.value.openProblem)

        host.unreach()
        assertFalse(browser.open(NOTE))
        assertEquals(DocumentWorkspaceProblem.UNAVAILABLE, browser.state.value.openProblem)
    }

    @Test
    fun aGrantWithdrawnWhileOpenIsMarkedAndTheNoteCanStillBeLeft() = runTest {
        assertTrue(browser.open(NOTE))
        val workspace = assertNotNull(browser.state.value.workspace)
        val note = assertNotNull(workspace.state.value.current).note
        note.edit("unsaved after revoke")

        host.revoke()
        assertFalse(note.save())
        assertTrue(workspace.withdrawn())
        assertFalse(workspace.close(), "the durability barrier alone cannot leave an unsavable draft")

        assertTrue(browser.closeNote())
        assertNull(browser.state.value.workspace)
        assertEquals(SharedNoteListing.Listed(emptyList()), browser.state.value.listing, "leaving lists again")
        assertEquals("shared text", host.backend.source.markdown, "nothing reached the host after the revoke")
    }

    @Test
    fun closingADirtyNoteTheHostStillSharesSavesItFirst() = runTest {
        assertTrue(browser.open(NOTE))
        val note = assertNotNull(browser.state.value.workspace?.state?.value?.current).note
        note.edit("saved on the way out")

        assertTrue(browser.closeNote())
        assertEquals("saved on the way out", host.backend.source.markdown)
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

    @Test
    fun overlappingRefreshesShowTheLatestAnswerAndStayLoadingUntilAllAnswer() = runTest {
        val gated = GatedDirectory()
        val overlapping = SharedNoteBrowser(object : SharedNotebooks {
            override val directory = gated
            override val opener = host.opener
        })
        val older = launch { overlapping.refresh() }
        runCurrent()
        val newer = launch { overlapping.refresh() }
        runCurrent()

        gated.answer(1, SharedNoteListing.Listed(emptyList()))
        runCurrent()
        assertEquals(SharedNoteListing.Listed(emptyList()), overlapping.state.value.listing, "the newer answer shows")
        assertTrue(overlapping.state.value.loading, "the older refresh is still waiting")

        gated.answer(0, SharedNoteListing.Listed(listOf(SharedNote(NOTE, "Plan"))))
        runCurrent()
        assertEquals(SharedNoteListing.Listed(emptyList()), overlapping.state.value.listing, "a stale answer arriving last is dropped")
        assertFalse(overlapping.state.value.loading)
        older.join()
        newer.join()

        val first = launch { overlapping.refresh() }
        runCurrent()
        val second = launch { overlapping.refresh() }
        runCurrent()
        first.cancel()
        runCurrent()
        assertTrue(overlapping.state.value.loading, "a cancelled refresh does not end the one still waiting")
        gated.answer(3, SharedNoteListing.Listed(listOf(SharedNote(NOTE, "Plan"))))
        second.join()
        val latest = SharedNoteListing.Listed(listOf(SharedNote(NOTE, "Plan")))
        assertEquals(latest, overlapping.state.value.listing, "the latest one asked wins")
        assertFalse(overlapping.state.value.loading)
    }

    @Test
    fun showMoreAppendsTheNextPageAndRefreshStartsAgainFromTheFirst() = runTest {
        val paged = pagedBrowser(PagedDirectory())
        paged.refresh()
        assertEquals(SharedNoteListing.Listed(listOf(FIRST), CURSOR), paged.state.value.listing)

        assertTrue(paged.loadMore())
        assertEquals(SharedNoteListing.Listed(listOf(FIRST, SECOND)), paged.state.value.listing)
        assertFalse(paged.state.value.loadingMore)
        assertFalse(paged.loadMore(), "nothing is asked once the host says nothing is left")

        paged.refresh()
        assertEquals(SharedNoteListing.Listed(listOf(FIRST), CURSOR), paged.state.value.listing, "a refresh shows the first page only")
    }

    @Test
    fun aPageThatFailsKeepsWhatIsShownAndCanBeAskedAgain() = runTest {
        val directory = PagedDirectory().apply { nextPageFails = true }
        val paged = pagedBrowser(directory)
        paged.refresh()

        assertFalse(paged.loadMore())
        assertEquals(SharedNoteListing.Listed(listOf(FIRST), CURSOR), paged.state.value.listing, "what is shown stays")
        assertTrue(paged.state.value.moreFailed)

        directory.nextPageFails = false
        assertTrue(paged.loadMore())
        assertEquals(SharedNoteListing.Listed(listOf(FIRST, SECOND)), paged.state.value.listing)
        assertFalse(paged.state.value.moreFailed)
    }

    @Test
    fun aPageAnsweredForAnOutdatedRefreshIsDropped() = runTest {
        val gated = GatedDirectory()
        val paged = pagedBrowser(gated)
        val first = launch { paged.refresh() }
        runCurrent()
        gated.answer(0, SharedNoteListing.Listed(listOf(FIRST), CURSOR))
        first.join()

        val page = launch { paged.loadMore() }
        runCurrent()
        assertEquals(CURSOR, gated.asked[1], "the page starts where the shown listing stopped")
        assertTrue(paged.state.value.loadingMore)
        val refresh = launch { paged.refresh() }
        runCurrent()
        assertNull(gated.asked[2], "a refresh asks for the first page")
        gated.answer(2, SharedNoteListing.Listed(listOf(SECOND)))
        refresh.join()

        gated.answer(1, SharedNoteListing.Listed(listOf(FIRST)))
        page.join()
        assertEquals(SharedNoteListing.Listed(listOf(SECOND)), paged.state.value.listing, "the page for the older listing is dropped")
        assertFalse(paged.state.value.loadingMore)
        assertFalse(paged.state.value.moreFailed)
    }

    /** A refresh that publishes frees paging at once; the obsolete page neither appends nor clears the newer page's flag. */
    @Test
    fun aPublishedRefreshFreesPagingAndTheObsoletePageLeavesTheNewerOneAlone() = runTest {
        val gated = GatedDirectory()
        val paged = pagedBrowser(gated)
        val first = launch { paged.refresh() }
        runCurrent()
        gated.answer(0, SharedNoteListing.Listed(listOf(FIRST), CURSOR))
        first.join()
        val obsolete = async { paged.loadMore() }
        runCurrent()
        val refresh = launch { paged.refresh() }
        runCurrent()
        gated.answer(2, SharedNoteListing.Listed(listOf(FIRST), CURSOR))
        refresh.join()
        assertFalse(paged.state.value.loadingMore, "the published listing has no page in flight")

        val newer = async { paged.loadMore() }
        runCurrent()
        assertEquals(CURSOR, gated.asked[3], "the new listing can ask for its next page right away")
        gated.answer(1, SharedNoteListing.Listed(listOf(OBSOLETE)))
        assertFalse(obsolete.await())
        assertTrue(paged.state.value.loadingMore, "the obsolete page does not clear the newer page's flag")
        assertEquals(SharedNoteListing.Listed(listOf(FIRST), CURSOR), paged.state.value.listing, "nor does it append")

        gated.answer(3, SharedNoteListing.Listed(listOf(SECOND)))
        assertTrue(newer.await())
        assertEquals(SharedNoteListing.Listed(listOf(FIRST, SECOND)), paged.state.value.listing)
        assertFalse(paged.state.value.loadingMore)
    }

    /** A next page the host refuses replaces the listing: the earlier notes are not shown once this device is refused. */
    @Test
    fun aRefusedNextPageReplacesTheListingWithTheRefusal() = runTest {
        val gated = GatedDirectory()
        val paged = listedOnce(gated)
        val page = async { paged.loadMore() }
        runCurrent()
        gated.answer(1, SharedNoteListing.Denied)

        assertFalse(page.await())
        assertEquals(SharedNoteListing.Denied, paged.state.value.listing)
        assertFalse(paged.state.value.moreFailed, "a refusal is not a page to try again")
        assertFalse(paged.state.value.loadingMore)
        assertFalse(paged.loadMore(), "nothing is left to page through")
    }

    /** A cursor the host can no longer read is not retried: the browser lists again from the first page. */
    @Test
    fun aCursorTheHostCannotReadListsAgainFromTheFirstPage() = runTest {
        val gated = GatedDirectory()
        val paged = listedOnce(gated)
        val page = async { paged.loadMore() }
        runCurrent()
        gated.answer(1, SharedNoteListing.Unavailable(SharedNoteListing.Unavailable.INVALID_CURSOR))
        runCurrent()

        assertEquals(3, gated.asked.size)
        assertNull(gated.asked[2], "the browser asks for the first page again")
        assertTrue(paged.state.value.loading)
        assertFalse(paged.state.value.moreFailed, "the dead cursor is not offered for a retry")
        gated.answer(2, SharedNoteListing.Listed(listOf(FIRST, SECOND)))
        assertFalse(page.await())
        assertEquals(SharedNoteListing.Listed(listOf(FIRST, SECOND)), paged.state.value.listing)
        assertFalse(paged.state.value.loading)
    }

    /** A browser over [gated] showing [FIRST] with [CURSOR] (the directory's call 0). */
    private suspend fun TestScope.listedOnce(gated: GatedDirectory): SharedNoteBrowser {
        val paged = pagedBrowser(gated)
        val first = launch { paged.refresh() }
        runCurrent()
        gated.answer(0, SharedNoteListing.Listed(listOf(FIRST), CURSOR))
        first.join()
        return paged
    }

    private fun pagedBrowser(source: SharedNoteDirectory) = SharedNoteBrowser(object : SharedNotebooks {
        override val directory = source
        override val opener = host.opener
    })

    private companion object {
        val NOTE = NotebookDocumentId("0123456789abcdef0123456789abcdef")
        val FIRST = SharedNote(NOTE, "Plan")
        val SECOND = SharedNote(NotebookDocumentId("fedcba9876543210fedcba9876543210"), "Later")
        val CURSOR = SharedNotesCursor("after-first")
        val OBSOLETE = SharedNote(NotebookDocumentId("00000000000000000000000000000000"), "Gone")
    }

    /** Two pages: [FIRST] with a cursor, then [SECOND] with none; the second can be made to fail. */
    private class PagedDirectory : SharedNoteDirectory {
        var nextPageFails = false

        override suspend fun list(from: SharedNotesCursor?): SharedNoteListing = when {
            from == null -> SharedNoteListing.Listed(listOf(FIRST), CURSOR)
            nextPageFails -> SharedNoteListing.Unavailable("down")
            else -> SharedNoteListing.Listed(listOf(SECOND))
        }
    }

    /** A directory whose every call waits until the test answers it, by call number; [asked] records each call's cursor. */
    private class GatedDirectory : SharedNoteDirectory {
        // overflow: one gate per call in a single test; bounded by the calls the test makes.
        private val gates = mutableListOf<CompletableDeferred<SharedNoteListing>>()
        val asked = mutableListOf<SharedNotesCursor?>()

        override suspend fun list(from: SharedNotesCursor?): SharedNoteListing {
            asked += from
            return CompletableDeferred<SharedNoteListing>().also { gates += it }.await()
        }

        fun answer(call: Int, listing: SharedNoteListing) {
            gates[call].complete(listing)
        }
    }

    /** A host over one [backend] note; its knobs model a revoked grant or an unreachable host. */
    private class BrowserHost : SharedNotebooks {
        val backend = NoteTestBackend(NOTE).also { it.source = it.source.copy(title = "Plan", markdown = "shared text") }
        private var shared = listOf(SharedNote(NOTE, "Plan"))
        private var reachable = true

        override val directory = SharedNoteDirectory { _ ->
            if (reachable) SharedNoteListing.Listed(shared) else SharedNoteListing.Unavailable("down")
        }

        override val opener = DocumentWorkspaceOpener { id -> opened(id, backend.load(id)) }

        fun revoke() {
            shared = emptyList()
            backend.loadResult = NotebookNoteLoad.Denied
            backend.saveResult = NotebookNoteSave.Denied
        }

        fun unreach() {
            reachable = false
            backend.loadResult = NotebookNoteLoad.Unavailable("down")
            backend.saveResult = NotebookNoteSave.Unavailable("down")
        }

        private fun opened(id: NotebookDocumentId, loaded: NotebookNoteLoad): DocumentWorkspaceOpen = when (loaded) {
            is NotebookNoteLoad.Found -> DocumentWorkspaceOpen.Opened(DocumentWorkspaceSessions(NotebookNoteSession(id, backend), null))
            NotebookNoteLoad.Denied -> DocumentWorkspaceOpen.Denied
            else -> DocumentWorkspaceOpen.Unavailable
        }
    }
}
