package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-k5nbq.9: per-agent defaults survive a restart and refuse a relative cwd. */
class AgentDefaultsTest {
    private val agent = AgentId("agent-1")

    @Test
    fun anAbsoluteCwdIsNormalizedAndReloaded() {
        val store = InMemorySecureSettingsStore()
        StoredAgentDefaults(store).setCwd(agent, "/work/repo/")
        assertEquals("/work/repo", StoredAgentDefaults(store).cwd(agent))
    }

    @Test
    fun aRelativeCwdIsRefusedAndABlankPathClearsTheDefault() {
        val defaults = StoredAgentDefaults(InMemorySecureSettingsStore())
        defaults.setCwd(agent, "relative/path")
        assertNull(defaults.cwd(agent))

        defaults.setCwd(agent, "/work/repo")
        defaults.setCwd(agent, "  ")
        assertNull(defaults.cwd(agent))
        assertTrue(defaults.entries.value.isEmpty())
    }

    @Test
    fun aClearedDefaultStaysClearedAfterARestart() {
        val store = InMemorySecureSettingsStore()
        val defaults = StoredAgentDefaults(store)
        defaults.setCwd(agent, "/work")
        defaults.setCwd(agent, null)
        assertNull(StoredAgentDefaults(store).cwd(agent))
    }

    @Test
    fun aBlankAgentIdIsIgnored() {
        val defaults = StoredAgentDefaults(InMemorySecureSettingsStore())
        defaults.setCwd(AgentId("  "), "/work")
        assertTrue(defaults.entries.value.isEmpty())
    }
}
