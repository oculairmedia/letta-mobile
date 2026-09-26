package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.memory.MemoryGraphNodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryGraphViewTest {
    private val graph = MemoryGraphFixtures.memory().graph

    @Test
    fun buildCountsDegreesOverVisibleEdges() {
        val view = MemoryGraphViews.build(graph, emptySet())

        assertEquals(4, view.degreeOf(MemoryGraphFixtures.ROOT_ID))
        assertEquals(1, view.degreeOf(MemoryGraphFixtures.blockNodeId(MemoryGraphFixtures.persona)))
        assertEquals(
            listOf(MemoryGraphNodeKind.Agent, MemoryGraphNodeKind.Skill, MemoryGraphNodeKind.Memory),
            view.kindsPresent,
        )
    }

    @Test
    fun disabledKindDropsItsNodesAndEdges() {
        val view = MemoryGraphViews.build(graph, setOf(MemoryGraphNodeKind.Skill))

        assertNull(view.node(MemoryGraphFixtures.skillNodeId(MemoryGraphFixtures.search)))
        assertEquals(3, view.degreeOf(MemoryGraphFixtures.ROOT_ID))
        assertTrue(view.edges.none { it.toId == MemoryGraphFixtures.skillNodeId(MemoryGraphFixtures.search) })
        assertFalse(view.isKindEnabled(MemoryGraphNodeKind.Skill))
    }

    @Test
    fun disabledKindsAbsentFromTheGraphAreIgnored() {
        val view = MemoryGraphViews.build(graph, setOf(MemoryGraphNodeKind.Channel))

        assertTrue(view.disabledKinds.isEmpty())
        assertEquals(graph.nodes.size, view.nodes.size)
    }

    @Test
    fun toggleDisablesThenReenablesAKind() {
        val present = MemoryGraphViews.kindsPresent(graph)

        val off = MemoryGraphViews.toggle(emptySet(), present, MemoryGraphNodeKind.Skill)
        val on = MemoryGraphViews.toggle(off, present, MemoryGraphNodeKind.Skill)

        assertEquals(setOf(MemoryGraphNodeKind.Skill), off)
        assertTrue(on.isEmpty())
    }

    @Test
    fun toggleNeverDisablesTheLastEnabledKind() {
        val present = MemoryGraphViews.kindsPresent(graph)
        val onlyMemoryLeft = setOf(MemoryGraphNodeKind.Agent, MemoryGraphNodeKind.Skill)

        val result = MemoryGraphViews.toggle(onlyMemoryLeft, present, MemoryGraphNodeKind.Memory)

        assertEquals(onlyMemoryLeft, result)
    }

    @Test
    fun sameTopologyIgnoresTextChanges() {
        val before = MemoryGraphViews.build(graph, emptySet())
        val renamed = graph.copy(nodes = graph.nodes.map { it.copy(title = it.title + "!") })
        val after = MemoryGraphViews.build(renamed, emptySet())

        assertTrue(before.sameTopologyAs(after))
        assertFalse(before.sameTopologyAs(MemoryGraphViews.build(graph, setOf(MemoryGraphNodeKind.Skill))))
    }
}
