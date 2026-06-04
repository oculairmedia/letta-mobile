package com.letta.mobile.feature.chat.subagent

import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource.Companion.activeOnly
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * letta-mobile-73o2h.2: unit coverage for the active-subagent state model
 * and the WS-seam source/operator (no Android dependencies).
 */
@Tag("unit")
class ActiveSubagentSourceTest {

    private fun running(id: String) = ActiveSubagent(
        id = id,
        description = "desc $id",
        subagentType = "general",
        status = ActiveSubagent.Status.RUNNING,
    )

    @Test
    fun `isActive only true for running`() {
        assertTrue(running("a").isActive)
        assertFalse(running("a").copy(status = ActiveSubagent.Status.COMPLETED).isActive)
        assertFalse(running("a").copy(status = ActiveSubagent.Status.FAILED).isActive)
    }

    @Test
    fun `activeOnly filters terminal statuses`() = runTest {
        val source = FakeActiveSubagentSource(
            listOf(
                running("a"),
                running("b").copy(status = ActiveSubagent.Status.COMPLETED),
                running("c").copy(status = ActiveSubagent.Status.FAILED),
            ),
        )

        val active = source.activeSubagents.activeOnly().first()

        assertEquals(listOf("a"), active.map { it.id })
    }

    @Test
    fun `empty source emits empty active list - hidden case`() = runTest {
        val source = FakeActiveSubagentSource()
        assertTrue(source.activeSubagents.activeOnly().first().isEmpty())
    }

    @Test
    fun `upsert adds then updates by stable id`() = runTest {
        val source = FakeActiveSubagentSource()
        source.upsert(running("task_1"))
        assertEquals(1, source.activeSubagents.value.size)

        // Same id -> update in place, not duplicate (stable identity).
        source.upsert(running("task_1").copy(description = "updated"))
        assertEquals(1, source.activeSubagents.value.size)
        assertEquals("updated", source.activeSubagents.value.first().description)
    }

    @Test
    fun `setStatus transition removes from active-only view`() = runTest {
        val source = FakeActiveSubagentSource(listOf(running("task_1")))
        assertEquals(1, source.activeSubagents.activeOnly().first().size)

        source.setStatus("task_1", ActiveSubagent.Status.COMPLETED)
        assertTrue(source.activeSubagents.activeOnly().first().isEmpty())
    }

    @Test
    fun `setActive replaces the whole snapshot`() = runTest {
        val source = FakeActiveSubagentSource(listOf(running("a")))
        source.setActive(listOf(running("b"), running("c")))
        assertEquals(listOf("b", "c"), source.activeSubagents.value.map { it.id })
    }

    @Test
    fun `sample produces requested count of running subagents`() {
        val source = FakeActiveSubagentSource.sample(3)
        assertEquals(3, source.activeSubagents.value.size)
        assertTrue(source.activeSubagents.value.all { it.isActive })
    }
}
