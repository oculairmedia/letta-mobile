package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-fxoew.9: conversations owned by hidden (subagent) agents stay out of the chat list. */
class HiddenAgentConversationsTest {
    private fun record(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun agentHiddenRuleMatchesLettaCode() {
        assertTrue(record("""{"id":"a","hidden":true}""").isHiddenAgentRecord())
        assertTrue(record("""{"id":"a","tags":["type:reflection","role:subagent"]}""").isHiddenAgentRecord())
        assertTrue(record("""{"id":"a","hidden":null,"tags":["role:subagent"]}""").isHiddenAgentRecord())
        // An explicit boolean wins over the tag, as in isHiddenLocalAgentRecord.
        assertFalse(record("""{"id":"a","hidden":false,"tags":["role:subagent"]}""").isHiddenAgentRecord())
        assertFalse(record("""{"id":"a","tags":["origin:letta-code"]}""").isHiddenAgentRecord())
        assertFalse(record("""{"id":"a","hidden":"true"}""").isHiddenAgentRecord())
    }

    @Test
    fun dropsOnlyRowsOwnedByHiddenAgents() {
        val rows = listOf(
            buildJsonObject { put("id", "conv-meridian"); put("agent_id", "agent-meridian") },
            buildJsonObject { put("id", "conv-sub"); put("agent_id", "agent-local-sub") },
            buildJsonObject { put("id", "conv-unowned") },
        )
        val visible = withoutHiddenAgentConversations(rows, setOf("agent-local-sub"))
        assertEquals(listOf("conv-meridian", "conv-unowned"), visible.map { (it as JsonObject)["id"].toString().trim('"') })
    }

    @Test
    fun indexCachesAndKeepsTheLastGoodSetOnFailure() {
        var now = 0L
        var loads = 0
        var next: Set<String>? = setOf("agent-a")
        val index = HiddenAgentIndex(load = { loads++; next }, clock = { now }, ttlMs = 1_000L)

        assertEquals(setOf("agent-a"), index.hiddenAgentIds())
        next = setOf("agent-b")
        now = 999L
        assertEquals(setOf("agent-a"), index.hiddenAgentIds(), "cached within the ttl")
        assertEquals(1, loads)

        now = 1_000L
        assertEquals(setOf("agent-b"), index.hiddenAgentIds())
        next = null
        now = 2_000L
        assertEquals(setOf("agent-b"), index.hiddenAgentIds(), "a failed read keeps the last good set")
    }

    @Test
    fun localBackendStoreReadsHiddenAgentsFromAgentRecords() {
        val root = createTempDirectory("fxoew9-agents").toFile()
        val agents = File(root, "agents").apply { mkdirs() }
        File(agents, "visible.json").writeText("""{"id":"agent-meridian","tags":["origin:letta-code"]}""")
        File(agents, "sub.json").writeText("""{"id":"agent-local-sub","tags":["role:subagent","type:general-purpose"]}""")
        File(agents, "hidden.json").writeText("""{"id":"agent-local-reflect","hidden":true}""")

        assertEquals(setOf("agent-local-sub", "agent-local-reflect"), LocalBackendAdminStore(root).hiddenAgentIds())
    }
}
