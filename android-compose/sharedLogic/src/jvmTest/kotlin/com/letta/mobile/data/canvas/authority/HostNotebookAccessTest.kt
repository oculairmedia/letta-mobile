package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookItem
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.NotebookHistoryArchive
import com.letta.mobile.data.canvas.NotebookPeerGrant
import com.letta.mobile.data.canvas.references.NotebookReferenceLoad
import com.letta.mobile.data.canvas.references.NotebookReferenceContent
import com.letta.mobile.data.canvas.references.NotebookReferenceSession
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceController
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceOpener
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceProblem
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceOpen
import com.letta.mobile.data.canvas.workspace.SharedNote
import com.letta.mobile.data.canvas.workspace.SharedNoteListing
import com.letta.mobile.data.canvas.workspace.SharedNotesCursor
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostNotebookAccessTest {
    @Test
    fun deniedRefreshDisablesSaveAndReauthorizationRetainsTheDraft() = runBlocking {
        assumeDurableGrantHost()
        val root = Files.createTempDirectory("notebook-denied-save")
        NotebookLocalStore(root.resolve("notebooks"), "host").use { notebooks ->
            val storageId = notebooks.create("Note")
            val id = requireNotNull(notebooks.read(storageId)).id
            val grants = FileNotebookGrantStore(root.resolve("grants.json"))
            val grant = NotebookPeerGrant("peer", mayRead = true, mayEdit = true)
            grants.provision(id, grant)
            val opened = HostNotebookAccess(notebooks, grants, "peer").opener.open(id)
            val session = assertIs<DocumentWorkspaceOpen.Opened>(opened).sessions.note
            assertTrue(session.refresh())
            session.edit("retained draft")
            assertTrue(session.state.value.canSave)
            grants.revoke("peer", id)
            assertFalse(session.refresh())
            assertTrue(session.state.value.denied)
            assertFalse(session.state.value.canSave, "Denied editor state must disable Save")
            assertEquals("retained draft", session.state.value.draft)
            assertEquals("", notebooks.read(storageId)?.markdown)
            grants.provision(id, grant)
            assertTrue(session.refresh())
            assertFalse(session.state.value.denied)
            assertTrue(session.state.value.canSave)
            assertEquals("retained draft", session.state.value.draft)
            assertTrue(session.save())
            assertEquals("retained draft", notebooks.read(storageId)?.markdown)
        }
    }

    @Test
    fun revocationBetweenOpenAndInitialReadRemainsDenial() = runBlocking {
        assumeDurableGrantHost()
        val root = Files.createTempDirectory("notebook-open-revocation")
        NotebookLocalStore(Files.createDirectory(root.resolve("notebooks")), "host").use { notebooks ->
            val id = requireNotNull(notebooks.read(notebooks.create("Note"))).id
            val grants = FileNotebookGrantStore(root.resolve("grants.json"))
            grants.provision(id, NotebookPeerGrant("peer", mayRead = true, mayEdit = true))
            val access = HostNotebookAccess(notebooks, grants, "peer")
            val workspace = DocumentWorkspaceController(DocumentWorkspaceOpener { target ->
                val opened = access.opener.open(target)
                grants.revoke("peer", target)
                opened
            })
            assertFalse(workspace.open(id))
            assertEquals(DocumentWorkspaceProblem.DENIED, workspace.state.value.problem)
        }
    }

    @Test
    fun nestedReferenceRequiresItsOwnCurrentGrant() = runBlocking {
        assumeDurableGrantHost()
        val root = Files.createTempDirectory("nested-notebook-grants")
        NotebookLocalStore(Files.createDirectory(root.resolve("notebooks")), "host").use { notebooks ->
            val parentStorage = notebooks.create("Parent")
            val childStorage = notebooks.create("Child")
            val parent = requireNotNull(notebooks.read(parentStorage)).id
            val child = requireNotNull(notebooks.read(childStorage)).id
            notebooks.putItem(parentStorage, NotebookItem("child", "reference", targetDocumentId = child))
            val grants = FileNotebookGrantStore(root.resolve("grants.json"))
            val grant = NotebookPeerGrant("peer", mayRead = true, mayReference = true)
            grants.provision(parent, grant)
            val preview = NotebookReferenceSession(parent, HostNotebookAccess(notebooks, grants, "peer").resolver)
            preview.refresh()
            assertEquals(NotebookReferenceContent.Placeholder.DENIED, preview.state.value.root?.children?.single()?.content)
            grants.provision(child, grant)
            preview.refresh()
            assertIs<NotebookReferenceContent.Markdown>(preview.state.value.root?.children?.single()?.content)
            grants.revoke("peer", child)
            preview.refresh()
            assertEquals(NotebookReferenceContent.Placeholder.DENIED, preview.state.value.root?.children?.single()?.content)
        }
    }

    @Test
    fun registeredRemoteDocumentWithoutGrantIsDenied() = runBlocking {
        val root = Files.createTempDirectory("remote-notebook-authority")
        val remote = Files.createDirectory(root.resolve("remote"))
        val host = Files.createDirectory(root.resolve("host"))
        val remoteId = NotebookLocalStore(remote, "remote-peer").use { source ->
            source.create("Remote").also { assertTrue(source.persistCanvas(it)) }
        }
        val key = remoteId.bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val sourcePath = NotebookHistoryArchive(remote).documentDirectory(key)
        val targetPath = NotebookHistoryArchive(host).documentDirectory(key)
        Files.walk(sourcePath).use { paths ->
            paths.forEach { source ->
                val target = targetPath.resolve(sourcePath.relativize(source))
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        }
        NotebookLocalStore(host, "host-peer").use { notebooks ->
            assertTrue(notebooks.listDocuments().isEmpty())
            assertTrue(notebooks.registerDocument(remoteId))
            val id = requireNotNull(notebooks.read(remoteId)).id
            val access = HostNotebookAccess(notebooks, FileNotebookGrantStore(root.resolve("grants.json")), "peer")
            assertEquals(NotebookReferenceLoad.Denied, access.resolver.resolve(id))
            assertEquals(DocumentWorkspaceOpen.Denied, access.opener.open(id))
        }
    }

    @Test
    fun grantsAreRequiredForReferencesAndRevokedEditorsCannotSave() = runBlocking {
        assumeDurableGrantHost()
        val path = Files.createTempDirectory("notebook-authority")
        val grantsPath = path.resolve("grants.json")
        NotebookLocalStore(Files.createDirectory(path.resolve("notebooks")), "host").use { notebooks ->
            val storageId = notebooks.create("Remote")
            assertTrue(notebooks.registerDocument(storageId))
            val id = requireNotNull(notebooks.read(storageId)).id
            val grants = FileNotebookGrantStore(grantsPath)
            val principal = "peer-one"
            val access = HostNotebookAccess(notebooks, grants, principal)
            assertEquals(NotebookReferenceLoad.Denied, access.resolver.resolve(id))
            assertEquals(DocumentWorkspaceOpen.Denied, access.opener.open(id))

            grants.provision(id, NotebookPeerGrant(principal, mayRead = false, mayEdit = true))
            assertEquals(DocumentWorkspaceOpen.Denied, access.opener.open(id))
            grants.provision(id, NotebookPeerGrant(principal, mayRead = true, mayEdit = true))
            assertEquals(NotebookReferenceLoad.Denied, access.resolver.resolve(id))
            grants.provision(id, NotebookPeerGrant(principal, mayRead = true, mayEdit = true, mayReference = true))
            val reloaded = HostNotebookAccess(notebooks, FileNotebookGrantStore(grantsPath), principal)
            assertIs<NotebookReferenceLoad.Found>(reloaded.resolver.resolve(id))
            assertEquals(NotebookReferenceLoad.Denied, HostNotebookAccess(notebooks, grants, "peer-two").resolver.resolve(id))
            assertEquals(NotebookReferenceLoad.Denied, reloaded.resolver.resolve(NotebookDocumentId("other")))
            assertEquals(DocumentWorkspaceOpen.Denied, reloaded.opener.open(NotebookDocumentId("other")))
            val opened = assertIs<DocumentWorkspaceOpen.Opened>(reloaded.opener.open(id)).sessions.note
            opened.refresh()
            opened.edit("draft")
            grants.revoke(principal, id)
            assertFalse(opened.save())
            assertEquals("", notebooks.read(storageId)?.markdown)
            assertEquals("draft", opened.state.value.draft)
            assertNull(opened.state.value.indeterminateWrite)
        }
    }

    /** Grants that cannot open the editor ahead of one that can do not hide it (on any host: the grants are in memory). */
    @Test
    fun ineligibleGrantsAheadOfAnEditableNoteDoNotHideIt() = runBlocking {
        withNotes(1) { notebooks, (id) ->
            val grants = MemoryGrants(readOnly(SharedNotesWire.MAX_LISTED) + (id to EDITOR))
            val listing = HostNotebookAccess(notebooks, grants, PEER).sharedNotes()

            assertEquals(SharedNoteListing.Listed(listOf(SharedNote(id, "Note 0"))), listing)
            assertEquals(SharedNotesWire.MAX_LISTED + 1, grants.rechecks, "every grant is rechecked once")
        }
    }

    /** One page rechecks a bounded number of grants and hands back where it stopped rather than passing for the whole list. */
    @Test
    fun aPageStopsAtTheRecheckBoundAndSaysWhereToGoOn() = runBlocking {
        withNotes(1) { notebooks, (id) ->
            val grants = MemoryGrants(readOnly(SharedNotesWire.MAX_CHECKED) + (id to EDITOR))
            val listing = assertIs<SharedNoteListing.Listed>(HostNotebookAccess(notebooks, grants, PEER).sharedNotes())

            assertEquals(emptyList(), listing.notes)
            assertNotNull(listing.next, "the page stopped before the last grant")
            assertEquals(SharedNotesWire.MAX_CHECKED, grants.rechecks)
        }
    }

    /** A full page reads the grant store once per chunk of rechecks (plus its listing), never once per grant. */
    @Test
    fun aFullPageReadsTheGrantStoreABoundedNumberOfTimes() = runBlocking {
        withNotes(1) { notebooks, (id) ->
            val grants = MemoryGrants(readOnly(SharedNotesWire.MAX_CHECKED) + (id to EDITOR))
            assertIs<SharedNoteListing.Listed>(HostNotebookAccess(notebooks, grants, PEER).sharedNotes())

            val chunks = SharedNotesWire.MAX_CHECKED / SHARED_NOTES_CHECKS_PER_TRANSACTION
            assertEquals(1 + chunks, grants.reads, "one listing read and one transaction per chunk")
            assertEquals(SharedNotesWire.MAX_CHECKED, grants.rechecks)
        }
    }

    /** An editable note behind more than one page's worth of ineligible grants is reached by following the cursor. */
    @Test
    fun anEditableNoteBeyondTheRecheckBoundIsReachedByFollowingCursors() = runBlocking {
        withNotes(1) { notebooks, (id) ->
            val grants = MemoryGrants(readOnly(SharedNotesWire.MAX_CHECKED + 10) + (id to EDITOR))
            val access = HostNotebookAccess(notebooks, grants, PEER)

            val first = assertIs<SharedNoteListing.Listed>(access.sharedNotes())
            val second = access.sharedNotes(assertNotNull(first.next))

            assertEquals(SharedNoteListing.Listed(listOf(SharedNote(id, "Note 0"))), second, "the last page has no cursor")
            assertEquals(SharedNotesWire.MAX_CHECKED + 11, grants.rechecks, "each grant is rechecked once across the walk")
        }
    }

    /** With nothing changing, the pages together list every openable note exactly once, in id order. */
    @Test
    fun pagesNeitherRepeatNorSkipANoteWhileNothingChanges() = runBlocking {
        withNotes(3) { notebooks, ids ->
            val grants = MemoryGrants(pagedLayout(ids))
            val pages = walk(HostNotebookAccess(notebooks, grants, PEER))

            assertEquals(3, pages.size, "each note sits on its own page")
            assertEquals(ids.sortedBy { it.value }, pages.flatMap { page -> page.notes.map { it.id } })
            assertNull(pages.last().next)
        }
    }

    /** A grant revoked after an earlier page is checked again on the later page and left out there. */
    @Test
    fun aGrantRevokedBetweenPagesIsNotListedOnTheLaterPage() = runBlocking {
        withNotes(3) { notebooks, ids ->
            val (first, revoked, last) = ids.sortedBy { it.value }
            val grants = MemoryGrants(pagedLayout(listOf(first)) + (revoked to EDITOR) + (last to EDITOR))
            val access = HostNotebookAccess(notebooks, grants, PEER)
            val page = assertIs<SharedNoteListing.Listed>(access.sharedNotes())
            assertEquals(listOf(first), page.notes.map { it.id })

            grants.revoke(revoked)
            val later = assertIs<SharedNoteListing.Listed>(access.sharedNotes(assertNotNull(page.next)))

            assertEquals(listOf(last), later.notes.map { it.id })
            assertNull(later.next)
        }
    }

    /** A cursor that is not one this host issued to this principal is refused before any grant is read. */
    @Test
    fun aMalformedOrForeignCursorIsRefusedBeforeAnyRecheck() = runBlocking {
        withNotes(1) { notebooks, (id) ->
            val grants = MemoryGrants(listOf(id to EDITOR))
            val access = HostNotebookAccess(notebooks, grants, PEER)
            val own = SharedNotesCursors.issue(PEER, id).value
            val tooLong = "v1." + "A".repeat(SharedNotesWire.MAX_CURSOR_CHARS)
            val foreign = SharedNotesCursors.issue("other-peer", id).value
            val refused = listOf("", "garbage", "v1.", "v1.!!!!", "v2." + own.removePrefix("v1."), "$own=", tooLong, foreign)

            for (cursor in refused) {
                val answer = access.sharedNotes(SharedNotesCursor(cursor))
                assertEquals(SharedNoteListing.Unavailable(SharedNotesWire.INVALID_CURSOR), answer, cursor)
            }
            assertEquals(0, grants.rechecks)
            val afterOwn = access.sharedNotes(SharedNotesCursor(own))
            assertEquals(SharedNoteListing.Listed(emptyList()), afterOwn, "its own cursor starts after the note it names")
        }
    }

    @Test
    fun aPageRoundTripsOverTheWireAndAnOlderHostWithoutACursorHasNoMore() {
        val id = NotebookDocumentId("0123456789abcdef0123456789abcdef")
        val notes = SharedNotesWire.sharedNotes(SharedNoteListing.Listed(listOf(SharedNote(id, "T"))))["notes"]!!
        val page = SharedNoteListing.Listed(listOf(SharedNote(id, "T")), SharedNotesCursor("v1.opaque"))
        val older = buildJsonObject { put("status", "listed"); put("truncated", true); put("notes", notes) }
        val unreadable = listOf(JsonPrimitive(3), JsonPrimitive(""), JsonPrimitive("x".repeat(SharedNotesWire.MAX_CURSOR_CHARS + 1)))

        assertEquals(page, SharedNotesWire.readSharedNotes(SharedNotesWire.sharedNotes(page)))
        assertEquals(SharedNoteListing.Listed(listOf(SharedNote(id, "T"))), SharedNotesWire.readSharedNotes(older))
        for (cursor in unreadable) {
            val body = buildJsonObject { put("status", "listed"); put("notes", notes); put(SharedNotesWire.NEXT_CURSOR, cursor) }
            assertIs<SharedNoteListing.Unavailable>(SharedNotesWire.readSharedNotes(body), cursor.toString())
        }
        assertEquals(buildJsonObject {}, SharedNotesWire.request(null))
        assertEquals(buildJsonObject { put("cursor", "v1.opaque") }, SharedNotesWire.request(page.next))
    }

    private suspend fun withNotes(count: Int, block: suspend (NotebookLocalStore, List<NotebookDocumentId>) -> Unit) {
        val root = Files.createTempDirectory("notebook-shared-listing")
        NotebookLocalStore(Files.createDirectory(root.resolve("notebooks")), "host").use { notebooks ->
            block(notebooks, (0 until count).map { requireNotNull(notebooks.read(notebooks.create("Note $it"))).id })
        }
    }

    /** Every page the host hands out, following cursors from the first until one has none. */
    private suspend fun walk(access: HostNotebookAccess): List<SharedNoteListing.Listed> {
        val pages = mutableListOf(assertIs<SharedNoteListing.Listed>(access.sharedNotes()))
        while (pages.size <= MAX_PAGES) {
            val next = pages.last().next ?: break
            pages += assertIs<SharedNoteListing.Listed>(access.sharedNotes(next))
        }
        return pages
    }

    /** Ids that sort before any note id the store issues (hex), so these grants come first in id order. */
    private fun readOnly(count: Int) = (0 until count).map { NotebookDocumentId("-read-only-${padded(it)}") to READ_ONLY }

    /**
     * Each editable note followed (in id order) by one page's worth less one of read-only grants, so
     * every page ends right before the next note.
     */
    private fun pagedLayout(notes: List<NotebookDocumentId>) = notes.flatMap { note ->
        val trailing = (1 until SharedNotesWire.MAX_CHECKED).map { NotebookDocumentId("${note.value}-ro-${padded(it)}") to READ_ONLY }
        listOf(note to EDITOR) + trailing
    }

    private fun padded(index: Int) = index.toString().padStart(5, '0')

    /**
     * Grants held in memory, listed in id order as the store contract says. Counts every grant handed
     * to a transaction ([rechecks]) and every store read, each transaction or listing ([reads]).
     */
    private class MemoryGrants(grants: List<Pair<NotebookDocumentId, NotebookPeerGrant>>) : NotebookGrantStore {
        private val byDocument = grants.toMap().toMutableMap()
        var rechecks = 0
        var reads = 0

        fun revoke(id: NotebookDocumentId) {
            byDocument.remove(id)
        }

        override suspend fun <T> withGrant(
            principal: String,
            documentId: NotebookDocumentId,
            action: suspend (NotebookPeerGrant?) -> T,
        ): T {
            rechecks++
            reads++
            return action(byDocument[documentId]?.takeIf { it.peerId == principal })
        }

        override suspend fun <T> withGrants(
            principal: String,
            documentIds: Collection<NotebookDocumentId>,
            action: suspend (Map<NotebookDocumentId, NotebookPeerGrant>) -> T,
        ): T {
            rechecks += documentIds.size
            reads++
            val held = documentIds.mapNotNull { id -> byDocument[id]?.takeIf { it.peerId == principal }?.let { id to it } }
            return action(held.toMap())
        }

        override suspend fun documentsOf(principal: String): List<NotebookDocumentId> {
            reads++
            return byDocument.filterValues { it.peerId == principal }.keys.sortedBy { it.value }
        }
    }

    private companion object {
        const val PEER = "peer"
        const val MAX_PAGES = 10
        val EDITOR = NotebookPeerGrant(PEER, mayRead = true, mayEdit = true)
        val READ_ONLY = NotebookPeerGrant(PEER, mayRead = true)
    }
}
