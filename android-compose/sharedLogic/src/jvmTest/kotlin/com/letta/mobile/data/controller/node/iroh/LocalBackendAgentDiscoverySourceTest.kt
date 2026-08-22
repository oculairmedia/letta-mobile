package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalBackendAgentDiscoverySourceTest {
    private fun tempStore(): File = Files.createTempDirectory("lc-local-backend-discovery-test").toFile()

    private fun writeAgent(base: File, fileName: String, json: String) {
        val agents = File(base, "agents").apply { mkdirs() }
        File(agents, fileName).writeText(json)
    }

    @Test
    fun `repeat lookups within the TTL reuse the cached agent list`() = runBlocking {
        val base = tempStore()
        writeAgent(base, "a1.json", """{"id":"agent-1","name":"Meridian"}""")
        val store = LocalBackendAdminStore(base, lmstudioBaseUrl = "http://127.0.0.1:4000/v1")
        var now = 0L
        val source = LocalBackendAgentDiscoverySource(store, nowMillis = { now }, cacheTtlMs = 5_000L)

        val first = source.listAgents()
        assertEquals(1, first?.size)

        // A second agent is registered, but within the TTL window a repeat
        // agent_discover call (as a chatty tool loop would make) should still
        // see the cached snapshot rather than re-reading the store.
        writeAgent(base, "a2.json", """{"id":"agent-2","name":"Sidekick"}""")
        now += 2_000L
        val second = source.listAgents()
        assertEquals(1, second?.size, "cached snapshot should be reused inside the TTL window")

        now += 4_000L
        val third = source.listAgents()
        assertEquals(2, third?.size, "cache should refresh once the TTL has elapsed")
    }
}
