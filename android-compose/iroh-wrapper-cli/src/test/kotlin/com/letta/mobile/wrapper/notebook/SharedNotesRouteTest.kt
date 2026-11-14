package com.letta.mobile.wrapper.notebook

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.NotebookPeerGrant
import com.letta.mobile.data.canvas.authority.FileNotebookGrantStore
import com.letta.mobile.data.canvas.authority.NotebookGrantStore
import com.letta.mobile.data.canvas.authority.SharedNotesWire
import com.letta.mobile.data.canvas.workspace.SharedNoteListing
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.IrohNodeConnection
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.automerge.ObjectId
import org.automerge.repo.DocumentId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `notebook.shared.list` through the real router and connection gate: who may call it, cursors that
 * belong to someone else, pages bounded by bytes, one listing per principal at a time, and notes that
 * cannot be read.
 */
class SharedNotesRouteTest {
    /** A cursor the host really issued to one principal is refused, as invalid, when another replays it. */
    @Test
    fun aRealCursorIssuedToOnePrincipalIsRefusedForAnother() = withStore { store, root ->
        val ids = (0..SharedNotesWire.MAX_CHECKED).map { "-read-only-${it.toString().padStart(5, '0')}" }
        val grantsFile = root.resolve("grants.json")
        writeGrants(grantsFile, ids.flatMap { listOf(GrantLine(ALICE, it, edit = false), GrantLine(BOB, it, edit = false)) })
        val router = routerOver(store, FileNotebookGrantStore(grantsFile))

        val first = notebookPeer(router, ALICE).call(SharedNotesWire.LIST, buildJsonObject {})
        val cursor = first.getValue(SharedNotesWire.NEXT_CURSOR).jsonPrimitive.content
        val replayed = notebookPeer(router, BOB).call(SharedNotesWire.LIST, buildJsonObject { put(SharedNotesWire.CURSOR, cursor) })
        val own = notebookPeer(router, ALICE).call(SharedNotesWire.LIST, buildJsonObject { put(SharedNotesWire.CURSOR, cursor) })

        assertEquals("unavailable", replayed.status())
        assertEquals(SharedNotesWire.INVALID_CURSOR, replayed.getValue("reason").jsonPrimitive.content)
        assertEquals("listed", own.status(), "the principal it was issued to may follow it")
    }

    /** Any authenticated session may list (a chat-role pairing included); an unauthenticated or revoked one may not. */
    @Test
    fun aChatRoleSessionMayListButAnUnauthenticatedOrRevokedSessionMayNot() = withStore { store, _ ->
        val grants = MemoryGrantStore()
        val router = routerOver(store, grants)
        val chat = notebookPeer(router, ALICE, pairedPeers(ALICE, IrohPeerCapabilities.CHAT_ROLE))
        val stranger = notebookPeer(router, BOB, InMemoryPairedPeerStore())
        val revokedPairing = pairedPeers(CAROL)
        val revoked = notebookPeer(router, CAROL, revokedPairing)
        revokedPairing.remove(CAROL)

        assertEquals("listed", chat.call(SharedNotesWire.LIST, buildJsonObject {}).status())
        val listingsBefore = grants.listings
        for (refused in listOf(stranger, revoked)) {
            val frame = refused.adminFrame(SharedNotesWire.LIST, buildJsonObject {})
            assertEquals("false", frame.getValue("success").jsonPrimitive.content, frame.toString())
        }
        assertEquals(listingsBefore, grants.listings, "a refused session never reaches the grant store")
    }

    /** A request whose context carries no valid authenticated endpoint id is refused before any grant is read. */
    @Test
    fun aRequestWithoutAnAuthenticatedEndpointIdIsRefused() = withStore { store, _ ->
        val grants = MemoryGrantStore()
        val router = routerOver(store, grants)
        val contexts = listOf(
            AdminRpcRequestContext(authenticated = true, authenticatedEndpointId = null),
            AdminRpcRequestContext(authenticated = true, authenticatedEndpointId = "not-an-endpoint-id"),
            AdminRpcRequestContext(authenticated = false, authenticatedEndpointId = ALICE),
        )

        for (context in contexts) {
            val invocation = AdminRpcInvocation(
                requestId = "r", method = SharedNotesWire.LIST, params = buildJsonObject {}, context = context,
            )
            val frame = Json.parseToJsonElement(router.dispatch(invocation)).jsonObject
            assertEquals("false", frame.getValue("success").jsonPrimitive.content, "$context")
        }
        assertEquals(0, grants.listings)
    }

    /** Notes whose entries together pass the byte budget are paged by bytes; every frame stays far under 1 MiB. */
    @Test
    fun aPageTooLargeInBytesIsSplitAndTheWalkListsEveryNote() = withStore { store, _ ->
        val title = "\u0001".repeat(2 * SharedNotesWire.MAX_TITLE_CHARS)
        val count = SharedNotesWire.MAX_PAGE_BYTES / (JSON_CONTROL_CHAR_BYTES * SharedNotesWire.MAX_TITLE_CHARS) + EXTRA_NOTES
        val ids = (0 until count).map { idOf(store, store.create(title)) }
        val grants = MemoryGrantStore(ids.associateWith { EDITOR })
        val alice = notebookPeer(routerOver(store, grants), ALICE)

        val pages = walk(alice)

        assertTrue(pages.size >= 2, "the notes do not fit one page by bytes")
        assertEquals(ids.sortedBy { it.value }, pages.flatMap { page -> page.listing.notes.map { it.id } })
        assertTrue(pages.all { page -> page.listing.notes.all { it.title.length == SharedNotesWire.MAX_TITLE_CHARS } })
        assertTrue(pages.all { it.frameBytes < FRAME_LIMIT_BYTES * 3 / 4 }, pages.map { it.frameBytes }.toString())
    }

    /** A second listing for the same principal while one runs is answered busy at once; others are unaffected. */
    @Test
    fun aSecondConcurrentListingForTheSamePrincipalIsBusyNotQueued() = withStore { store, _ ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val grants = MemoryGrantStore(gate = { principal ->
            if (principal == ALICE && !entered.isCompleted) {
                entered.complete(Unit)
                release.await()
            }
        })
        val router = routerOver(store, grants)
        val first = async { notebookPeer(router, ALICE).call(SharedNotesWire.LIST, buildJsonObject {}) }
        entered.await()

        val second = notebookPeer(router, ALICE).call(SharedNotesWire.LIST, buildJsonObject {})
        val other = notebookPeer(router, BOB).call(SharedNotesWire.LIST, buildJsonObject {})
        release.complete(Unit)

        assertEquals("unavailable", second.status())
        assertEquals(SharedNotesWire.BUSY, second.getValue("reason").jsonPrimitive.content)
        assertEquals("listed", other.status(), "another principal is not held back")
        assertEquals("listed", first.await().status())
        assertEquals("listed", notebookPeer(router, ALICE).call(SharedNotesWire.LIST, buildJsonObject {}).status(), "the slot is freed")
    }

    /** A granted note whose title cannot be read is skipped and the page goes on (against the file grant store). */
    @Test
    fun aNoteThatCannotBeReadIsSkippedAndTheRestAreListed() = withStore { store, root ->
        val broken = store.create("Broken")
        val readable = store.create("Readable")
        val brokenId = idOf(store, broken)
        val readableId = idOf(store, readable)
        breakTitle(store, broken)
        val grantsFile = root.resolve("grants.json")
        writeGrants(grantsFile, listOf(GrantLine(ALICE, brokenId.value, edit = true), GrantLine(ALICE, readableId.value, edit = true)))
        val alice = notebookPeer(routerOver(store, FileNotebookGrantStore(grantsFile)), ALICE)

        val result = alice.call(SharedNotesWire.LIST, buildJsonObject {})
        val listing = assertInstanceOf(SharedNoteListing.Listed::class.java, SharedNotesWire.readSharedNotes(result))

        assertEquals(listOf(readableId), listing.notes.map { it.id })
        assertEquals("Readable", listing.notes.single().title)
        assertNull(listing.next)
    }

    private fun withStore(block: suspend CoroutineScope.(NotebookLocalStore, Path) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("shared-notes-route")
        NotebookLocalStore(Files.createDirectory(root.resolve("notebooks")), "host").use { block(this, it, root) }
    }

    private fun routerOver(store: NotebookLocalStore, grants: NotebookGrantStore): AdminRpcRouter =
        AdminRpcRouter().also { registerNotebookAdminRoutes(it, store, grants) }

    private fun idOf(store: NotebookLocalStore, storage: DocumentId): NotebookDocumentId = requireNotNull(store.read(storage)).id

    /** Makes the note's title something other than a string, so reading it throws. */
    private fun breakTitle(store: NotebookLocalStore, storage: DocumentId) {
        requireNotNull(store.open(storage)).withDocument { document ->
            document.startTransaction().use { tx ->
                tx.set(ObjectId.ROOT, "title", true)
                tx.commit()
            }
        }.get()
    }

    /** Every page from the first, following `next_cursor`, with the size of each response frame. */
    private suspend fun walk(peer: IrohNodeConnection): List<ListedPage> {
        val pages = mutableListOf<ListedPage>()
        var params = buildJsonObject {}
        while (pages.size < MAX_PAGES) {
            val frame = peer.adminFrame(SharedNotesWire.LIST, params)
            val listing = SharedNotesWire.readSharedNotes(frame["result"])
            pages += ListedPage(assertInstanceOf(SharedNoteListing.Listed::class.java, listing), frame.toString().encodeToByteArray().size)
            val next = pages.last().listing.next ?: break
            params = buildJsonObject { put(SharedNotesWire.CURSOR, next.value) }
        }
        return pages
    }

    private suspend fun IrohNodeConnection.call(method: String, params: JsonObject): JsonObject {
        val parsed = adminFrame(method, params)
        assertTrue(parsed["success"]?.jsonPrimitive?.content == "true", parsed.toString())
        return requireNotNull(parsed["result"]).jsonObject
    }

    private fun JsonObject.status() = requireNotNull(this["status"]).jsonPrimitive.content

    /** One page as read by the client, and the bytes of the frame that carried it. */
    private data class ListedPage(val listing: SharedNoteListing.Listed, val frameBytes: Int)

    /** One grant as the file store keeps it. */
    private data class GrantLine(val principal: String, val document: String, val edit: Boolean)

    /** Writes the grant file directly, in the store's own strict format (reading it needs no directory durability). */
    private fun writeGrants(path: Path, lines: List<GrantLine>) {
        val body = buildJsonObject {
            put("version", 1)
            put("grants", buildJsonArray {
                lines.forEach { line ->
                    add(buildJsonObject {
                        put("principal", line.principal)
                        put("documentId", line.document)
                        put("mayRead", true)
                        put("mayEdit", line.edit)
                        put("mayReference", false)
                    })
                }
            })
        }
        Files.writeString(path, body.toString())
    }

    /**
     * Grants in memory, every one of them held by [ALICE]. [listings] counts listing reads; [gate] runs at
     * the start of each, so a test can hold one open.
     */
    private class MemoryGrantStore(
        private val byDocument: Map<NotebookDocumentId, NotebookPeerGrant> = emptyMap(),
        private val gate: suspend (String) -> Unit = {},
    ) : NotebookGrantStore {
        var listings = 0

        override suspend fun <T> withGrant(
            principal: String,
            documentId: NotebookDocumentId,
            action: suspend (NotebookPeerGrant?) -> T,
        ): T = action(byDocument[documentId]?.takeIf { it.peerId == principal })

        override suspend fun <T> withGrants(
            principal: String,
            documentIds: Collection<NotebookDocumentId>,
            action: suspend (Map<NotebookDocumentId, NotebookPeerGrant>) -> T,
        ): T = action(documentIds.mapNotNull { id -> byDocument[id]?.takeIf { it.peerId == principal }?.let { id to it } }.toMap())

        override suspend fun documentsOf(principal: String): List<NotebookDocumentId> {
            listings++
            gate(principal)
            return byDocument.filterValues { it.peerId == principal }.keys.sortedBy { it.value }
        }
    }

    private companion object {
        val ALICE = "a".repeat(64)
        val BOB = "b".repeat(64)
        val CAROL = "c".repeat(64)
        val EDITOR = NotebookPeerGrant(ALICE, mayRead = true, mayEdit = true)

        /** JSON writes a control character such as U+0001 as a six-byte escape. */
        const val JSON_CONTROL_CHAR_BYTES = 6
        const val EXTRA_NOTES = 20
        const val FRAME_LIMIT_BYTES = 1024 * 1024
        const val MAX_PAGES = 20
    }
}
