package com.letta.mobile.wrapper.notebook

import com.github.ajalt.clikt.core.parse
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookLocalStore
import com.letta.mobile.data.canvas.authority.FileNotebookGrantStore
import com.letta.mobile.data.canvas.authority.RemoteNotebookAccess
import com.letta.mobile.data.canvas.notes.NotebookNoteLoad
import com.letta.mobile.data.canvas.notes.NotebookNoteSave
import com.letta.mobile.data.canvas.references.NotebookReferenceLoad
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceOpen
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The client side of shared notebooks, end to end through the real host routes and grant store: a
 * granted peer edits the host's note, revoking the grant denies the next open and every edit with
 * no content returned, an ungranted peer sees nothing, and a dead transport is unavailable.
 */
class RemoteNotebookAccessTest {
    @Test
    fun aGrantedPeerOpensAndEditsTheHostNote() = withHost { host ->
        val access = host.client(ALICE)
        host.grant(ALICE, edit = true)

        val opened = assertIs<DocumentWorkspaceOpen.Opened>(access.opener.open(host.id))
        val note = opened.sessions.note
        note.refresh()
        assertEquals("shared text", note.state.value.draft)
        note.edit("edited from the phone")

        assertTrue(note.save())
        assertEquals("edited from the phone", host.markdown())
    }

    @Test
    fun revokingTheGrantDeniesRemountAndEditsWithoutContent() = withHost { host ->
        val access = host.client(ALICE)
        host.grant(ALICE, edit = true)
        val first = assertIs<DocumentWorkspaceOpen.Opened>(access.opener.open(host.id)).sessions.note
        first.refresh()
        first.edit("draft after revoke")

        host.revoke(ALICE)

        assertEquals(DocumentWorkspaceOpen.Denied, access.opener.open(host.id))
        assertEquals(NotebookNoteLoad.Denied, access.backend.load(host.id))
        assertEquals(NotebookNoteSave.Denied, access.backend.save(host.id, "shared text", "draft after revoke"))
        assertEquals(NotebookReferenceLoad.Denied, access.resolver.resolve(host.id))
        assertFalse(first.save())
        first.refresh()
        assertTrue(first.state.value.denied)
        assertEquals("shared text", host.markdown())
    }

    @Test
    fun aPeerWithoutAGrantSeesNothing() = withHost { host ->
        host.grant(ALICE, edit = true)
        val bob = host.client(BOB)

        assertEquals(DocumentWorkspaceOpen.Denied, bob.opener.open(host.id))
        assertEquals(NotebookNoteLoad.Denied, bob.backend.load(host.id))
        assertEquals(NotebookReferenceLoad.Denied, bob.resolver.resolve(host.id))
    }

    @Test
    fun readOnlyGrantResolvesTheReferenceButDoesNotOpenTheEditor() = withHost { host ->
        val access = host.client(ALICE)
        host.grant(ALICE, edit = false)

        assertIs<NotebookReferenceLoad.Found>(access.resolver.resolve(host.id))
        assertEquals(DocumentWorkspaceOpen.Denied, access.opener.open(host.id))
    }

    @Test
    fun aFailedTransportIsUnavailableNotDeniedOrEmpty() = runBlocking {
        val access = RemoteNotebookAccess(AdminRpcInvoker { _, _ -> error("offline") })
        val id = NotebookDocumentId("0123456789abcdef0123456789abcdef")

        assertEquals(DocumentWorkspaceOpen.Unavailable, access.opener.open(id))
        assertIs<NotebookNoteLoad.Unavailable>(access.backend.load(id))
        assertIs<NotebookNoteSave.Unavailable>(access.backend.save(id, "", "x"))
        assertEquals(NotebookReferenceLoad.Unavailable, access.resolver.resolve(id))
    }

    private class Host(root: Path) : AutoCloseable {
        private val grants = root.resolve("grants.json")
        private val store = NotebookLocalStore(Files.createDirectory(root.resolve("notebooks")), "host")
        private val storageId = store.create("Shared")
        val id: NotebookDocumentId = requireNotNull(store.read(storageId)).id
        private val router = AdminRpcRouter().also { registerNotebookAdminRoutes(it, store, FileNotebookGrantStore(grants)) }

        init {
            grantCommand(HOST_ADMIN, "--edit", "--read")
            val seeded = runBlocking { client(HOST_ADMIN).backend.save(id, "", "shared text") }
            check(seeded is NotebookNoteSave.Saved) { "could not seed the note: $seeded" }
        }

        fun client(peer: String) = RemoteNotebookAccess(notebookPeer(router, peer).asInvoker())

        fun grant(peer: String, edit: Boolean) =
            if (edit) grantCommand(peer, "--read", "--edit", "--reference") else grantCommand(peer, "--read", "--reference")

        fun revoke(peer: String) = grantCommand(peer, "--revoke")

        fun markdown(): String = runBlocking { (client(HOST_ADMIN).backend.load(id) as NotebookNoteLoad.Found).snapshot.markdown }

        private fun grantCommand(peer: String, vararg flags: String) =
            NotebookGrantCommand().parse(listOf("--file", grants.toString(), "--principal", peer, "--document", id.value) + flags)

        override fun close() = store.close()
    }

    private fun withHost(block: suspend (Host) -> Unit) = runBlocking {
        Host(Files.createTempDirectory("remote-notebook")).use { block(it) }
    }

    private companion object {
        val ALICE = "a".repeat(64)
        val BOB = "b".repeat(64)
        val HOST_ADMIN = "c".repeat(64)
    }
}

private inline fun <reified T : Any> assertIs(value: Any?): T = assertInstanceOf(T::class.java, value)
