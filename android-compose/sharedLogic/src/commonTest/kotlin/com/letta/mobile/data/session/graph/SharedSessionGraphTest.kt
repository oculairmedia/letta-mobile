package com.letta.mobile.data.session.graph

import com.letta.mobile.data.context.ContextReadingSnapshots
import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/** letta-mobile-by4vm.24: the shared builder every host's session graph takes these repositories from. */
class SharedSessionGraphTest {
    private class MemoryStore : SecureSettingsStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue
        override fun putString(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun clear() = values.clear()
    }

    @Test
    fun contextReadingsStartFromTheSavedSnapshot() = runTest {
        val store = MemoryStore()
        ContextTokenReadings(onChange = ContextReadingSnapshots(store)::save).record(
            ServerFrame.UsageStatistics(agentId = AGENT, conversationId = CONV, contextTokens = 29_193),
        )
        val scope = backgroundScope

        val repositories = SharedSessionGraph.transportBound(
            transport = NoOpChannelTransport(),
            scope = scope,
            snapshots = ContextReadingSnapshots(store),
        )

        assertEquals(29_193, repositories.contextTokenReadings.latest(AGENT, CONV))
    }

    @Test
    fun eachGraphGenerationGetsItsOwnRepositories() = runTest {
        val scope = backgroundScope
        val transport = NoOpChannelTransport()

        val first = SharedSessionGraph.transportBound(transport, scope, subagents = SubagentListing.ALL)
        val second = SharedSessionGraph.transportBound(transport, scope, subagents = SubagentListing.ALL)

        assertNotSame(first.cronRepository, second.cronRepository)
        assertNotSame(first.selfTodoRepository, second.selfTodoRepository)
        assertNotSame(first.subagentRepository, second.subagentRepository)
        assertNotSame(first.contextTokenReadings, second.contextTokenReadings)
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONV = "conv-1"
    }
}
