package com.letta.mobile.data.controller.registry

import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest

class RuntimeRegistryTest {
    @Test
    fun saveAndLoadRuntimeRecord() = runTest {
        val registry = InMemoryRuntimeRegistry()

        val record = RuntimeRecord(
            id = "record-1",
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            cwd = "/workspace",
            displayName = "Chat with Agent 1",
            role = "assistant",
        )

        registry.save(record)

        val loaded = registry.load("record-1")
        assertNotNull(loaded)
        assertEquals(record.id, loaded.id)
        assertEquals(record.agentId, loaded.agentId)
        assertEquals(record.conversationId, loaded.conversationId)
        assertEquals(record.cwd, loaded.cwd)
        assertEquals(record.displayName, loaded.displayName)
        assertEquals(record.role, loaded.role)
        assertNull(loaded.lastStartedAt)
        assertNull(loaded.canonicalRuntime)
    }

    @Test
    fun loadNonexistentRecordReturnsNull() = runTest {
        val registry = InMemoryRuntimeRegistry()

        val loaded = registry.load("nonexistent")
        assertNull(loaded)
    }

    @Test
    fun listReturnsAllRecords() = runTest {
        val registry = InMemoryRuntimeRegistry()

        val record1 = RuntimeRecord(
            id = "record-1",
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
        )
        val record2 = RuntimeRecord(
            id = "record-2",
            agentId = AgentId("agent-2"),
            conversationId = ConversationId("conv-2"),
        )
        val record3 = RuntimeRecord(
            id = "record-3",
            agentId = AgentId("agent-3"),
            conversationId = ConversationId("conv-3"),
        )

        registry.save(record1)
        registry.save(record2)
        registry.save(record3)

        val allRecords = registry.list()
        assertEquals(3, allRecords.size)
        assertEquals(setOf("record-1", "record-2", "record-3"), allRecords.map { it.id }.toSet())
    }

    @Test
    fun listReturnsEmptyListWhenNoRecords() = runTest {
        val registry = InMemoryRuntimeRegistry()

        val allRecords = registry.list()
        assertEquals(0, allRecords.size)
    }

    @Test
    fun removeDeletesRecord() = runTest {
        val registry = InMemoryRuntimeRegistry()

        val record = RuntimeRecord(
            id = "record-1",
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
        )

        registry.save(record)
        assertNotNull(registry.load("record-1"))

        registry.remove("record-1")
        assertNull(registry.load("record-1"))

        val allRecords = registry.list()
        assertEquals(0, allRecords.size)
    }

    @Test
    fun removeNonexistentRecordIsNoOp() = runTest {
        val registry = InMemoryRuntimeRegistry()

        // Should not throw
        registry.remove("nonexistent")
    }

    @Test
    fun saveReplacesExistingRecord() = runTest {
        val registry = InMemoryRuntimeRegistry()

        val record1 = RuntimeRecord(
            id = "record-1",
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            displayName = "Original Name",
        )

        registry.save(record1)

        val record2 = record1.copy(displayName = "Updated Name")
        registry.save(record2)

        val loaded = registry.load("record-1")
        assertNotNull(loaded)
        assertEquals("Updated Name", loaded.displayName)

        // Should still only have one record
        assertEquals(1, registry.list().size)
    }

    @Test
    fun markStartedUpdatesRecordWithCanonicalRuntime() = runTest {
        val registry = InMemoryRuntimeRegistry()

        val record = RuntimeRecord(
            id = "record-1",
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
        )

        registry.save(record)

        val canonicalRuntime = CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = "agent-1",
                conversationId = "conv-1",
            ),
        )
        val startedAt = Clock.System.now()

        registry.markStarted(
            id = "record-1",
            canonicalRuntime = canonicalRuntime,
            lastStartedAt = startedAt,
        )

        val loaded = registry.load("record-1")
        assertNotNull(loaded)
        assertEquals(canonicalRuntime, loaded.canonicalRuntime)
        assertEquals(startedAt, loaded.lastStartedAt)
    }

    @Test
    fun markStartedThrowsWhenRecordNotFound() = runTest {
        val registry = InMemoryRuntimeRegistry()

        val canonicalRuntime = CanonicalRuntime(
            scope = AppServerRuntimeScope(
                agentId = "agent-1",
                conversationId = "conv-1",
            ),
        )

        val exception = assertFailsWith<RuntimeRegistryException> {
            registry.markStarted(
                id = "nonexistent",
                canonicalRuntime = canonicalRuntime,
                lastStartedAt = Clock.System.now(),
            )
        }

        assertEquals("Runtime record not found: nonexistent", exception.message)
    }

    @Test
    fun findByAgentAndConversationReturnsMatchingRecord() = runTest {
        val registry = InMemoryRuntimeRegistry()

        val record1 = RuntimeRecord(
            id = "record-1",
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
        )
        val record2 = RuntimeRecord(
            id = "record-2",
            agentId = AgentId("agent-2"),
            conversationId = ConversationId("conv-2"),
        )

        registry.save(record1)
        registry.save(record2)

        val found = registry.findByAgentAndConversation(
            agentId = AgentId("agent-2"),
            conversationId = ConversationId("conv-2"),
        )

        assertNotNull(found)
        assertEquals("record-2", found.id)
        assertEquals(AgentId("agent-2"), found.agentId)
        assertEquals(ConversationId("conv-2"), found.conversationId)
    }

    @Test
    fun findByAgentAndConversationReturnsNullWhenNotFound() = runTest {
        val registry = InMemoryRuntimeRegistry()

        val record = RuntimeRecord(
            id = "record-1",
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
        )

        registry.save(record)

        val found = registry.findByAgentAndConversation(
            agentId = AgentId("agent-2"),
            conversationId = ConversationId("conv-2"),
        )

        assertNull(found)
    }

    @Test
    fun findByAgentAndConversationMatchesBothFields() = runTest {
        val registry = InMemoryRuntimeRegistry()

        val record1 = RuntimeRecord(
            id = "record-1",
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
        )
        val record2 = RuntimeRecord(
            id = "record-2",
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-2"),
        )
        val record3 = RuntimeRecord(
            id = "record-3",
            agentId = AgentId("agent-2"),
            conversationId = ConversationId("conv-1"),
        )

        registry.save(record1)
        registry.save(record2)
        registry.save(record3)

        // Should only match exact agent+conversation pair
        val found = registry.findByAgentAndConversation(
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-2"),
        )

        assertNotNull(found)
        assertEquals("record-2", found.id)
    }
}
