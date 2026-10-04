package com.letta.mobile.data.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-fxoew.6: hidden subagent agents and conversations stay out of
 * user-facing lists.
 *
 * Kotlin/Native commonTest naming: punctuation-free camelCase only.
 */
class HiddenListFilterTest {
    private fun agent(id: String, hidden: Boolean?, vararg tags: String) =
        Agent(id = AgentId(id), name = id, hidden = hidden, tags = persistentListOf(*tags))

    private fun conversation(id: String, hidden: Boolean?) =
        Conversation(id = ConversationId(id), agentId = AgentId("agent-1"), hidden = hidden)

    @Test
    fun hiddenConversationIsExcludedFromTheList() {
        val list = listOf(conversation("conv-visible", null), conversation("conv-hidden", true), conversation("conv-shown", false))
        assertEquals(listOf("conv-visible", "conv-shown"), list.visibleConversationsInLists().map { it.id.value })
    }

    @Test
    fun untaggedSubagentWithUnsetHiddenIsExcluded() {
        val subagent = agent("sub", null, "type:reflection", "parent:agent-x")
        assertTrue(HiddenListFilter.isHidden(subagent))
        assertEquals(emptyList(), listOf(subagent).visibleInLists())
    }

    @Test
    fun explicitFalseWinsOverSubagentTags() {
        assertFalse(HiddenListFilter.isHidden(agent("shown", false, "type:general-purpose")))
    }

    @Test
    fun explicitTrueHidesAnAgent() {
        assertTrue(HiddenListFilter.isHidden(agent("hidden", true)))
    }

    @Test
    fun appServerSubagentRoleTagHidesAnAgent() {
        assertTrue(HiddenListFilter.isHidden(agent("sub", null, "origin:letta-code", "role:subagent")))
    }

    @Test
    fun otherRoleTagsStayVisible() {
        assertFalse(HiddenListFilter.isHidden(agent("tester", null, "role:tester", "project:x")))
    }

    @Test
    fun decodingHiddenTrueSetsTheConversationFlag() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(
            Conversation.serializer(),
            """{"id":"conv-1","agent_id":"agent-1","hidden":true}""",
        )
        assertEquals(true, decoded.hidden)
        assertTrue(HiddenListFilter.isHidden(decoded))
    }
}
