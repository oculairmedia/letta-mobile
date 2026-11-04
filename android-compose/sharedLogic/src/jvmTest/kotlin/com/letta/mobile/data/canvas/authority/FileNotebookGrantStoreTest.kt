package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookPeerGrant
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileNotebookGrantStoreTest {
    @Test
    fun provisionReloadIsolationAndRevoke() = runBlocking {
        val file = Files.createTempDirectory("notebook-grants").resolve("grants.json")
        val store = FileNotebookGrantStore(file)
        val id = NotebookDocumentId("document-one")
        val grant = NotebookPeerGrant("principal-one", mayRead = true, mayEdit = true, mayReference = true)
        assertNull(store.withGrant("principal-one", id) { it })
        store.provision(id, grant)
        val reloaded = FileNotebookGrantStore(file)
        assertEquals(grant, reloaded.withGrant("principal-one", id) { it })
        assertNull(reloaded.withGrant("principal-two", id) { it })
        assertNull(reloaded.withGrant("principal-one", NotebookDocumentId("document-two")) { it })
        reloaded.revoke("principal-one", id)
        assertNull(store.withGrant("principal-one", id) { it })
    }

    @Test
    fun documentedFixtureLoads() = runBlocking {
        val file = Files.createTempDirectory("notebook-grants-fixture").resolve("grants.json")
        val fixture = requireNotNull(javaClass.getResourceAsStream("/notebook-authority/grants-v1.json"))
        fixture.use { Files.copy(it, file) }
        val grant = FileNotebookGrantStore(file).withGrant(
            "a".repeat(64), NotebookDocumentId("0123456789abcdef0123456789abcdef"),
        ) { it }
        assertEquals(NotebookPeerGrant("a".repeat(64), mayRead = true, mayReference = true), grant)
    }

    @Test
    fun malformedStoreFailsClosed() = runBlocking {
        val file = Files.createTempDirectory("notebook-grants-corrupt").resolve("grants.json")
        Files.writeString(file, "{\"version\":1,\"grants\":[{}]}")
        val store = FileNotebookGrantStore(file)
        val malformed = assertFailsWith<Exception> { store.withGrant("principal", NotebookDocumentId("document")) { it } }
        assertTrue(malformed.message?.contains("Malformed notebook grant store") == true)
        assertFailsWith<Exception> { store.provision(NotebookDocumentId("document"), NotebookPeerGrant("principal")) }
        assertTrue(Files.readString(file).contains("[{}]"))
        Files.writeString(file, """{"version":"1","grants":[]}""")
        assertFailsWith<Exception> { store.withGrant("principal", NotebookDocumentId("document")) { it } }
        Files.writeString(file, """{"version":1,"grants":[{"principal":"principal","documentId":"document",""" +
            """"mayRead":"true","mayEdit":false,"mayReference":false}]}""")
        assertFailsWith<Exception> { store.withGrant("principal", NotebookDocumentId("document")) { it } }
        Files.writeString(file, """{"version":1,"grants":[{"principal":"principal","documentId":"document",""" +
            """"mayRead":false,"mayRead":true,"mayEdit":false,"mayReference":false}]}""")
        assertFailsWith<Exception> { store.withGrant("principal", NotebookDocumentId("document")) { it } }
        Files.writeString(file, """{"version":1,"grants":[{"principal":"principal","documentId":"document",""" +
            """"mayRead":false,"may\u0052ead":true,"mayEdit":false,"mayReference":false}]}""")
        assertFailsWith<Exception> { store.withGrant("principal", NotebookDocumentId("document")) { it } }
        val prefix = "{\"version\":1,\"grants\":[{\"principal\":\"".encodeToByteArray()
        val suffix = "\",\"documentId\":\"document\",\"mayRead\":true,\"mayEdit\":false,\"mayReference\":false}]}".encodeToByteArray()
        Files.write(file, prefix + byteArrayOf(0xff.toByte()) + suffix)
        assertFailsWith<Exception> { store.withGrant("principal", NotebookDocumentId("document")) { it } }
        Unit
    }

    @Test
    fun revokeWaitsForActiveTransactionAcrossStoreInstances() = runBlocking {
        val file = Files.createTempDirectory("notebook-grants-lock").resolve("grants.json")
        val store = FileNotebookGrantStore(file)
        val peer = "principal"
        val id = NotebookDocumentId("document")
        store.provision(id, NotebookPeerGrant(peer, mayEdit = true))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = async {
            store.withGrant(peer, id) { grant ->
                assertTrue(grant?.mayEdit == true)
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val revoke = async { FileNotebookGrantStore(file).revoke(peer, id) }
        yield()
        assertFalse(revoke.isCompleted)
        release.complete(Unit)
        operation.await()
        revoke.await()
        assertNull(store.withGrant(peer, id) { it })
    }
}
