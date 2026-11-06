package com.letta.mobile.data.pins

import com.letta.mobile.data.home.SettingsStoreHomePinStore
import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.objectverbs.binding.PinBinding
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class PinMigrationTest {
    @Test
    fun importsBothHomeBackingsWithoutLosingMembershipOrOrder() {
        val storage = InMemorySecureSettingsStore(mapOf(
            "desktop.home.pinnedItems" to "shortcut:TOOLS\nshortcut:AGENTS",
            "home.pinnedItems" to "shortcut:AGENTS\nshortcut:CONVERSATIONS",
        ))
        val home = SettingsStoreHomePinStore(storage, emptyList(), "desktop.home.pinnedItems")
        assertEquals(listOf("shortcut:TOOLS", "shortcut:AGENTS", "shortcut:CONVERSATIONS"), home.pinnedKeys.value)
    }

    @Test
    fun inventoryUnionsAllBackingsWithSettingsOrderBeforeBothHomeOrders() {
        val raw = mapOf(
            "pinned_items_order" to "[\"agent:b\",\"agent:a\",\"agent:b\"]",
            "desktop.home.pinnedItems" to "agent:a\nagent:c\nshortcut:TOOLS\nshortcut:FUTURE",
            "home.pinnedItems" to "agent:d\nagent:a",
            "pinned_conversation_ids" to "[\"c\",\"z\"]",
            "pinned_project_ids" to "[\"p\"]",
            "desktop.conversations.pinned" to "d\nc",
            "conversations.pinned" to "e",
            "projects.pinned" to "q",
            "desktop.projects.pinned" to "r",
        )
        val backing = InMemorySecureSettingsStore()
        val store = DevicePinStore(backing)
        val snapshot = object : com.letta.mobile.data.storage.SettingsPreferencesSnapshot {
            override fun getString(key: String): String? = raw[key]
            override fun getStringSet(key: String): Set<String>? = raw[key]?.let {
                com.letta.mobile.data.serialization.LettaJson.wire.decodeFromString<List<String>>(it).toSet()
            }
            override fun getBoolean(key: String): Boolean? = null
            override fun getFloat(key: String): Float? = null
        }
        val sources = LegacyPinSources.settings(snapshot) + LegacyPinSources.secure(
            InMemorySecureSettingsStore(raw),
            LegacyPinSources.keys.drop(1).toSet() - setOf("pinned_conversation_ids", "pinned_project_ids"),
        )
        store.importLegacy(sources, ::owner)
        assertEquals(listOf("agent:b", "agent:a", "agent:c", "shortcut:TOOLS", "agent:d", "conversation:c",
            "conversation:z", "project:p", "conversation:d", "conversation:e", "project:q", "project:r"),
            store.journal.value.pins.map { "${it.objectRef.kind}:${it.objectRef.id}" })
        assertEquals(LegacyPinSources.keys, store.journal.value.imports.map { it.key })
        assertTrue(store.journal.value.imports.all { it.complete })
        assertEquals(raw, store.journal.value.imports.associate { it.key to it.raw })
        assertEquals(listOf("shortcut:FUTURE"), store.journal.value.imports.flatMap { it.entries - it.resolved.keys })
        assertEquals(store.journal.value, DevicePinStore(backing).journal.value)
    }

    @Test
    fun unknownBackendAndKindsStayHiddenUntilTrustedOwnershipReturns() {
        val backing = InMemorySecureSettingsStore(mapOf(
            "home.pinnedItems" to "agent:missing\nshortcut:FUTURE\nwidget:x\nconversation:default",
        ))
        val store = DevicePinStore(backing)
        store.importLegacy(LegacyPinSources.secure(backing, setOf("home.pinnedItems")))
        assertEquals(emptyList(), store.journal.value.pins)
        assertEquals(4, store.journal.value.imports.single { it.key == "home.pinnedItems" }.entries.size)
        store.resolvePending { _, entry -> if (entry == "agent:missing") ref("agent", "missing", "returned") else null }
        assertEquals(listOf(ref("agent", "missing", "returned")), store.journal.value.pins)
        assertEquals(listOf("missing"), PinBinding(store, MutableStateFlow("returned")).ids("agent").value)
        assertEquals(emptyList(), PinBinding(store, MutableStateFlow("other")).ids("agent").value)
    }

    @Test
    fun failedReadAndCorruptDecodeAreNeverEmptyOrCompletedAndCanRetry() {
        val backing = InMemorySecureSettingsStore()
        val store = DevicePinStore(backing)
        val failed = LegacyPinSource("pinned_items_order", { error("unavailable") }) { emptyList() }
        val corrupt = LegacyPinSource("home.pinnedItems", { "corrupt" }) { error("bad encoding") }
        store.importLegacy(listOf(failed, corrupt))
        val records = store.journal.value.imports
        assertFalse(records.single { it.key == failed.key }.complete)
        assertFalse(records.single { it.key == corrupt.key }.complete)
        assertEquals("corrupt", records.single { it.key == corrupt.key }.raw)
        store.importLegacy(listOf(LegacyPinSource(failed.key, { "ok" }) { listOf("agent:restored") }), ::owner)
        assertEquals(listOf(ref("agent", "restored")), store.journal.value.pins)
        assertFalse(store.journal.value.imports.single { it.key == "desktop.conversations.pinned" }.complete)
    }

    @Test
    fun crashAfterUnionBeforeCheckpointResumesWithoutDuplicatesAndUnpinCannotResurrect() {
        val backing = InterruptingStore()
        val sources = listOf(LegacyPinSource("pinned_items_order", { "saved" }) { listOf("agent:b", "agent:a") })
        val interrupted = DevicePinStore(backing)
        backing.failOnWrite = 3
        assertFailsWith<IllegalStateException> { interrupted.importLegacy(sources, ::owner) }
        val resumed = DevicePinStore(backing)
        assertEquals(listOf(ref("agent", "b"), ref("agent", "a")), resumed.journal.value.pins)
        assertFalse(resumed.journal.value.imports.first().complete)
        backing.failOnWrite = null
        resumed.importLegacy(sources, ::owner)
        resumed.setPinned(ref("agent", "b"), false)
        val reopened = DevicePinStore(backing)
        reopened.importLegacy(sources, ::owner)
        reopened.resolvePending(::owner)
        assertEquals(listOf(ref("agent", "a")), reopened.journal.value.pins)
        assertTrue(ref("agent", "b") in reopened.journal.value.tombstones)
        assertTrue(reopened.journal.value.imports.first().complete)
    }

    @Test
    fun sameIdsOnDifferentBackendsStayDistinctAndReorderPreservesInvisibleSlots() {
        val store = DevicePinStore(InMemorySecureSettingsStore())
        val a = ref("agent", "same", "one")
        val other = ref("agent", "same", "two")
        val b = ref("agent", "b", "one")
        listOf(a, other, b).forEach { store.setPinned(it, true) }
        store.reorder(listOf(b, a))
        assertEquals(listOf(b, other, a), store.journal.value.pins)
        assertEquals(2, store.facets(a).pinOrder)
        assertEquals(1, store.facets(other).pinOrder)
    }

    @Test
    fun explicitEmptyHomeDoesNotSeedDefaultsAndAbsentDoes() {
        val empty = InMemorySecureSettingsStore(mapOf("home.pinnedItems" to ""))
        assertEquals(emptyList(), SettingsStoreHomePinStore(empty, listOf("shortcut:TOOLS")).pinnedKeys.value)
        val absent = InMemorySecureSettingsStore()
        val home = SettingsStoreHomePinStore(absent, listOf("shortcut:TOOLS"))
        assertEquals(listOf("shortcut:TOOLS"), home.pinnedKeys.value)
        home.setPinned("shortcut:TOOLS", false)
        assertEquals(emptyList(), SettingsStoreHomePinStore(absent, listOf("shortcut:TOOLS")).pinnedKeys.value)
    }

    private fun owner(@Suppress("UNUSED_PARAMETER") key: String, entry: String): ScopedObjectRef? = LegacyPinSources.objectRef(entry)
        ?.takeUnless { it.kind == "shortcut" }?.let { ScopedObjectRef("backend", it) }

    private fun ref(kind: String, id: String, source: String = "backend") = ScopedObjectRef(source, ObjectRef(kind, id))

    private class InterruptingStore : SecureSettingsStore by InMemorySecureSettingsStore() {
        var writes = 0
        var failOnWrite: Int? = null
        override fun putDurableString(key: String, value: String) {
            writes++
            check(writes != failOnWrite) { "simulated process interruption" }
            putString(key, value)
        }
    }
}
