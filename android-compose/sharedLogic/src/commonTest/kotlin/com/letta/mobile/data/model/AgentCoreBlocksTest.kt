package com.letta.mobile.data.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Memory page reported 0 blocks for an agent the backend was demonstrably
 * returning 14 blocks for. Cause: [Agent] decoded only the TOP-LEVEL `blocks`,
 * while canonical Letta `AgentState` nests core memory under `memory.blocks` —
 * so the tolerant decoder silently dropped it. [Agent.coreBlocks] must resolve
 * either shape.
 */
class AgentCoreBlocksTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun resolvesTopLevelBlocksShape() {
        val agent = json.decodeFromString<Agent>(
            """
            {"id":"agent-1","name":"Meridian",
             "blocks":[{"id":"block-a","label":"persona","value":"v"}]}
            """.trimIndent(),
        )
        assertEquals(listOf("block-a"), agent.coreBlocks.map { it.id.value })
    }

    @Test
    fun resolvesCanonicalNestedMemoryShape() {
        // The shape that rendered 0 blocks before this fix.
        val agent = json.decodeFromString<Agent>(
            """
            {"id":"agent-1","name":"Meridian",
             "memory":{"blocks":[{"id":"block-a","label":"persona","value":"v"},
                                 {"id":"block-b","label":"human","value":"w"}]}}
            """.trimIndent(),
        )
        assertEquals(listOf("block-a", "block-b"), agent.coreBlocks.map { it.id.value })
    }

    @Test
    fun topLevelWinsWhenBothShapesPresent() {
        // The admin shim emits both; they should agree, but pin a deterministic
        // precedence so a disagreement cannot silently change what is displayed.
        val agent = json.decodeFromString<Agent>(
            """
            {"id":"agent-1","name":"Meridian",
             "blocks":[{"id":"top","label":"persona","value":"v"}],
             "memory":{"blocks":[{"id":"nested","label":"persona","value":"v"}]}}
            """.trimIndent(),
        )
        assertEquals(listOf("top"), agent.coreBlocks.map { it.id.value })
    }

    @Test
    fun explicitEmptyTopLevelWinsOverNestedBlocks() {
        val agent = json.decodeFromString<Agent>(
            """
            {"id":"agent-1","name":"Meridian","blocks":[],
             "memory":{"blocks":[{"id":"stale","label":"persona","value":"old"}]}}
            """.trimIndent(),
        )
        assertEquals(emptyList(), agent.coreBlocks)
    }

    @Test
    fun emptyWhenNeitherShapeCarriesBlocks() {
        val agent = json.decodeFromString<Agent>("""{"id":"agent-1","name":"Meridian"}""")
        assertEquals(emptyList(), agent.coreBlocks)
        assertEquals(null, agent.memory)
    }

    @Test
    fun nestedFileBlocksAreDecodedButNotCoreMemory() {
        // memory.file_blocks is a separate concept (memory filesystem); it must
        // not leak into the core-memory block list the Memory page counts.
        val agent = json.decodeFromString<Agent>(
            """
            {"id":"agent-1","name":"Meridian",
             "memory":{"blocks":[{"id":"core","label":"persona","value":"v"}],
                       "file_blocks":[{"id":"file","label":"notes.md","value":"x"}]}}
            """.trimIndent(),
        )
        assertEquals(listOf("core"), agent.coreBlocks.map { it.id.value })
        assertEquals(listOf("file"), agent.memory?.fileBlocks?.map { it.id.value })
    }
}
