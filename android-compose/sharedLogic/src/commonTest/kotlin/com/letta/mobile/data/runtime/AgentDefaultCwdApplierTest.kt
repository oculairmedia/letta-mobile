package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** letta-mobile-k5nbq.9: a new conversation starts in the agent's saved cwd. */
class AgentDefaultCwdApplierTest {
    private val agent = AgentId("agent-1")
    private val conversation = ConversationId("conv-new")

    @Test
    fun applyOnCreateWritesTheAgentsDefaultCwd() = runTest {
        val defaults = StoredAgentDefaults(InMemorySecureSettingsStore())
        defaults.setCwd(agent, "/work/repo/")
        val written = mutableListOf<Triple<String, String, String>>()
        val applied = AgentDefaultCwdApplier(defaults).applyOnCreate(agent, conversation) { id, conv, cwd ->
            written += Triple(id.value, conv.value, cwd)
            true
        }
        assertTrue(applied)
        assertEquals(listOf(Triple("agent-1", "conv-new", "/work/repo")), written)
    }

    @Test
    fun applyOnCreateDoesNothingWhenTheAgentHasNoDefault() = runTest {
        val defaults = StoredAgentDefaults(InMemorySecureSettingsStore())
        val written = mutableListOf<String>()
        val applied = AgentDefaultCwdApplier(defaults).applyOnCreate(agent, conversation) { _, _, cwd ->
            written += cwd
            true
        }
        assertTrue(applied)
        assertEquals(emptyList(), written)
    }

    @Test
    fun applyOnCreateReportsWhenTheWriteIsRefused() = runTest {
        val defaults = StoredAgentDefaults(InMemorySecureSettingsStore())
        defaults.setCwd(agent, "/gone")
        val applied = AgentDefaultCwdApplier(defaults).applyOnCreate(agent, conversation) { _, _, _ -> false }
        assertEquals(false, applied)
    }
}
