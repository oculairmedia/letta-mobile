package com.letta.mobile.ui.mascot

import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotPalette
import com.letta.mobile.avatar.core.MascotShape
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** The mascot identity writer (moved from feature-editagent with the shared agent editor, letta-mobile-ckmub.3.24). */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentMascotIdentityWriterTest {
    private val agentId = "agent-42"
    private val loaded: Map<String, JsonElement> = mapOf("team" to JsonPrimitive("core"))
    private val turned = MascotIdentity(MascotShape.HEXAGON, MascotPalette.TEAL, rotationDegrees = 30)

    private class FakeSettings : SecureSettingsStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String?) = values[key] ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() = values.clear()
    }

    /** Records each metadata write; a write can be held open to model a slow network. */
    private class RecordingAgent {
        val sent = mutableListOf<Map<String, JsonElement>>()
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        var inFlight = 0
        var maxInFlight = 0
        var failNext = false

        suspend fun push(metadata: Map<String, JsonElement>) {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                gates.removeFirstOrNull()?.await()
                if (failNext) {
                    failNext = false
                    error("offline")
                }
                sent += metadata
            } finally {
                inFlight--
            }
        }
    }

    private fun unexpected(e: Exception): Nothing = throw AssertionError("unexpected write failure", e)

    private fun Map<String, JsonElement>.identity() = this[MASCOT_IDENTITY_METADATA_KEY]?.jsonPrimitive?.content

    @Test
    fun aPickIsCachedAtOnceAndWrittenToTheAgentWithItsTurnAndTheOtherMetadata() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings()
        val remote = RecordingAgent()
        val writer = AgentMascotIdentityWriter(agentId, settings, backgroundScope, { loaded }, remote::push, onFailed = ::unexpected)

        writer.write(turned)
        advanceUntilIdle()

        assertEquals("hexagon:ff14a08a:30", settings.values[mascotIdentitySettingsKey(agentId)])
        assertEquals("hexagon:ff14a08a:30", remote.sent.single().identity())
        assertEquals(JsonPrimitive("core"), remote.sent.single()["team"])
    }

    @Test
    fun picksMadeWhileAWriteIsInFlightEndOnTheLastOneWithoutOverlappingWrites() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings()
        val remote = RecordingAgent()
        val slow = CompletableDeferred<Unit>().also { remote.gates += it }
        val writer = AgentMascotIdentityWriter(agentId, settings, backgroundScope, { loaded }, remote::push, onFailed = ::unexpected)

        writer.write(turned)
        listOf(45, 90, 135).forEach { writer.write(turned.copy(rotationDegrees = it)) }
        assertEquals("hexagon:ff14a08a:135", settings.values[mascotIdentitySettingsKey(agentId)])

        slow.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("hexagon:ff14a08a:30", "hexagon:ff14a08a:135"), remote.sent.map { it.identity() })
        assertEquals(1, remote.maxInFlight)
    }

    @Test
    fun aFailedWriteLeavesTheNextOneMergingIntoTheLoadedMetadata() = runTest(UnconfinedTestDispatcher()) {
        val remote = RecordingAgent().apply { failNext = true }
        val failures = mutableListOf<Exception>()
        val writer = AgentMascotIdentityWriter(agentId, FakeSettings(), backgroundScope, { loaded }, remote::push, onFailed = { failures += it })

        writer.write(turned)
        advanceUntilIdle()
        writer.write(turned.copy(shape = MascotShape.PILL))
        advanceUntilIdle()

        assertEquals(listOf("offline"), failures.map { it.message })
        assertEquals("pill:ff14a08a:30", remote.sent.single().identity())
        assertEquals(JsonPrimitive("core"), remote.sent.single()["team"])
    }
}
