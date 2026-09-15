package com.letta.mobile.feature.editagent

import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotPalette
import com.letta.mobile.avatar.core.MascotShape
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.ui.mascot.MASCOT_IDENTITY_METADATA_KEY
import com.letta.mobile.ui.mascot.mascotIdentitySettingsKey
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("unit")
class EditAgentMascotIdentityWriterTest {
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

    /** Records each PATCH's metadata; a write can be held open to model a slow network. */
    private class RecordingRepository {
        val sent = mutableListOf<Map<String, JsonElement>>()
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        var inFlight = 0
        var maxInFlight = 0
        var failNext = false
        val repository: IAgentRepository = mockk(relaxed = true)

        init {
            coEvery { repository.updateAgent(any<AgentId>(), any<AgentUpdateParams>()) } coAnswers {
                val params = secondArg<AgentUpdateParams>()
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                try {
                    gates.removeFirstOrNull()?.await()
                    if (failNext) {
                        failNext = false
                        error("offline")
                    }
                    sent += params.metadata.orEmpty()
                    Agent(id = AgentId("agent-42"), name = "Forty-two", metadata = params.metadata.orEmpty())
                } finally {
                    inFlight--
                }
            }
        }
    }

    private fun unexpected(e: Exception): Nothing = throw AssertionError("unexpected write failure", e)

    private fun Map<String, JsonElement>.identity() = this[MASCOT_IDENTITY_METADATA_KEY]?.jsonPrimitive?.content

    @Test
    fun `a pick is cached at once and written to the agent with its turn and the other metadata`() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings()
        val remote = RecordingRepository()
        val writer = EditAgentMascotIdentityWriter(agentId, remote.repository, settings, backgroundScope, { loaded }, onFailed = ::unexpected)

        writer.write(turned)
        advanceUntilIdle()

        assertEquals("hexagon:ff14a08a:30", settings.values[mascotIdentitySettingsKey(agentId)])
        assertEquals(1, remote.sent.size)
        assertEquals("hexagon:ff14a08a:30", remote.sent.single().identity())
        assertEquals(JsonPrimitive("core"), remote.sent.single()["team"])
    }

    @Test
    fun `picks made while a write is in flight end on the last one without overlapping writes`() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings()
        val remote = RecordingRepository()
        val slow = CompletableDeferred<Unit>().also { remote.gates += it }
        val writer = EditAgentMascotIdentityWriter(agentId, remote.repository, settings, backgroundScope, { loaded }, onFailed = ::unexpected)

        writer.write(turned)
        listOf(45, 90, 135).forEach { writer.write(turned.copy(rotationDegrees = it)) }
        assertEquals("the device cache already holds the newest pick", "hexagon:ff14a08a:135", settings.values[mascotIdentitySettingsKey(agentId)])

        slow.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("hexagon:ff14a08a:30", "hexagon:ff14a08a:135"), remote.sent.map { it.identity() })
        assertEquals(1, remote.maxInFlight)
    }

    @Test
    fun `a failed write leaves the next one merging into the loaded metadata`() = runTest(UnconfinedTestDispatcher()) {
        val remote = RecordingRepository().apply { failNext = true }
        val failures = mutableListOf<Exception>()
        val writer = EditAgentMascotIdentityWriter(agentId, remote.repository, FakeSettings(), backgroundScope, { loaded }, onFailed = { failures += it })

        writer.write(turned)
        advanceUntilIdle()
        writer.write(turned.copy(shape = MascotShape.PILL))
        advanceUntilIdle()

        assertEquals(listOf("offline"), failures.map { it.message })
        assertEquals("pill:ff14a08a:30", remote.sent.single().identity())
        assertEquals(JsonPrimitive("core"), remote.sent.single()["team"])
    }
}
