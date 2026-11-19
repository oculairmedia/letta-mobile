package com.letta.mobile.data.pins.shared

import com.letta.mobile.data.pins.wire.AgentPinsError
import com.letta.mobile.data.pins.wire.AgentPinsWire
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** The client side of pinned agents v1: optimistic edits, conflicts, pushes, offline and an empty start. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedAgentPinsTest {
    private val a = AgentId("agent-a")
    private val b = AgentId("agent-b")
    private val c = AgentId("agent-c")

    @Test
    fun readsTheHostListAndCachesIt() = runTest {
        val host = FakeHost(revision = 4, ids = listOf(a, b))
        val cache = cache()
        val pins = SharedAgentPins(host, cache)
        val state = pins.refresh()
        assertEquals(listOf(a, b), state.agentIds)
        assertTrue(state.editable)
        assertEquals(AgentPinsSnapshot(4, listOf(a, b)), cache.read())
    }

    @Test
    fun anAcceptedEditShowsAtOnceAndTakesTheHostRevision() = runTest {
        val host = FakeHost(revision = 1, ids = listOf(a))
        val pins = SharedAgentPins(host, cache()).also { it.refresh() }
        host.beforeSet = { assertEquals(listOf(a, b), pins.state.value.agentIds) }
        assertEquals(AgentPinsEditOutcome.APPLIED, pins.setPinned(b, true))
        assertEquals(AgentPinsSnapshot(2, listOf(a, b)), pins.state.value.snapshot)
        assertEquals(1L, host.setExpectations.single())
    }

    @Test
    fun aConflictRollsBackToTheHostList() = runTest {
        val host = FakeHost(revision = 1, ids = listOf(a))
        val pins = SharedAgentPins(host, cache()).also { it.refresh() }
        host.write(listOf(c))
        assertEquals(AgentPinsEditOutcome.CONFLICT, pins.setPinned(b, true))
        assertEquals(AgentPinsSnapshot(2, listOf(c)), pins.state.value.snapshot)
        assertEquals(AgentPinsWire.REVISION_CONFLICT, host.lastSetError)
    }

    @Test
    fun aRefusedEditIsRolledBack() = runTest {
        val host = FakeHost(revision = 1, ids = listOf(a))
        val pins = SharedAgentPins(host, cache()).also { it.refresh() }
        host.refuseSetWith = AgentPinsWire.INVALID_REQUEST
        assertEquals(AgentPinsEditOutcome.REFUSED, pins.setPinned(c, true))
        assertEquals(AgentPinsSnapshot(1, listOf(a)), pins.state.value.snapshot)
    }

    @Test
    fun newerPushesReplaceTheListAndOlderOnesAreIgnored() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeHost(revision = 3, ids = listOf(a))
        val pins = SharedAgentPins(host, cache())
        val job = launch { pins.run() }
        assertEquals(listOf(a), pins.state.value.agentIds)
        host.push(5, listOf(b, a))
        assertEquals(AgentPinsSnapshot(5, listOf(b, a)), pins.state.value.snapshot)
        host.push(4, listOf(c))
        assertEquals(AgentPinsSnapshot(5, listOf(b, a)), pins.state.value.snapshot)
        job.cancel()
    }

    @Test
    fun offlineShowsTheLastListAndRefusesEditsWithoutQueueing() = runTest(UnconfinedTestDispatcher()) {
        val cache = cache().apply { write(AgentPinsSnapshot(7, listOf(a))) }
        val host = FakeHost(revision = 7, ids = listOf(a), online = false)
        val pins = SharedAgentPins(host, cache)
        assertEquals(listOf(a), pins.state.value.agentIds)
        assertEquals(AgentPinsEditOutcome.OFFLINE, pins.setPinned(b, true))
        val job = launch { pins.run() }
        host.connected.value = true
        assertTrue(pins.state.value.editable)
        host.connected.value = false
        assertFalse(pins.state.value.editable)
        assertEquals(AgentPinsEditOutcome.OFFLINE, pins.setPinned(b, true))
        assertEquals(listOf(a), pins.state.value.agentIds)
        assertTrue(host.setExpectations.isEmpty())
        job.cancel()
    }

    @Test
    fun aHostWithoutTheContractIsUnsupported() = runTest {
        val host = FakeHost(revision = 0, ids = emptyList(), capabilities = emptySet())
        val pins = SharedAgentPins(host, cache())
        assertFalse(pins.refresh().supported)
        assertEquals(AgentPinsEditOutcome.UNSUPPORTED, pins.setPinned(a, true))
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun aTransientFailureNeverLeavesThePinsReadOnly() = runTest {
        val host = FakeHost(revision = 1, ids = listOf(a))
        val pins = SharedAgentPins(host, cache()).also { it.refresh() }
        host.failNextCalls = 1
        assertEquals(AgentPinsEditOutcome.OFFLINE, pins.setPinned(b, true))
        assertFalse(pins.state.value.editable)
        assertEquals(AgentPinsSnapshot(1, listOf(a)), pins.state.value.snapshot)
        assertEquals(AgentPinsEditOutcome.APPLIED, pins.setPinned(b, true))
        assertEquals(AgentPinsSnapshot(2, listOf(a, b)), pins.state.value.snapshot)
    }

    @Test
    fun aFailedReadIsRetriedBeforeTheNextEdit() = runTest {
        val host = FakeHost(revision = 3, ids = listOf(a)).apply { failNextCalls = 1 }
        val pins = SharedAgentPins(host, cache())
        assertFalse(pins.refresh().live)
        assertEquals(AgentPinsError.UNREACHABLE, pins.state.value.error)
        assertEquals(AgentPinsEditOutcome.APPLIED, pins.setPinned(c, true))
        assertEquals(AgentPinsSnapshot(4, listOf(a, c)), pins.state.value.snapshot)
    }

    @Test
    fun aHostWithNoListYetStartsEmpty() = runTest {
        val pins = SharedAgentPins(FakeHost(revision = 0, ids = emptyList()), cache())
        val state = pins.refresh()
        assertEquals(emptyList(), state.agentIds)
        assertTrue(state.editable)
    }

    private fun cache() = SettingsAgentPinsCache(InMemorySecureSettingsStore(), "host-1")

    /** A host serving the contract the way AgentPinsStore does, in memory. */
    private class FakeHost(
        var revision: Long,
        var ids: List<AgentId>,
        val capabilities: Set<String> = setOf(AgentPinsWire.CAPABILITY),
        online: Boolean = true,
    ) : AgentPinsLink {
        val connected = MutableStateFlow(online)
        private val frames = MutableSharedFlow<JsonObject>(extraBufferCapacity = 8)
        val calls = mutableListOf<String>()
        val setExpectations = mutableListOf<Long>()
        var beforeSet: () -> Unit = {}
        var refuseSetWith: String? = null
        var lastSetError: String? = null
        var failNextCalls = 0

        override val hostCapabilities: Set<String> get() = capabilities
        override val isConnected: Boolean get() = connected.value
        override val connection: Flow<Boolean> get() = connected
        override val pushes: Flow<JsonObject> = frames

        fun write(next: List<AgentId>) {
            revision += 1
            ids = next
        }

        fun push(at: Long, list: List<AgentId>) {
            frames.tryEmit(snapshot(at, list))
        }

        override suspend fun call(method: String, params: JsonObject): AgentPinsReply {
            calls += method
            if (failNextCalls > 0) {
                failNextCalls -= 1
                error("link timed out")
            }
            return when (method) {
                AgentPinsWire.GET -> AgentPinsReply(true, snapshot(revision, ids))
                else -> set(params)
            }
        }

        private fun set(params: JsonObject): AgentPinsReply {
            beforeSet()
            val expected = params.getValue(AgentPinsWire.EXPECTED_REVISION).jsonPrimitive.long
            setExpectations += expected
            lastSetError = refuseSetWith ?: AgentPinsWire.REVISION_CONFLICT.takeIf { expected != revision }
            lastSetError?.let { return AgentPinsReply(false, error = it) }
            write(idsOf(params))
            return AgentPinsReply(true, snapshot(revision, ids))
        }

        private fun idsOf(params: JsonObject) = params.getValue(AgentPinsWire.AGENT_IDS).jsonArray.map { AgentId(it.jsonPrimitive.content) }

        private fun snapshot(at: Long, list: List<AgentId>) = buildJsonObject {
            put("type", AgentPinsWire.FRAME_TYPE)
            put("revision", at)
            put(AgentPinsWire.AGENT_IDS, JsonArray(list.map { JsonPrimitive(it.value) }))
        }
    }
}
