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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A host's list carries agent-free worker conversations (`agent_id: null`); the list leaves them out by rule. */
class ConversationListDecodingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun row(id: String, agentId: String?) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id),
            "agent_id" to (agentId?.let(::JsonPrimitive) ?: JsonNull),
        ),
    )

    /** A row with an agent that this client still cannot decode (an id that is not a string). */
    private fun malformed(id: String) = JsonObject(mapOf("id" to JsonObject(emptyMap()), "agent_id" to JsonPrimitive(id)))

    @Test
    fun anAgentFreeRowIsLeftOutAndTheRestStayInOrder() {
        val listed = json.decodeConversationList(JsonArray(listOf(row("c1", "a1"), row("c2", null), row("c3", "a2"))))

        assertEquals(listOf("c1", "c3"), listed.map { it.id.value })
    }

    @Test
    fun agentFreeRowsAreExcludedByRuleNotReportedAsSkipped() {
        val reports = mutableListOf<String>()
        json.decodeConversationList(JsonArray(listOf(row("c1", "a1"), row("c2", null), row("c3", "")))) { reports += it }

        assertTrue(reports.isEmpty(), reports.toString())
    }

    @Test
    fun malformedRowsAreSkippedAndReportedOnce() {
        val reports = mutableListOf<String>()
        val listed = json.decodeConversationList(JsonArray(listOf(row("c1", "a1"), malformed("a2"), malformed("a3")))) { reports += it }

        assertEquals(listOf("c1"), listed.map { it.id.value })
        assertEquals(1, reports.size)
        assertTrue(reports.single().startsWith("skipped=2 kept=1 "), reports.single())
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

    @Test
    fun aRowIsAgentFreeOnlyWithoutAnAgent() {
        assertTrue(HiddenListFilter.isAgentFreeRow(JsonObject(mapOf("id" to JsonPrimitive("c1")))))
        assertTrue(HiddenListFilter.isAgentFreeRow(row("c1", null)))
        assertTrue(HiddenListFilter.isAgentFreeRow(row("c1", " ")))
        assertFalse(HiddenListFilter.isAgentFreeRow(row("c1", "agent-1")))
    }
}
