package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookPeerGrant
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileNotebookGrantStoreTest {
    @Test
    fun provisionReportsDirectorySyncFailureAfterReplacement() = runBlocking {
        val grant = NotebookPeerGrant("principal", mayRead = true)
        // The replacement may be visible even when its crash durability is unconfirmed.
        assertEquals(grant, directorySyncFailureAfterReplacement(grant))
    }

    @Test
    fun revokeReportsDirectorySyncFailureAfterReplacement() = runBlocking {
        assertNull(directorySyncFailureAfterReplacement(null))
    }

    private suspend fun directorySyncFailureAfterReplacement(expected: NotebookPeerGrant?): NotebookPeerGrant? {
        assumeDurableGrantHost()
        val file = Files.createTempDirectory("notebook-grant-directory-sync").resolve("grants.json")
        val id = NotebookDocumentId("document")
        if (expected == null) FileNotebookGrantStore(file).provision(id, NotebookPeerGrant("principal", mayRead = true))
        val injected = AtomicBoolean(false)
        val store = FileNotebookGrantStore(file) { directory ->
            assertEquals(file.parent, directory)
            val replaced = if (expected == null) {
                Json.parseToJsonElement(Files.readString(file)).jsonObject.getValue("grants").jsonArray.isEmpty()
            } else Files.exists(file)
            if (replaced) {
                injected.set(true)
                throw IOException("injected directory sync failure after replacement")
            }
            forceDirectory(directory)
        }
        val failure = runCatching {
            if (expected == null) store.revoke("principal", id) else store.provision(id, expected)
        }.exceptionOrNull()
        assertTrue(injected.get(), "The fault must occur after the real atomic replacement")
        assertIs<IOException>(failure, "Administration must not acknowledge an unconfirmed directory write")
        return FileNotebookGrantStore(file).withGrant("principal", id) { it }
    }

    @Test
    fun unsupportedDirectorySyncRefusesBeforeChangingTheGrantFile() = runBlocking {
        val file = Files.createTempDirectory("notebook-grant-unsupported-sync").resolve("grants.json")
        val id = NotebookDocumentId("document")
        val grant = NotebookPeerGrant("principal", mayRead = true)
        val store = FileNotebookGrantStore(file, directoryDurabilitySupported = false) {
            throw UnsupportedOperationException("injected unsupported directory syncing")
        }
        val failure = runCatching { store.provision(id, grant) }.exceptionOrNull()
        assertIs<UnsupportedOperationException>(failure, "Unsupported durability must be explicit")
        Files.list(file.parent).use { assertEquals(0L, it.count(), "Refusal must not even create a lock file") }
        assertEquals(
            "Notebook sharing needs a host that can confirm directory durability; not available on a Windows host",
            failure.message,
        )
        assertFailsWith<UnsupportedOperationException> { store.revoke("principal", id) }
        Files.list(file.parent).use { assertEquals(0L, it.count()) }
        assertNull(store.withGrant("principal", id) { it })
        Files.writeString(file, """{"version":1,"grants":[{"principal":"principal","documentId":"document",""" +
            """"mayRead":true,"mayEdit":false,"mayReference":false}]}""")
        assertEquals(grant, store.withGrant("principal", id) { it })
        val before = Files.readAllBytes(file)
        assertFailsWith<UnsupportedOperationException> { store.provision(id, grant) }
        assertFailsWith<UnsupportedOperationException> { store.revoke("principal", id) }
        assertTrue(before.contentEquals(Files.readAllBytes(file)), "Unsupported administration must leave grants unchanged")
    }

    private fun forceDirectory(directory: Path) = FileChannel.open(directory).use { it.force(true) }

    @Test
    fun provisionReloadIsolationAndRevoke() = runBlocking {
        assumeDurableGrantHost()
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

    /** One bulk transaction hands back exactly this principal's grants among the ids asked, from one read. */
    @Test
    fun withGrantsHandsBackOnlyThePrincipalsGrantsForTheIdsAsked() = runBlocking {
        val file = Files.createTempDirectory("notebook-grants-bulk").resolve("grants.json")
        fun entry(principal: String, document: String, edit: Boolean) =
            """{"principal":"$principal","documentId":"$document","mayRead":true,"mayEdit":$edit,"mayReference":false}"""
        val entries = listOf(entry("one", "a", true), entry("one", "b", false), entry("two", "c", true), entry("one", "d", true))
        Files.writeString(file, """{"version":1,"grants":[${entries.joinToString(",")}]}""")
        val asked = listOf("a", "b", "c", "missing").map(::NotebookDocumentId)

        val held = FileNotebookGrantStore(file).withGrants("one", asked) { it }

        val expected = mapOf(
            NotebookDocumentId("a") to NotebookPeerGrant("one", mayRead = true, mayEdit = true),
            NotebookDocumentId("b") to NotebookPeerGrant("one", mayRead = true),
        )
        assertEquals(expected, held, "another principal's grant, an id not asked and a missing grant are absent")
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
        assumeDurableGrantHost()
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
