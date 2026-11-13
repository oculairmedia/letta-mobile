package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookItem
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.NotebookDocumentIndex
import com.letta.mobile.data.canvas.NotebookHistoryArchive
import com.letta.mobile.data.canvas.NotebookPeerGrant
import com.letta.mobile.data.canvas.notes.notebookStorageId
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
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.automerge.ObjectId
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

    /**
     * Editable grants whose ids cannot name a local notebook (stored before provisioning checked ids)
     * open nothing and are skipped, more than a page of them ahead of real notes and one between them,
     * and the walk still reaches every note after them.
     */
    @Test
    fun grantsWhoseIdsCannotNameANotebookAreSkippedAndDoNotHideLaterNotes() = runBlocking {
        withNotes(2) { notebooks, ids ->
            val (first, last) = ids.sortedBy { it.value }
            val between = NotebookDocumentId("${first.value}-not-a-notebook")
            val grants = MemoryGrants(editableInvalid(SharedNotesWire.MAX_CHECKED + 10) + listOf(first, between, last).map { it to EDITOR })
            val pages = walk(HostNotebookAccess(notebooks, grants, PEER))

            assertEquals(2, pages.size, "the first page stops inside the invalid grants and hands back a cursor")
            assertEquals(listOf(first, last), pages.flatMap { page -> page.notes.map { it.id } })
            assertNull(pages.last().next)
            assertEquals(SharedNotesWire.MAX_CHECKED + 13, grants.rechecks, "each grant is rechecked once across the walk")
        }
    }

    /**
     * A note whose title cannot be read is counted as checked and left out, and the page goes on past it,
     * so one broken note never makes the listing unavailable for everyone it is shared with.
     */
    @Test
    fun aNoteThatCannotBeReadIsSkippedAndThePageGoesOn() = runBlocking {
        withNotes(2) { notebooks, ids ->
            val (broken, readable) = ids.sortedBy { it.value }
            breakTitle(notebooks, broken)
            val grants = MemoryGrants(editableInvalid(1) + (broken to EDITOR) + (readable to EDITOR))

            val listing = HostNotebookAccess(notebooks, grants, PEER).sharedNotes()

            assertEquals(SharedNoteListing.Listed(listOf(SharedNote(readable, titleOf(notebooks, readable)))), listing)
            assertEquals(3, grants.rechecks, "the unreadable note was checked once and the walk went on")
        }
    }

    /** Skipping an unreadable note does not hide a store-wide failure: a grant store that cannot be read is unavailable. */
    @Test
    fun aGrantStoreThatCannotBeReadStillMakesThePageUnavailable() = runBlocking {
        withNotes(1) { notebooks, (id) ->
            val grants = UnreadableGrants(listOf(id))

            assertIs<SharedNoteListing.Unavailable>(HostNotebookAccess(notebooks, grants, PEER).sharedNotes())
        }
    }

    /** A grant whose note was retired (moved to a successor) is not listed, and no successor is looked up for it. */
    @Test
    fun aRetiredNoteIsNotListedEvenWithARecordedSuccessor() = runBlocking {
        val directory = Files.createTempDirectory("notebook-shared-retired")
        val retired = NotebookLocalStore(directory, "host").use { store ->
            store.create("Retired").also { id ->
                store.insertMarkdown(id, 0, "Retired body")
                check(store.persistCanvas(id))
            }
        }
        val retiredKey = keyOf(retired.bytes)
        val successor = NotebookHistoryArchive(directory)
            .compactToNewDocument(retiredKey, 1_000L, NotebookDocumentIndex(directory), 1_024L).newKey
        NotebookLocalStore(directory, "host").use { notebooks ->
            assertEquals(successor, notebooks.resolveStorageId(retired)?.let { keyOf(it.bytes) }, "the successor is recorded")
            val grants = MemoryGrants(listOf(NotebookDocumentId(retiredKey) to EDITOR, NotebookDocumentId(successor) to EDITOR))

            val listing = assertIs<SharedNoteListing.Listed>(HostNotebookAccess(notebooks, grants, PEER).sharedNotes())

            assertEquals(listOf(NotebookDocumentId(successor)), listing.notes.map { it.id })
            assertNull(listing.next)
        }
    }

    /**
     * The file store reuses what it parsed only while the file's bytes are unchanged, so a grant revoked
     * by another instance (as another process would) is rechecked and left out on the next page.
     */
    @Test
    fun aGrantRevokedThroughAnotherFileStoreIsNotListedByOneThatParsedTheFileBefore() = runBlocking {
        assumeDurableGrantHost()
        withNotes(2) { notebooks, ids ->
            val path = Files.createTempDirectory("notebook-grant-reuse").resolve("grants.json")
            val admin = FileNotebookGrantStore(path)
            ids.forEach { admin.provision(it, EDITOR) }
            val access = HostNotebookAccess(notebooks, FileNotebookGrantStore(path), PEER)
            val sorted = ids.sortedBy { it.value }
            assertEquals(sorted, assertIs<SharedNoteListing.Listed>(access.sharedNotes()).notes.map { it.id })

            admin.revoke(PEER, sorted.first())

            assertEquals(listOf(sorted.last()), assertIs<SharedNoteListing.Listed>(access.sharedNotes()).notes.map { it.id })
        }
    }

    /** Long titles are cut on the wire, never inside a surrogate pair, and a reader cuts them too. */
    @Test
    fun titlesAreCutToTheWireMaximumWithoutSplittingACharacter() {
        val max = SharedNotesWire.MAX_TITLE_CHARS
        val pairAtTheCut = "a".repeat(max - 1) + "😀" + "tail"
        val id = NotebookDocumentId("0123456789abcdef0123456789abcdef")

        assertEquals("b".repeat(max), SharedNotesWire.boundedTitle("b".repeat(max + 10)))
        assertEquals("a".repeat(max - 1), SharedNotesWire.boundedTitle(pairAtTheCut))
        assertEquals("short", SharedNotesWire.boundedTitle("short"))
        val long = buildJsonObject {
            put("status", "listed")
            put("notes", buildJsonArray { add(buildJsonObject { put("document_id", id.value); put("title", "c".repeat(max * 3)) }) })
        }
        val read = assertIs<SharedNoteListing.Listed>(SharedNotesWire.readSharedNotes(long))
        assertEquals(max, read.notes.single().title.length)
        val sent = SharedNotesWire.sharedNotes(SharedNoteListing.Listed(listOf(SharedNote(id, "d".repeat(max * 3)))))
        assertEquals(max, assertIs<SharedNoteListing.Listed>(SharedNotesWire.readSharedNotes(sent)).notes.single().title.length)
    }

    /** A reader keeps only entries whose id is a host note id, dropping (not failing on) the others. */
    @Test
    fun aReaderDropsEntriesWhoseIdIsNotAHostNoteId() {
        val good = "0123456789abcdef0123456789abcdef"
        val bad = listOf("", "../etc/passwd", good.uppercase(), good + "0", good.drop(1), "g".repeat(32))
        val body = buildJsonObject {
            put("status", "listed")
            put("notes", buildJsonArray {
                (bad + good).forEach { id -> add(buildJsonObject { put("document_id", id); put("title", "T") }) }
            })
        }

        assertEquals(SharedNoteListing.Listed(listOf(SharedNote(NotebookDocumentId(good), "T"))), SharedNotesWire.readSharedNotes(body))
    }

    /** A page whose entries would pass the byte budget stops before it, and the next page starts with the note that did not fit. */
    @Test
    fun aPageStopsAtTheByteBudgetAndTheNextStartsWithTheNoteThatDidNotFit() = runBlocking {
        val count = 2 * SharedNotesWire.MAX_PAGE_BYTES / longEntryBytes() + 1
        withTitledNotes(count, LONG_TITLE) { notebooks, ids ->
            val grants = MemoryGrants(ids.map { it to EDITOR })
            val pages = walk(HostNotebookAccess(notebooks, grants, PEER))

            assertTrue(pages.size >= 3, "a page stops at the byte budget, not at the note count")
            assertEquals(ids.sortedBy { it.value }, pages.flatMap { page -> page.notes.map { it.id } })
            for (page in pages) {
                assertTrue(page.notes.sumOf(SharedNotesWire::entryBytes) <= SharedNotesWire.MAX_PAGE_BYTES)
                assertTrue(page.notes.all { it.title.length == SharedNotesWire.MAX_TITLE_CHARS })
            }
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

    /** Like [withNotes], every note titled [title]. */
    private suspend fun withTitledNotes(count: Int, title: String, block: suspend (NotebookLocalStore, List<NotebookDocumentId>) -> Unit) {
        val root = Files.createTempDirectory("notebook-shared-titled")
        NotebookLocalStore(Files.createDirectory(root.resolve("notebooks")), "host").use { notebooks ->
            block(notebooks, (0 until count).map { requireNotNull(notebooks.read(notebooks.create(title))).id })
        }
    }

    /** The encoded size of one note entry titled [LONG_TITLE], as a page counts it. */
    private fun longEntryBytes(): Int =
        SharedNotesWire.entryBytes(SharedNote(NotebookDocumentId("0".repeat(32)), SharedNotesWire.boundedTitle(LONG_TITLE)))

    /** Makes the note's title something other than a string, so a title read throws. */
    private fun breakTitle(notebooks: NotebookLocalStore, id: NotebookDocumentId) {
        requireNotNull(notebooks.open(notebookStorageId(id))).withDocument { document ->
            document.startTransaction().use { tx ->
                tx.set(ObjectId.ROOT, "title", true)
                tx.commit()
            }
        }.get()
    }

    private fun titleOf(notebooks: NotebookLocalStore, id: NotebookDocumentId): String =
        requireNotNull(notebooks.read(notebookStorageId(id))).title

    private fun keyOf(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    /** Every page the host hands out, following cursors from the first until one has none. */
    private suspend fun walk(access: HostNotebookAccess): List<SharedNoteListing.Listed> {
        val pages = mutableListOf(assertIs<SharedNoteListing.Listed>(access.sharedNotes()))
        while (pages.size <= MAX_PAGES) {
            val next = pages.last().next ?: break
            pages += assertIs<SharedNoteListing.Listed>(access.sharedNotes(next))
        }
        return pages
    }

    /** Editable grants for ids no local notebook can have, sorting before any note id the store issues (hex). */
    private fun editableInvalid(count: Int) = (0 until count).map { NotebookDocumentId("-invalid-${padded(it)}") to EDITOR }

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

    /** Grants the store cannot read: listing works, every transaction fails as a broken store would. */
    private class UnreadableGrants(private val documents: List<NotebookDocumentId>) : NotebookGrantStore {
        override suspend fun <T> withGrant(
            principal: String,
            documentId: NotebookDocumentId,
            action: suspend (NotebookPeerGrant?) -> T,
        ): T = throw IOException("grant store unreadable")

        override suspend fun <T> withGrants(
            principal: String,
            documentIds: Collection<NotebookDocumentId>,
            action: suspend (Map<NotebookDocumentId, NotebookPeerGrant>) -> T,
        ): T = throw IOException("grant store unreadable")

        override suspend fun documentsOf(principal: String): List<NotebookDocumentId> = documents
    }

    private companion object {
        /** Control characters, which JSON writes as six bytes each, so few notes pass the byte budget. */
        val LONG_TITLE = "\u0001".repeat(2 * SharedNotesWire.MAX_TITLE_CHARS)
        const val PEER = "peer"
        const val MAX_PAGES = 10
        val EDITOR = NotebookPeerGrant(PEER, mayRead = true, mayEdit = true)
        val READ_ONLY = NotebookPeerGrant(PEER, mayRead = true)
    }
}
