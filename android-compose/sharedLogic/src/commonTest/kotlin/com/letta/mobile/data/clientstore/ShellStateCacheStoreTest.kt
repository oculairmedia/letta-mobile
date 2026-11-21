package com.letta.mobile.data.clientstore

import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

class ShellStateCacheStoreTest {
    private val store = InMemorySecureSettingsStore()
    private val cache = ShellStateCacheStore(store)

    @Test
    fun theMirrorRoundTripsUnderItsDeclaredDisposableEntry() {
        val document = snapshot("appearance.theme" to JsonPrimitive("DARK"))
        cache.save(document)

        assertEquals(document, cache.load())
        assertEquals(ClientStoreKind.DisposableCache, ClientStoreEntries.declarationOf(cache.entry.storageKey)?.kind)
        assertTrue(cache.entry in ClientStoreEntries.caches)
    }

    @Test
    fun onlyEntriesInAnAllowedNamespaceAreEverKept() {
        cache.save(snapshot("appearance.theme" to JsonPrimitive("DARK"), "secrets.token" to JsonPrimitive("hf_secret"), "voice.x" to JsonPrimitive(1)))

        val raw = store.getString(cache.entry.storageKey).orEmpty()
        assertEquals(setOf("appearance.theme"), cache.load()?.entries?.keys)
        assertTrue("hf_secret" !in raw && "voice.x" !in raw, "what a host sent outside the namespace never reached the device")
    }

    @Test
    fun anUnreadableOrNewerSchemaMirrorIsNoMirrorAndNothingThrows() {
        store.putString(cache.entry.storageKey, "{not json")
        assertNull(cache.load())
        store.putString(cache.entry.storageKey, """{"schema_version":99,"document_revision":1,"entries":{}}""")
        assertNull(cache.load())
    }

    @Test
    fun clearAllWipesTheMirror() = runTest {
        cache.save(snapshot("appearance.theme" to JsonPrimitive("DARK")))
        ClientStoreEntries.clearAll(listOf(cache))
        assertNull(cache.load())
        assertNull(store.getString(cache.entry.storageKey))
    }

    private fun snapshot(vararg entries: Pair<String, JsonPrimitive>) = ShellStateSnapshot(
        1, 3, entries.associate { (key, value) -> key to ShellStateEntry(value, 3, ShellStateActor.User("endpoint-b"), 1) },
    )
}
