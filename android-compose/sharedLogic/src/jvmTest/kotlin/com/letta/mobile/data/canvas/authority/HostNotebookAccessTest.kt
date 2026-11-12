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
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        withListedNote { notebooks, id ->
            val grants = MemoryGrants(readOnly(SharedNotesWire.MAX_LISTED) + (id to EDITOR))
            val listing = HostNotebookAccess(notebooks, grants, PEER).sharedNotes()

            assertEquals(SharedNoteListing.Listed(listOf(SharedNote(id, "Note"))), listing)
            assertEquals(SharedNotesWire.MAX_LISTED + 1, grants.rechecks, "every grant is rechecked under its own transaction")
        }
    }

    /** One request rechecks a bounded number of grants and says it stopped rather than passing for the whole list. */
    @Test
    fun aListingStopsAtTheRecheckBoundAndSaysItIsTruncated() = runBlocking {
        withListedNote { notebooks, id ->
            val grants = MemoryGrants(readOnly(SharedNotesWire.MAX_CHECKED) + (id to EDITOR))
            val listing = HostNotebookAccess(notebooks, grants, PEER).sharedNotes()

            assertEquals(SharedNoteListing.Listed(emptyList(), truncated = true), listing)
            assertEquals(SharedNotesWire.MAX_CHECKED, grants.rechecks)
        }
    }

    @Test
    fun aTruncatedListingSaysSoOverTheWireAndAnOlderHostIsNotTruncated() {
        val id = NotebookDocumentId("0123456789abcdef0123456789abcdef")
        val cut = SharedNoteListing.Listed(listOf(SharedNote(id, "T")), truncated = true)
        val older = buildJsonObject { put("status", "listed"); put("notes", SharedNotesWire.sharedNotes(cut)["notes"]!!) }
        val unreadable = buildJsonObject { put("status", "listed"); put("notes", older["notes"]!!); put("truncated", JsonPrimitive("yes")) }

        assertEquals(cut, SharedNotesWire.readSharedNotes(SharedNotesWire.sharedNotes(cut)))
        assertEquals(SharedNoteListing.Listed(listOf(SharedNote(id, "T"))), SharedNotesWire.readSharedNotes(older))
        assertIs<SharedNoteListing.Unavailable>(SharedNotesWire.readSharedNotes(unreadable))
    }

    private suspend fun withListedNote(block: suspend (NotebookLocalStore, NotebookDocumentId) -> Unit) {
        val root = Files.createTempDirectory("notebook-shared-listing")
        NotebookLocalStore(Files.createDirectory(root.resolve("notebooks")), "host").use { notebooks ->
            block(notebooks, requireNotNull(notebooks.read(notebooks.create("Note"))).id)
        }
    }

    private fun readOnly(count: Int) =
        (0 until count).map { NotebookDocumentId("read-only-$it") to NotebookPeerGrant(PEER, mayRead = true) }

    /** Grants held in memory, in order; counts every per-note recheck. */
    private class MemoryGrants(private val grants: List<Pair<NotebookDocumentId, NotebookPeerGrant>>) : NotebookGrantStore {
        private val byDocument = grants.toMap()
        var rechecks = 0

        override suspend fun <T> withGrant(
            principal: String,
            documentId: NotebookDocumentId,
            action: suspend (NotebookPeerGrant?) -> T,
        ): T {
            rechecks++
            return action(byDocument[documentId]?.takeIf { it.peerId == principal })
        }

        override suspend fun documentsOf(principal: String): List<NotebookDocumentId> =
            grants.filter { it.second.peerId == principal }.map { it.first }
    }

    private companion object {
        const val PEER = "peer"
        val EDITOR = NotebookPeerGrant(PEER, mayRead = true, mayEdit = true)
    }
}
