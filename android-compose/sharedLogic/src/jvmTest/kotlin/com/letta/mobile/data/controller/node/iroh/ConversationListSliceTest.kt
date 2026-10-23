package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** The per-agent page must be what the App Server's own listLocalConversations returns for the same request. */
class ConversationListSliceTest {
    // Newest first, as the App Server sorts the full list: a's and b's rows interleaved, one agent-free row.
    private val full: JsonArray = buildJsonArray {
        listOf("a1" to "a", "b1" to "b", "a2" to "a", "free" to null, "a3" to "a", "b2" to "b", "a4" to "a").forEach { (id, agent) ->
            add(buildJsonObject { put("id", id); if (agent == null) put("agent_id", JsonNull) else put("agent_id", "agent-$agent") })
        }
    }

    private fun page(agent: String, after: String? = null, limit: String? = null): List<String> =
        agentConversationPage(full, AgentId("agent-$agent"), after?.let(::ConversationId), limit)
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test
    fun anAgentsRowsKeepTheFullListsOrder() {
        assertEquals(listOf("a1", "a2", "a3", "a4"), page("a"))
        assertEquals(listOf("b1", "b2"), page("b"))
    }

    @Test
    fun afterStartsPastThatRowAndAnUnknownIdStartsFromTheTop() {
        assertEquals(listOf("a3", "a4"), page("a", after = "a2"))
        assertEquals(listOf("a1", "a2", "a3", "a4"), page("a", after = "not-a-row"))
        assertEquals(listOf("a1", "a2", "a3", "a4"), page("a", after = "b1"))
    }

    @Test
    fun limitIsANumberOrTwentyAndANegativeOneDropsFromTheEnd() {
        assertEquals(listOf("a1", "a2"), page("a", limit = "2"))
        assertEquals(listOf("a1", "a2", "a3", "a4"), page("a", limit = "fifty"))
        assertEquals(listOf("a1", "a2", "a3"), page("a", limit = "-1"))
        assertEquals(emptyList(), page("a", limit = "0"))
        assertEquals(listOf("a1", "a2", "a3", "a4"), page("a", limit = "99999999999"))
    }

    @Test
    fun agentFreeRowsAreInNoAgentsPage() {
        assertEquals(emptyList(), page("missing"))
        assertEquals(false, page("a").contains("free"))
    }
}
