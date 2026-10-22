package com.letta.mobile.data.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A host's list carries agent-free conversations (`agent_id: null`); one such row must not hide the rest. */
class ConversationListDecodingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun row(id: String, agentId: String?) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "agent_id" to (agentId?.let(::JsonPrimitive) ?: JsonNull),
            "agent_free" to JsonPrimitive(agentId == null),
        ),
    )

    @Test
    fun anAgentFreeRowIsSkippedAndTheRestStayInOrder() {
        val listed = json.decodeConversationList(JsonArray(listOf(row("c1", "a1"), row("c2", null), row("c3", "a2"))))

        assertEquals(listOf("c1", "c3"), listed.map { it.id.value })
    }

    @Test
    fun theSkippedRowsAreReportedOnce() {
        val reports = mutableListOf<Pair<Int, Int>>()
        json.decodeConversationList(JsonArray(listOf(row("c1", "a1"), row("c2", null), row("c3", null)))) { skipped, kept, _ ->
            reports += skipped to kept
        }

        assertEquals(listOf(2 to 1), reports)
    }

    @Test
    fun aListOfOnlyAgentFreeRowsIsEmptyNotAnError() {
        val listed = json.decodeConversationList(JsonArray(List(3) { row("c$it", null) }))

        assertTrue(listed.isEmpty())
    }

    @Test
    fun aListWithNoBadRowDecodesWhole() {
        val listed = json.decodeConversationList(JsonArray(listOf(row("c1", "a1"), row("c2", "a2"))))

        assertEquals(listOf("c1", "c2"), listed.map { it.id.value })
    }

    @Test
    fun aPayloadThatIsNotAListStillFails() {
        assertFailsWith<SerializationException> { json.decodeConversationList(row("c1", "a1")) }
    }
}
