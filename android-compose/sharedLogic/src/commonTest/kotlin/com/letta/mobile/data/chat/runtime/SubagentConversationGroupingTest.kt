package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-5172y.1 acceptance: pure grouping of ephemeral Letta Code
 * subagent conversations by AUTHORITATIVE parent provenance.
 *
 * Backtick test names deliberately contain NO parentheses "()" — Kotlin/Native
 * rejects them (this broke #868).
 */
class SubagentConversationGroupingTest {
    private fun convo(
        id: String,
        agentId: String? = null,
        agentName: String = "agent",
    ): ChatConversationSummary =
        ChatConversationSummary(
            id = id,
            title = "Conversation $id",
            agentName = agentName,
            updatedAtLabel = "Remote",
            lastMessagePreview = "",
            agentId = agentId,
        )

    private fun entry(
        toolCallId: String,
        status: String = SubagentStatus.RUNNING,
        subagentConversationId: String? = null,
        subagentAgentId: String? = null,
        parentAgentId: String? = null,
        parentConversationId: String? = null,
        parentRunId: String? = null,
        subagentType: String = "coder",
        description: String = "task",
    ): SubagentEntry =
        SubagentEntry(
            toolCallId = toolCallId,
            description = description,
            subagentType = subagentType,
            status = status,
            subagentConversationId = subagentConversationId,
            subagentAgentId = subagentAgentId,
            parentAgentId = parentAgentId,
            parentConversationId = parentConversationId,
            parentRunId = parentRunId,
        )
    @Test
    fun `same-provenance entries group into one stack`() {
        val conversations = listOf(
            convo(id = "c1", agentId = "sa1"),
            convo(id = "c2", agentId = "sa2"),
        )
        val entries = listOf(
            entry(toolCallId = "t1", subagentConversationId = "c1", parentAgentId = "parentA"),
            entry(toolCallId = "t2", subagentConversationId = "c2", parentAgentId = "parentA"),
        )

        val result = groupSubagentConversations(conversations, entries)

        assertEquals(1, result.stacks.size)
        val stack = result.stacks.single()
        assertEquals(listOf("c1", "c2"), stack.memberConversationIds)
        assertEquals(2, stack.count)
        assertTrue(result.ungrouped.isEmpty())
        assertTrue(stack.provenance is SubagentProvenance.Known)
        assertEquals(
            "parentA",
            (stack.provenance as SubagentProvenance.Known).parentAgentId,
        )
    }

    @Test
    fun `same display name but different parentAgentId do not merge`() {
        // Identical name-ish fields (same subagentType + description + agentName)
        // but DIFFERENT parentAgentId must yield two distinct stacks.
        val conversations = listOf(
            convo(id = "c1", agentId = "sa1", agentName = "Explore"),
            convo(id = "c2", agentId = "sa2", agentName = "Explore"),
        )
        val entries = listOf(
            entry(
                toolCallId = "t1",
                subagentConversationId = "c1",
                subagentType = "explore",
                description = "look around",
                parentAgentId = "parentA",
            ),
            entry(
                toolCallId = "t2",
                subagentConversationId = "c2",
                subagentType = "explore",
                description = "look around",
                parentAgentId = "parentB",
            ),
        )

        val result = groupSubagentConversations(conversations, entries)

        assertEquals(2, result.stacks.size)
        assertEquals(setOf("known:parentA", "known:parentB"), result.stacks.map { it.stackKey }.toSet())
    }

    @Test
    fun `partial parent identifiers key on precedence parentConversationId then parentAgentId then parentRunId`() {
        // Two members carry ONLY parentAgentId (parentConversationId + parentRunId
        // null) -> both key on that parentAgentId and group together.
        // A third carries ONLY parentRunId -> keys on parentRunId, distinct stack.
        // A fourth carries BOTH parentConversationId and parentAgentId -> the
        // stronger parentConversationId wins the key.
        val conversations = listOf(
            convo(id = "c1", agentId = "sa1"),
            convo(id = "c2", agentId = "sa2"),
            convo(id = "c3", agentId = "sa3"),
            convo(id = "c4", agentId = "sa4"),
        )
        val entries = listOf(
            entry(toolCallId = "t1", subagentConversationId = "c1", parentAgentId = "pA"),
            entry(toolCallId = "t2", subagentConversationId = "c2", parentAgentId = "pA"),
            entry(toolCallId = "t3", subagentConversationId = "c3", parentRunId = "pRun"),
            entry(toolCallId = "t4", subagentConversationId = "c4", parentConversationId = "pConv", parentAgentId = "pA"),
        )

        val result = groupSubagentConversations(conversations, entries)

        // pA stack has c1 + c2; pRun stack has c3; pConv stack has c4 (conversationId wins over agentId).
        val byKey = result.stacks.associateBy { it.stackKey }
        assertEquals(setOf("known:pA", "known:pRun", "known:pConv"), byKey.keys)
        assertEquals(listOf("c1", "c2"), byKey.getValue("known:pA").memberConversationIds)
        assertEquals(listOf("c3"), byKey.getValue("known:pRun").memberConversationIds)
        assertEquals(listOf("c4"), byKey.getValue("known:pConv").memberConversationIds)
    }

    @Test
    fun `unknown-provenance unrelated entries stay separate`() {
        val conversations = listOf(
            convo(id = "c1", agentId = "sa1"),
            convo(id = "c2", agentId = "sa2"),
        )
        // No parent identity at all on either entry -> Unknown, but unrelated.
        val entries = listOf(
            entry(toolCallId = "t1", subagentConversationId = "c1"),
            entry(toolCallId = "t2", subagentConversationId = "c2"),
        )

        val result = groupSubagentConversations(conversations, entries)

        assertEquals(2, result.stacks.size)
        assertTrue(result.stacks.all { it.provenance is SubagentProvenance.Unknown })
        assertEquals(listOf("unknown:c1", "unknown:c2"), result.stacks.map { it.stackKey })
        result.stacks.forEach { assertEquals(1, it.count) }
    }

    @Test
    fun `non-subagent conversations pass through ungrouped`() {
        val conversations = listOf(
            convo(id = "c1", agentId = "sa1"),
            convo(id = "plain", agentId = "regular"),
        )
        val entries = listOf(
            entry(toolCallId = "t1", subagentConversationId = "c1", parentAgentId = "parentA"),
        )

        val result = groupSubagentConversations(conversations, entries)

        assertEquals(1, result.stacks.size)
        assertEquals(listOf("c1"), result.stacks.single().memberConversationIds)
        assertEquals(listOf("plain"), result.ungrouped.map { it.id })
    }

    @Test
    fun `agentId fallback join matches when conversationId absent`() {
        val conversations = listOf(convo(id = "c1", agentId = "sa-agent"))
        // subagentConversationId is null (not yet filled) -> fall back to agentId.
        val entries = listOf(
            entry(toolCallId = "t1", subagentAgentId = "sa-agent", parentConversationId = "parentConv"),
        )

        val result = groupSubagentConversations(conversations, entries)

        assertEquals(1, result.stacks.size)
        val stack = result.stacks.single()
        assertEquals("known:parentConv", stack.stackKey)
        assertEquals(listOf("c1"), stack.memberConversationIds)
    }

    @Test
    fun `empty and no-match input degrades to all-ungrouped`() {
        val conversations = listOf(
            convo(id = "c1", agentId = "sa1"),
            convo(id = "c2", agentId = "sa2"),
        )

        // Empty entries.
        val emptyResult = groupSubagentConversations(conversations, emptyList())
        assertTrue(emptyResult.stacks.isEmpty())
        assertEquals(listOf("c1", "c2"), emptyResult.ungrouped.map { it.id })

        // Entries present but identity fields null -> nothing matches.
        val noMatch = groupSubagentConversations(
            conversations,
            listOf(entry(toolCallId = "t1", parentAgentId = "parentA")),
        )
        assertTrue(noMatch.stacks.isEmpty())
        assertEquals(listOf("c1", "c2"), noMatch.ungrouped.map { it.id })

        // Fully empty inputs.
        val allEmpty = groupSubagentConversations(emptyList(), emptyList())
        assertTrue(allEmpty.stacks.isEmpty())
        assertTrue(allEmpty.ungrouped.isEmpty())
    }

    @Test
    fun `running flag bubbles when any member entry is running`() {
        val conversations = listOf(
            convo(id = "c1", agentId = "sa1"),
            convo(id = "c2", agentId = "sa2"),
        )
        val entries = listOf(
            entry(
                toolCallId = "t1",
                status = SubagentStatus.COMPLETED,
                subagentConversationId = "c1",
                parentAgentId = "parentA",
            ),
            entry(
                toolCallId = "t2",
                status = SubagentStatus.RUNNING,
                subagentConversationId = "c2",
                parentAgentId = "parentA",
            ),
        )

        val result = groupSubagentConversations(conversations, entries)

        val stack = result.stacks.single()
        assertTrue(stack.running)
    }

    @Test
    fun `running flag stays false when no member entry is running`() {
        val conversations = listOf(convo(id = "c1", agentId = "sa1"))
        val entries = listOf(
            entry(
                toolCallId = "t1",
                status = SubagentStatus.COMPLETED,
                subagentConversationId = "c1",
                parentAgentId = "parentA",
            ),
        )

        val result = groupSubagentConversations(conversations, entries)

        assertFalse(result.stacks.single().running)
    }

    @Test
    fun `stable ordering and stable keys across runs`() {
        val conversations = listOf(
            convo(id = "c1", agentId = "sa1"),
            convo(id = "c2", agentId = "sa2"),
            convo(id = "c3", agentId = "sa3"),
        )
        val entries = listOf(
            entry(toolCallId = "t3", subagentConversationId = "c3", parentAgentId = "parentB"),
            entry(toolCallId = "t1", subagentConversationId = "c1", parentAgentId = "parentA"),
            entry(toolCallId = "t2", subagentConversationId = "c2", parentAgentId = "parentA"),
        )

        val first = groupSubagentConversations(conversations, entries)
        val second = groupSubagentConversations(conversations, entries)

        // Stacks ordered by first appearance of their provenance key in the
        // conversation walk: c1 (parentA) then c3 (parentB).
        assertEquals(listOf("known:parentA", "known:parentB"), first.stacks.map { it.stackKey })
        assertEquals(first.stacks.map { it.stackKey }, second.stacks.map { it.stackKey })
        assertEquals(
            first.stacks.map { it.memberConversationIds },
            second.stacks.map { it.memberConversationIds },
        )
        // Representative is first member in input order.
        assertEquals("c1", first.stacks.first().representativeConversationId)
    }

    @Test
    fun `conversationId join wins over agentId fallback`() {
        // Two entries could match c1: one by conversationId, one by agentId.
        // conversationId is the stronger key and must win.
        val conversations = listOf(convo(id = "c1", agentId = "sa1"))
        val entries = listOf(
            entry(toolCallId = "byAgent", subagentAgentId = "sa1", parentAgentId = "wrong"),
            entry(toolCallId = "byConvo", subagentConversationId = "c1", parentAgentId = "right"),
        )

        val result = groupSubagentConversations(conversations, entries)

        assertEquals("known:right", result.stacks.single().stackKey)
    }
}
