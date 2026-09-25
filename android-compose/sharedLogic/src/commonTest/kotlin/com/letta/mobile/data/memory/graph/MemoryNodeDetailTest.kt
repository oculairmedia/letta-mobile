package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.memory.MemoryGraphNodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryNodeDetailTest {
    private val memory = MemoryGraphFixtures.memory()

    @Test
    fun blockNodeResolvesAnAgentScopedBlockRef() {
        val detail = MemoryNodeDetails.resolve(memory, MemoryGraphFixtures.blockNodeId(MemoryGraphFixtures.human))

        requireNotNull(detail)
        assertEquals(MemoryBlockRef(MemoryGraphFixtures.AGENT_ID, "human", "block-human"), detail.blockRef)
        assertEquals(40, detail.limit)
        assertEquals("Name: Emmanuel", detail.body)
    }

    @Test
    fun readOnlyBlockIsFlagged() {
        val detail = MemoryNodeDetails.resolve(memory, MemoryGraphFixtures.blockNodeId(MemoryGraphFixtures.locked))

        assertTrue(requireNotNull(detail).readOnly)
    }

    @Test
    fun skillNodeUsesTheOverviewItemText() {
        val detail = MemoryNodeDetails.resolve(memory, MemoryGraphFixtures.skillNodeId(MemoryGraphFixtures.search))

        requireNotNull(detail)
        assertEquals(MemoryGraphNodeKind.Skill, detail.kind)
        assertEquals("Search the web", detail.body)
        assertEquals(listOf("tool"), detail.metadataLabels)
        assertNull(detail.blockRef)
    }

    @Test
    fun agentRootResolvesWithoutAnItem() {
        val detail = MemoryNodeDetails.resolve(memory, MemoryGraphFixtures.ROOT_ID)

        requireNotNull(detail)
        assertEquals("Ada", detail.title)
        assertNull(detail.blockRef)
    }

    @Test
    fun unknownNodeResolvesToNull() {
        assertNull(MemoryNodeDetails.resolve(memory, "memory:nope"))
    }

    @Test
    fun blockWithoutASelectedAgentIsNotEditable() {
        val detail = MemoryNodeDetails.resolve(
            memory.copy(selectedAgentId = null),
            MemoryGraphFixtures.blockNodeId(MemoryGraphFixtures.persona),
        )

        assertNull(requireNotNull(detail).blockRef)
    }
}
