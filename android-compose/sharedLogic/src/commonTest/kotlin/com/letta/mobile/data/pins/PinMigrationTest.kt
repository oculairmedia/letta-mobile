package com.letta.mobile.data.pins

import com.letta.mobile.data.home.SettingsStoreHomePinStore
import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.objectverbs.binding.withDevicePins
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
    fun incompleteSnapshotRereadsItsSourceAndRetainsNewPinsBeforeCheckpointing() {
        val backing = InterruptingStore()
        backing.putString("home.pinnedItems", "agent:a")
        var reads = 0
        val actual = LegacyPinSources.secure(backing, setOf("home.pinnedItems")).single()
        val source = LegacyPinSource(actual.key, { reads++; actual.read() }, actual.decode)
        val first = DevicePinStore(backing)
        backing.failOnWrite = 2
        assertFailsWith<IllegalStateException> { first.importLegacy(listOf(source), owner) }
        backing.putString("home.pinnedItems", "agent:a\nagent:b")
        backing.failOnWrite = null
        val resumed = DevicePinStore(backing)
        resumed.importLegacy(listOf(source), owner)
        assertEquals(listOf(ref(ObjectRef("agent", "a")), ref(ObjectRef("agent", "b"))), resumed.journal.value.pins)
        assertEquals(2, reads)
        val imported = resumed.journal.value.imports.single { it.key == actual.key }
        assertEquals("agent:a\nagent:b", imported.raw)
        assertEquals(listOf("agent:a", "agent:a\nagent:b"), imported.snapshots.map { it.raw })
        resumed.setPinned(ref(ObjectRef("agent", "a")), false)
        resumed.importLegacy(listOf(source), owner)
        assertEquals(2, reads, "a completed source must not be reread after cutover")
        assertEquals(listOf(ref(ObjectRef("agent", "b"))), resumed.journal.value.pins)
    }

    @Test
    fun legacyParsingPreservesOpaqueHomeAndProjectIdsAndUsesConversationTrimRules() {
        val raw = mapOf(
            "home.pinnedItems" to "agent:with space \n shortcut:TOOLS\nshortcut:TOOLS ",
            "projects.pinned" to " project key ",
            "conversations.pinned" to " chat ",
        )
        val parsed = LegacyPinSources.secure(InMemorySecureSettingsStore(raw), raw.keys)
            .associate { it.key to it.decode(checkNotNull(raw[it.key])) }
        assertEquals(listOf("agent:with space ", " shortcut:TOOLS", "shortcut:TOOLS "), parsed["home.pinnedItems"])
        assertEquals(listOf("project: project key "), parsed["projects.pinned"])
        assertEquals(listOf("conversation:chat"), parsed["conversations.pinned"])
    }

    @Test
    fun unreadableCanonicalJournalKeepsTheWorkspaceAvailableWithoutTreatingTheReadAsEmpty() {
        val backing = FailableSettings(readFails = true)
        val created = runCatching { SettingsStoreHomePinStore(backing.withDevicePins(), emptyList()) }
        assertTrue(created.isSuccess, "a failed pin backing must not prevent the workspace from opening")
        val pins = created.getOrThrow().binding
        assertEquals(null, pins.registry.describe(
            ObjectRef("shortcut", "TOOLS"), com.letta.mobile.data.objectverbs.VerbContext("home-tile"),
        ))
        assertEquals(0, backing.writes)
        assertEquals("shortcut:TOOLS", backing.values["home.pinnedItems"])
        assertFalse(pins.store.journal.value.imports.any { it.complete })
        backing.readFails = false
        val reopened = SettingsStoreHomePinStore(backing.withDevicePins(), emptyList())
        assertEquals(listOf("shortcut:TOOLS"), reopened.pinnedKeys.value)
    }

    @Test
    fun failedSnapshotWriteKeepsLegacyPinsAndLeavesAnUnavailableProvider() {
        val backing = FailableSettings(writeFails = true)
        val created = runCatching { SettingsStoreHomePinStore(backing.withDevicePins(), emptyList()) }
        assertTrue(created.isSuccess, "a failed snapshot write must not prevent the workspace from opening")
        assertEquals("shortcut:TOOLS", backing.values["home.pinnedItems"])
        assertEquals(null, backing.values[DevicePinStore.STORAGE_KEY])
        val pins = created.getOrThrow().binding
        assertFalse(pins.store.journal.value.imports.any { it.complete })
        assertEquals("disabled", pins.registry.describe(
            ObjectRef("shortcut", "TOOLS"), com.letta.mobile.data.objectverbs.VerbContext("home-tile"),
        )?.verbs?.single()?.availability?.state)
        backing.writeFails = false
        assertEquals(listOf("shortcut:TOOLS"), SettingsStoreHomePinStore(backing.withDevicePins(), emptyList()).pinnedKeys.value)
    }

    @Test
    fun importsBothHomeBackingsWithoutLosingMembershipOrOrder() {
        val storage = InMemorySecureSettingsStore(mapOf(
            "desktop.home.pinnedItems" to "shortcut:TOOLS\nshortcut:AGENTS",
            "home.pinnedItems" to "shortcut:AGENTS\nshortcut:CONVERSATIONS",
        ))
        val home = SettingsStoreHomePinStore(storage.withDevicePins(), emptyList(), "desktop.home.pinnedItems")
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
        store.importLegacy(sources, owner)
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
    fun settingsOrderWinsWhenImportedAfterHomeWithoutMovingExistingCanonicalPins() {
        val backing = InMemorySecureSettingsStore(mapOf("home.pinnedItems" to "shortcut:TOOLS"))
        val store = DevicePinStore(backing)
        val existing = ref(ObjectRef("agent", "existing"))
        store.setPinned(existing, true)
        store.importLegacy(LegacyPinSources.secure(backing, setOf("home.pinnedItems")))
        val settings = LegacyPinSource(LegacyPinSources.SETTINGS_ORDER,
            { "[\"shortcut:AGENTS\",\"shortcut:TOOLS\"]" },
            { com.letta.mobile.data.serialization.LettaJson.wire.decodeFromString<List<String>>(it) })
        store.importLegacy(listOf(settings))
        assertEquals(listOf(existing, LegacyPinSources.shortcut("shortcut:AGENTS"),
            LegacyPinSources.shortcut("shortcut:TOOLS")), store.journal.value.pins)
        assertEquals(store.journal.value, DevicePinStore(backing).journal.value)
    }

    @Test
    fun userReorderSurvivesALaterHigherPriorityImport() {
        val backing = InMemorySecureSettingsStore(mapOf("home.pinnedItems" to "shortcut:TOOLS\nshortcut:AGENTS"))
        val store = DevicePinStore(backing)
        store.importLegacy(LegacyPinSources.secure(backing, setOf("home.pinnedItems")))
        val tools = checkNotNull(LegacyPinSources.shortcut("shortcut:TOOLS"))
        val agents = checkNotNull(LegacyPinSources.shortcut("shortcut:AGENTS"))
        store.reorder(listOf(agents, tools))
        val settings = LegacyPinSource(LegacyPinSources.SETTINGS_ORDER,
            { "[\"shortcut:TOOLS\",\"shortcut:AGENTS\"]" },
            { com.letta.mobile.data.serialization.LettaJson.wire.decodeFromString<List<String>>(it) })
        store.importLegacy(listOf(settings))
        assertEquals(listOf(agents, tools), store.journal.value.pins)
    }

    @Test
    fun separateStoreInstancesReadLatestBeforeEachMutation() {
        val backing = InMemorySecureSettingsStore()
        val first = DevicePinStore(backing)
        val second = DevicePinStore(backing)
        val a = ref(ObjectRef("agent", "a"))
        val b = ref(ObjectRef("agent", "b"))
        first.setPinned(a, true)
        second.setPinned(b, true)
        first.setPinned(a, false)
        second.setPinned(b, true)
        assertEquals(listOf(b), DevicePinStore(backing).journal.value.pins)
        assertTrue(a in DevicePinStore(backing).journal.value.tombstones)
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
        store.resolvePending { _, entry ->
            if (entry == "agent:missing") ref(ObjectRef("agent", "missing")).copy(source = "returned") else null
        }
        assertEquals(listOf(ref(ObjectRef("agent", "missing")).copy(source = "returned")), store.journal.value.pins)
        assertEquals(listOf("missing"), PinBinding(store, MutableStateFlow("returned")).ids("agent").value)
        assertEquals(emptyList(), PinBinding(store, MutableStateFlow("other")).ids("agent").value)
    }

    @Test
    fun failedReadAndCorruptDecodeAreNeverEmptyOrCompletedAndCanRetry() {
        val backing = InMemorySecureSettingsStore()
        val store = DevicePinStore(backing)
        val home = LegacyPinSources.secure(
            InMemorySecureSettingsStore(mapOf("desktop.home.pinnedItems" to "agent:restored")),
            setOf("desktop.home.pinnedItems"),
        ).single()
        val failed = LegacyPinSource(home.key, { error("unavailable") }, home.decode)
        val snapshot = object : com.letta.mobile.data.storage.SettingsPreferencesSnapshot {
            override fun getString(key: String): String? = "corrupt"
            override fun getStringSet(key: String): Set<String>? = null
            override fun getBoolean(key: String): Boolean? = null
            override fun getFloat(key: String): Float? = null
        }
        val corrupt = LegacyPinSources.settings(snapshot).first()
        store.importLegacy(listOf(failed, corrupt))
        val records = store.journal.value.imports
        assertFalse(records.single { it.key == failed.key }.complete)
        assertFalse(records.single { it.key == corrupt.key }.complete)
        assertEquals("corrupt", records.single { it.key == corrupt.key }.raw)
        store.importLegacy(listOf(home), owner)
        assertEquals(listOf(ref(ObjectRef("agent", "restored"))), store.journal.value.pins)
        assertFalse(store.journal.value.imports.single { it.key == "desktop.conversations.pinned" }.complete)
    }

    @Test
    fun crashAfterUnionBeforeCheckpointResumesWithoutDuplicatesAndUnpinCannotResurrect() {
        val backing = InterruptingStore()
        val snapshot = object : com.letta.mobile.data.storage.SettingsPreferencesSnapshot {
            override fun getString(key: String): String? = "[\"agent:b\",\"agent:a\"]"
            override fun getStringSet(key: String): Set<String>? = null
            override fun getBoolean(key: String): Boolean? = null
            override fun getFloat(key: String): Float? = null
        }
        val sources = listOf(LegacyPinSources.settings(snapshot).first())
        val interrupted = DevicePinStore(backing)
        backing.failOnWrite = 3
        assertFailsWith<IllegalStateException> { interrupted.importLegacy(sources, owner) }
        val resumed = DevicePinStore(backing)
        assertEquals(listOf(ref(ObjectRef("agent", "b")), ref(ObjectRef("agent", "a"))), resumed.journal.value.pins)
        assertFalse(resumed.journal.value.imports.first().complete)
        backing.failOnWrite = null
        resumed.importLegacy(sources, owner)
        resumed.setPinned(ref(ObjectRef("agent", "b")), false)
        val reopened = DevicePinStore(backing)
        reopened.importLegacy(sources, owner)
        reopened.resolvePending(owner)
        assertEquals(listOf(ref(ObjectRef("agent", "a"))), reopened.journal.value.pins)
        assertTrue(ref(ObjectRef("agent", "b")) in reopened.journal.value.tombstones)
        assertTrue(reopened.journal.value.imports.first().complete)
    }

    @Test
    fun sameIdsOnDifferentBackendsStayDistinctAndReorderPreservesInvisibleSlots() {
        val store = DevicePinStore(InMemorySecureSettingsStore())
        val a = ref(ObjectRef("agent", "same")).copy(source = "one")
        val other = ref(ObjectRef("agent", "same")).copy(source = "two")
        val b = ref(ObjectRef("agent", "b")).copy(source = "one")
        listOf(a, other, b).forEach { store.setPinned(it, true) }
        store.reorder(listOf(b, a))
        assertEquals(listOf(b, other, a), store.journal.value.pins)
        assertEquals(2, store.facets(a).pinOrder)
        assertEquals(1, store.facets(other).pinOrder)
    }

    @Test
    fun explicitEmptyHomeDoesNotSeedDefaultsAndAbsentDoes() {
        val empty = InMemorySecureSettingsStore(mapOf("home.pinnedItems" to ""))
        assertEquals(emptyList(), SettingsStoreHomePinStore(empty.withDevicePins(), listOf("shortcut:TOOLS")).pinnedKeys.value)
        val absent = InMemorySecureSettingsStore()
        val home = SettingsStoreHomePinStore(absent.withDevicePins(), listOf("shortcut:TOOLS"))
        assertEquals(listOf("shortcut:TOOLS"), home.pinnedKeys.value)
        home.setPinned("shortcut:TOOLS", false)
        assertEquals(emptyList(), SettingsStoreHomePinStore(absent.withDevicePins(), listOf("shortcut:TOOLS")).pinnedKeys.value)
    }

    private val owner: (String, String) -> ScopedObjectRef? = { _, entry ->
        LegacyPinSources.objectRef(entry)?.takeUnless { it.kind == "shortcut" }?.let { ScopedObjectRef("backend", it) }
    }

    private fun ref(objectRef: ObjectRef) = ScopedObjectRef("backend", objectRef)

    private class FailableSettings(var readFails: Boolean = false, var writeFails: Boolean = false) : SecureSettingsStore {
        val values = mutableMapOf("home.pinnedItems" to "shortcut:TOOLS")
        var writes = 0
        override fun getString(key: String, defaultValue: String?): String? {
            check(!readFails || key != DevicePinStore.STORAGE_KEY) { "read unavailable" }
            return values[key] ?: defaultValue
        }
        override fun putString(key: String, value: String) {
            check(!writeFails) { "write unavailable" }
            writes++
            values[key] = value
        }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
    }

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
