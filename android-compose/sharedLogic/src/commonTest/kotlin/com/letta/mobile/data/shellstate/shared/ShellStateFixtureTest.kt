package com.letta.mobile.data.shellstate.shared

import com.letta.mobile.data.shellstate.ShellStateEditOutcome
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.wire.ShellStateError
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Every shell-state v1 fixture case, decoded through the client's own types and replayed into [SharedShellState]. */
class ShellStateFixtureTest {
    private val fixtures = mapOf(ShellStateWire.GET to shellStateGetContractText, ShellStateWire.PROPOSE to shellStateProposeContractText)

    @Test
    fun everyFixtureNamesItsMethodAndTheContract() {
        fixtures.forEach { (method, text) ->
            val root = Json.parseToJsonElement(text).jsonObject
            assertEquals(method, root.getValue("method").jsonPrimitive.content)
            assertEquals(ShellStateWire.CAPABILITY, root.getValue("capability").jsonPrimitive.content)
        }
    }

    @Test
    fun everyResultAndPushDecodesToTheSameDocumentAndEveryErrorIsKnown() {
        cases().forEach { (_, case) ->
            val response = case.getValue("expected_response").jsonObject
            if (response.getValue("success").jsonPrimitive.boolean) {
                val result = response.getValue("result")
                val snapshot = decode(result)
                assertEquals(result, LettaJson.persisted.encodeToJsonElement(ShellStateSnapshot.serializer(), snapshot))
                case["pushed_frame"]?.let { frame ->
                    assertEquals(ShellStateWire.FRAME_TYPE, frame.jsonObject.getValue("type").jsonPrimitive.content)
                    assertEquals(snapshot, decode(frame))
                }
            } else {
                val error = response.getValue("error").jsonPrimitive.content
                assertNotEquals(ShellStateError.UNKNOWN, ShellStateError.fromWire(error), "unmapped code $error")
            }
        }
    }

    @Test
    fun everyStoredEntryRecordsTheConnectionsPersonAsItsActor() {
        cases().forEach { (_, case) ->
            val response = case.getValue("expected_response").jsonObject
            if (!response.getValue("success").jsonPrimitive.boolean) return@forEach
            decode(response.getValue("result")).entries.values.forEach { entry -> assertIs<ShellStateActor.User>(entry.updatedBy) }
        }
    }

    @Test
    fun aDocumentWithAnUnknownActorKindIsRefusedWholeNotHalfRead() {
        val future = Json.parseToJsonElement(
            """{"schema_version":1,"document_revision":2,"entries":{"a":{"value":1,"revision":2,
               "updated_by":{"type":"robot"},"updated_at_ms":1}}}""",
        )
        val decoded = runCatching { decode(future) }
        assertTrue(decoded.isFailure)
    }

    @Test
    fun theClientAdoptsEverySetAnswerAsTheContractSays() = runTest {
        cases().filter { (method, _) -> method == ShellStateWire.PROPOSE }.forEach { (_, case) ->
            val stored = case.getValue("host_state").jsonObject.getValue("shell_state").jsonObject["stored"]
            val start = stored?.takeUnless { it is JsonNull }?.let(::decode) ?: EMPTY
            val response = case.getValue("expected_response").jsonObject
            val state = SharedShellState(ReplayLink(start, response))
            state.refresh()
            val outcome = state.set("layout.new", JsonPrimitive(1))
            val name = case.getValue("name").jsonPrimitive.content
            when (val error = response["error"]?.jsonPrimitive?.content) {
                null -> {
                    assertEquals(ShellStateEditOutcome.APPLIED, outcome, name)
                    assertEquals(decode(response.getValue("result")), state.state.value.snapshot, name)
                }
                ShellStateWire.REVISION_CONFLICT -> assertEquals(ShellStateEditOutcome.CONFLICT, outcome, name)
                else -> {
                    assertEquals(ShellStateEditOutcome.REFUSED, outcome, "$name ($error)")
                    assertEquals(start, state.state.value.snapshot, "$name leaves the shown document alone")
                }
            }
        }
    }

    private fun cases(): List<Pair<String, JsonObject>> = fixtures.flatMap { (method, text) ->
        Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.map { method to it.jsonObject }
    }.also { assertTrue(it.isNotEmpty()) }

    private fun decode(element: JsonElement): ShellStateSnapshot =
        LettaJson.wire.decodeFromJsonElement(ShellStateSnapshot.serializer(), element)

    /** Answers `shell.state.get` with [start] and `shell.state.propose` with the fixture's own response. */
    private class ReplayLink(private val start: ShellStateSnapshot, private val setResponse: JsonObject) : ShellStateLink {
        override val hostCapabilities: Set<String> = setOf(ShellStateWire.CAPABILITY)
        override val currentConnection: String? = "session"
        override val connection: Flow<String?> = flowOf("session")
        override val pushes: Flow<JsonObject> = emptyFlow()

        override suspend fun call(method: String, params: JsonObject): ShellStateReply = when (method) {
            ShellStateWire.GET -> ShellStateReply(true, LettaJson.persisted.encodeToJsonElement(ShellStateSnapshot.serializer(), start))
            else -> ShellStateReply(
                setResponse.getValue("success").jsonPrimitive.boolean,
                setResponse["result"],
                setResponse["error"]?.jsonPrimitive?.content,
            )
        }
    }

    private companion object {
        val EMPTY = ShellStateSnapshot(schemaVersion = 1, documentRevision = 0, entries = emptyMap())
    }
}
