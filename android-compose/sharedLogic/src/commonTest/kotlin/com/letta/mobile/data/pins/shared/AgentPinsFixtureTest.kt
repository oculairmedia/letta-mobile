package com.letta.mobile.data.pins.shared

import com.letta.mobile.data.pins.wire.AgentPinsError
import com.letta.mobile.data.pins.wire.AgentPinsWire
import com.letta.mobile.data.serialization.LettaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Every pins v1 fixture case, decoded through the client's own types and replayed into [SharedAgentPins]. */
class AgentPinsFixtureTest {
    private val fixtures = mapOf(AgentPinsWire.GET to pinsGetContractText, AgentPinsWire.SET to pinsSetContractText)

    @Test
    fun everyFixtureNamesItsMethodAndTheContract() {
        fixtures.forEach { (method, text) ->
            val root = Json.parseToJsonElement(text).jsonObject
            assertEquals(method, root.getValue("method").jsonPrimitive.content)
            assertEquals(AgentPinsWire.CAPABILITY, root.getValue("capability").jsonPrimitive.content)
        }
    }

    @Test
    fun everyResultAndPushDecodesToTheSameListAndEveryErrorIsKnown() {
        cases().forEach { (_, case) ->
            val response = case.getValue("expected_response").jsonObject
            if (response.getValue("success").jsonPrimitive.boolean) {
                val result = response.getValue("result")
                val snapshot = decode(result)
                assertEquals(result, LettaJson.persisted.encodeToJsonElement(AgentPinsSnapshot.serializer(), snapshot))
                case["pushed_frame"]?.let { frame ->
                    assertEquals(AgentPinsWire.FRAME_TYPE, frame.jsonObject.getValue("type").jsonPrimitive.content)
                    assertEquals(snapshot, decode(frame))
                }
            } else {
                val error = response.getValue("error").jsonPrimitive.content
                assertNotEquals(AgentPinsError.UNKNOWN, AgentPinsError.fromWire(error), "unmapped code $error")
            }
        }
    }

    @Test
    fun theClientAdoptsEverySetAnswerAsTheContractSays() = runTest {
        cases().filter { (method, _) -> method == AgentPinsWire.SET }.forEach { (_, case) ->
            val stored = case.getValue("host_state").jsonObject.getValue("pins").jsonObject["stored"]
            val start = stored?.takeUnless { it is JsonNull }?.let(::decode) ?: AgentPinsSnapshot()
            val response = case.getValue("expected_response").jsonObject
            val pins = SharedAgentPins(ReplayLink(start, response))
            pins.refresh()
            val outcome = pins.setPinned(com.letta.mobile.data.model.AgentId("agent-new"), true)
            val name = case.getValue("name").jsonPrimitive.content
            when (val error = response["error"]?.jsonPrimitive?.content) {
                null -> {
                    assertEquals(AgentPinsEditOutcome.APPLIED, outcome, name)
                    assertEquals(decode(response.getValue("result")), pins.state.value.snapshot, name)
                }
                AgentPinsWire.REVISION_CONFLICT -> assertEquals(AgentPinsEditOutcome.CONFLICT, outcome, name)
                else -> {
                    assertEquals(AgentPinsEditOutcome.REFUSED, outcome, "$name ($error)")
                    assertEquals(start, pins.state.value.snapshot, "$name rolls back")
                }
            }
        }
    }

    private fun cases(): List<Pair<String, JsonObject>> = fixtures.flatMap { (method, text) ->
        Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.map { method to it.jsonObject }
    }.also { assertTrue(it.isNotEmpty()) }

    private fun decode(element: JsonElement): AgentPinsSnapshot =
        LettaJson.wire.decodeFromJsonElement(AgentPinsSnapshot.serializer(), element)

    /** Answers `pins.get` with [start] and `pins.set` with the fixture's own response. */
    private class ReplayLink(private val start: AgentPinsSnapshot, private val setResponse: JsonObject) : AgentPinsLink {
        override val hostCapabilities: Set<String> = setOf(AgentPinsWire.CAPABILITY)
        override val isConnected: Boolean = true
        override val connection: Flow<Boolean> = flowOf(true)
        override val pushes: Flow<JsonObject> = emptyFlow()

        override suspend fun call(method: String, params: JsonObject): AgentPinsReply = when (method) {
            AgentPinsWire.GET -> AgentPinsReply(true, LettaJson.persisted.encodeToJsonElement(AgentPinsSnapshot.serializer(), start))
            else -> AgentPinsReply(
                setResponse.getValue("success").jsonPrimitive.boolean,
                setResponse["result"],
                setResponse["error"]?.jsonPrimitive?.content,
            )
        }
    }
}
