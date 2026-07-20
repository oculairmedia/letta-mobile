package com.letta.mobile.data.repository.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ISubagentRepositoryContractTest {
    @Test
    fun `active subagent projection requires an explicit parent scope`() {
        val overloads = ISubagentRepository::class.java.methods
            .filter { it.name == "activeSubagentsFlow" }

        assertFalse(overloads.any { it.parameterCount == 0 })
        assertTrue(overloads.isNotEmpty())
        overloads.forEach { method ->
            assertEquals(listOf(SubagentParentScope::class.java), method.parameterTypes.toList())
        }
    }

    @Test
    fun `current active subagent projection requires an explicit parent scope`() {
        val overloads = ISubagentRepository::class.java.methods
            .filter { it.name == "currentActiveSubagents" }

        assertFalse(overloads.any { it.parameterCount == 0 })
        assertTrue(overloads.isNotEmpty())
        overloads.forEach { method ->
            assertEquals(listOf(SubagentParentScope::class.java), method.parameterTypes.toList())
        }
    }
}
