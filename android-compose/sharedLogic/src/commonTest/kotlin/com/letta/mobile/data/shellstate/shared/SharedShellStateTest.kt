package com.letta.mobile.data.shellstate.shared

import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.wire.ShellStateError
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** The client side of shell state v1: per-entry compare-and-set, conflicts, pushes, offline, an empty start and recovery. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedShellStateTest {
    private val dark = JsonPrimitive("dark")
    private val light = JsonPrimitive("light")

    @Test
    fun readsTheHostDocumentAndKeepsItInMemory() = runTest {
        val host = FakeHost().apply { seed("appearance.theme", dark) }
        val state = SharedShellState(host).refresh()
        assertEquals(dark, state.entries.getValue("appearance.theme").value)
        assertTrue(state.editable)
    }

    @Test
    fun aProposalCarriesTheRevisionOfEachEntryAsLastSeen() = runTest {
        val host = FakeHost().apply { seed("appearance.theme", dark); seed("layout.open", JsonPrimitive(true)) }
        val shared = SharedShellState(host).also { it.refresh() }
        assertEquals(ShellStateEditOutcome.APPLIED, shared.set("appearance.theme", light))
        assertEquals(mapOf("appearance.theme" to 1L), host.lastExpectations)
        assertEquals(light, shared.state.value.entries.getValue("appearance.theme").value)
        assertEquals(ShellStateEditOutcome.APPLIED, shared.set("brand.new", dark))
        assertEquals(mapOf("brand.new" to 0L), host.lastExpectations)
    }

    @Test
    fun anUnrelatedWriterNeverConflicts() = runTest {
        val host = FakeHost().apply { seed("appearance.theme", dark); seed("layout.open", JsonPrimitive(true)) }
        val shared = SharedShellState(host).also { it.refresh() }
        host.write("layout.open", JsonPrimitive(false))
        assertEquals(ShellStateEditOutcome.APPLIED, shared.set("appearance.theme", light))
        assertEquals(JsonPrimitive(false), shared.state.value.entries.getValue("layout.open").value)
    }

    @Test
    fun aConflictOnTheSameEntryReadsTheHostDocumentAgain() = runTest {
        val host = FakeHost().apply { seed("appearance.theme", dark) }
        val shared = SharedShellState(host).also { it.refresh() }
        host.write("appearance.theme", JsonPrimitive("sepia"))
        assertEquals(ShellStateEditOutcome.CONFLICT, shared.set("appearance.theme", light))
        assertEquals(JsonPrimitive("sepia"), shared.state.value.entries.getValue("appearance.theme").value)
        assertTrue(shared.state.value.editable)
    }

    @Test
    fun aRefusedProposalLeavesTheShownDocumentAlone() = runTest {
        val host = FakeHost().apply { seed("appearance.theme", dark) }
        val shared = SharedShellState(host).also { it.refresh() }
        host.refuseSetWith = ShellStateWire.TOO_LARGE
        assertEquals(ShellStateEditOutcome.REFUSED, shared.set("appearance.theme", light))
        assertEquals(dark, shared.state.value.entries.getValue("appearance.theme").value)
        assertEquals(ShellStateError.TOO_LARGE, shared.state.value.error)
    }

    @Test
    fun anUnknownRefusalCodeReadsAsUnknown() = runTest {
        val host = FakeHost()
        val shared = SharedShellState(host).also { it.refresh() }
        host.refuseSetWith = "some_future_code"
        assertEquals(ShellStateEditOutcome.REFUSED, shared.set("a", dark))
        assertEquals(ShellStateError.UNKNOWN, shared.state.value.error)
    }

    @Test
    fun removeSendsNoValue() = runTest {
        val host = FakeHost().apply { seed("a", dark) }
        val shared = SharedShellState(host).also { it.refresh() }
        assertEquals(ShellStateEditOutcome.APPLIED, shared.remove("a"))
        assertTrue("a" !in shared.state.value.entries)
        assertTrue(host.lastChangeHadValue == false)
    }

    @Test
    fun newerPushesReplaceTheDocumentAndOlderOnesAreIgnored() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeHost().apply { seed("a", dark) }
        val shared = SharedShellState(host)
        val job = launch { shared.run() }
        assertEquals(1L, shared.state.value.snapshot?.documentRevision)
        host.pushRevision(5, "a", light)
        assertEquals(light, shared.state.value.entries.getValue("a").value)
        host.pushRevision(4, "a", JsonPrimitive("old"))
        assertEquals(light, shared.state.value.entries.getValue("a").value)
        job.cancel()
    }

    @Test
    fun aPushOfANewerSchemaIsNotAdopted() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeHost().apply { seed("a", dark) }
        val shared = SharedShellState(host)
        val job = launch { shared.run() }
        host.pushRevision(9, "a", light, schemaVersion = 99)
        assertEquals(dark, shared.state.value.entries.getValue("a").value)
        job.cancel()
    }

    @Test
    fun offlineShowsTheLastDocumentAndRefusesEditsWithoutQueueing() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeHost(online = false).apply { seed("a", dark) }
        val shared = SharedShellState(host)
        assertEquals(ShellStateEditOutcome.OFFLINE, shared.set("a", light))
        val job = launch { shared.run() }
        host.connected.value = true
        assertTrue(shared.state.value.editable)
        host.connected.value = false
        assertFalse(shared.state.value.editable)
        assertEquals(ShellStateEditOutcome.OFFLINE, shared.set("a", light))
        assertEquals(dark, shared.state.value.entries.getValue("a").value)
        assertTrue(host.setCalls == 0)
        job.cancel()
    }

    @Test
    fun aHostWithoutTheContractIsUnsupportedAndKeepsNothingLocal() = runTest {
        val host = FakeHost(capabilities = emptySet())
        val shared = SharedShellState(host)
        assertFalse(shared.refresh().supported)
        assertEquals(ShellStateEditOutcome.UNSUPPORTED, shared.set("a", dark))
        assertTrue(host.calls.isEmpty())
        assertNull(shared.state.value.snapshot)
        assertFalse(shared.state.value.editable)
    }

    @Test
    fun aTransientFailureNeverLeavesTheStateReadOnly() = runTest {
        val host = FakeHost().apply { seed("a", dark) }
        val shared = SharedShellState(host).also { it.refresh() }
        host.failNextCalls = 1
        assertEquals(ShellStateEditOutcome.OFFLINE, shared.set("a", light))
        assertFalse(shared.state.value.editable)
        assertEquals(dark, shared.state.value.entries.getValue("a").value)
        assertEquals(ShellStateEditOutcome.APPLIED, shared.set("a", light))
        assertEquals(light, shared.state.value.entries.getValue("a").value)
    }

    @Test
    fun aFailedReadIsRetriedBeforeTheNextEdit() = runTest {
        val host = FakeHost().apply { seed("a", dark); failNextCalls = 1 }
        val shared = SharedShellState(host)
        assertFalse(shared.refresh().live)
        assertEquals(ShellStateError.UNREACHABLE, shared.state.value.error)
        assertEquals(ShellStateEditOutcome.APPLIED, shared.set("a", light))
    }

    @Test
    fun aRefusedReadIsRetriedBeforeTheNextEditToo() = runTest {
        val host = FakeHost().apply { seed("a", dark); refuseGetOnce = ShellStateWire.STATE_UNREADABLE }
        val shared = SharedShellState(host)
        assertFalse(shared.refresh().live)
        assertEquals(ShellStateError.STATE_UNREADABLE, shared.state.value.error)
        assertEquals(ShellStateEditOutcome.APPLIED, shared.set("a", light))
    }

    @Test
    fun aHostWithNoDocumentYetStartsEmpty() = runTest {
        val state = SharedShellState(FakeHost()).refresh()
        assertTrue(state.entries.isEmpty())
        assertTrue(state.editable)
    }

    /** A host serving the contract the way ShellStateStore does, in memory. */
    private class FakeHost(
        val capabilities: Set<String> = setOf(ShellStateWire.CAPABILITY),
        online: Boolean = true,
    ) : ShellStateLink {
        private var revision = 0L
        private val entries = linkedMapOf<String, ShellStateEntry>()
        val connected = MutableStateFlow(online)
        private val frames = MutableSharedFlow<JsonObject>(extraBufferCapacity = 8)
        val calls = mutableListOf<String>()
        var setCalls = 0
        var lastExpectations: Map<String, Long> = emptyMap()
        var lastChangeHadValue: Boolean? = null
        var refuseSetWith: String? = null
        var refuseGetOnce: String? = null
        var failNextCalls = 0

        override val hostCapabilities: Set<String> get() = capabilities
        override val isConnected: Boolean get() = connected.value
        override val connection: Flow<Boolean> get() = connected
        override val pushes: Flow<JsonObject> = frames

        fun seed(key: String, value: JsonElement) = write(key, value)

        fun write(key: String, value: JsonElement) {
            revision += 1
            entries[key] = ShellStateEntry(value, revision, ShellStateActor.User("endpoint-b"), updatedAtMs = 1)
        }

        fun pushRevision(at: Long, key: String, value: JsonElement, schemaVersion: Int = 1) {
            val snapshot = ShellStateSnapshot(
                schemaVersion, at, mapOf(key to ShellStateEntry(value, at, ShellStateActor.User("endpoint-b"), updatedAtMs = 1)),
            )
            frames.tryEmit(encode(snapshot))
        }

        override suspend fun call(method: String, params: JsonObject): ShellStateReply {
            calls += method
            if (failNextCalls > 0) {
                failNextCalls -= 1
                error("link timed out")
            }
            return if (method == ShellStateWire.GET) get() else propose(params)
        }

        private fun get(): ShellStateReply {
            refuseGetOnce?.let { refuseGetOnce = null; return ShellStateReply(false, error = it) }
            return ShellStateReply(true, encode(document()))
        }

        private fun propose(params: JsonObject): ShellStateReply {
            setCalls += 1
            val changes = params.getValue(ShellStateWire.CHANGES).jsonArray.map { it.jsonObject }
            lastExpectations = changes.associate {
                it.getValue(ShellStateWire.KEY).jsonPrimitive.content to it.getValue(ShellStateWire.EXPECTED_REVISION).jsonPrimitive.long
            }
            lastChangeHadValue = changes.single().containsKey(ShellStateWire.VALUE)
            val stale = lastExpectations.any { (key, expected) -> (entries[key]?.revision ?: 0L) != expected }
            val refusal = refuseSetWith ?: ShellStateWire.REVISION_CONFLICT.takeIf { stale }
            refusal?.let { return ShellStateReply(false, error = it) }
            revision += 1
            changes.forEach { change ->
                val key = change.getValue(ShellStateWire.KEY).jsonPrimitive.content
                val value = change[ShellStateWire.VALUE]
                if (value == null) entries.remove(key)
                else entries[key] = ShellStateEntry(value, revision, ShellStateActor.User("endpoint-a"), updatedAtMs = 1)
            }
            return ShellStateReply(true, encode(document()))
        }

        private fun document() = ShellStateSnapshot(1, revision, entries.toMap())

        private fun encode(snapshot: ShellStateSnapshot): JsonObject {
            val body = LettaJson.persisted.encodeToJsonElement(ShellStateSnapshot.serializer(), snapshot).jsonObject
            return JsonObject(body + ("type" to JsonPrimitive(ShellStateWire.FRAME_TYPE)))
        }
    }
}
