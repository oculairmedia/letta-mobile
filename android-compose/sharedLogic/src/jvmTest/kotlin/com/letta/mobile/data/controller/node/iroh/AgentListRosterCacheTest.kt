package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** agent.list pages come from one held roster until a host write or an agent file change. */
class AgentListRosterCacheTest {
    private suspend fun dispatch(r: AdminRpcRouter, method: String, params: Map<String, String>): String =
        r.dispatch(
            AdminRpcInvocation(
                requestId = "t-1",
                method = method,
                params = buildJsonObject { params.forEach { (k, v) -> put(k, v) } },
                context = AdminRpcRequestContext.Authenticated,
            ),
        )

    @Test
    fun aRosterSweepFetchesOnceUntilAWriteOrTheStoreChanges() = runTest {
        val root = kotlin.io.path.createTempDirectory("roster-cache-store").toFile()
        LocalBackendFixtureStore.create(root)
        val native = FakeNativeClient(agentRosterSize = 5)
        val r = AdminRpcRouter()
        AgentAdminHandlers.register(
            r,
            controller = null,
            tiers = NativeReadTiers(
                nativeClient = native,
                localBackendStore = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1"),
            ),
        )
        fun listCalls() = native.calls.count { it == "agent_list" }

        val pages = listOf(0, 2, 4).map { offset -> dispatch(r, "agent.list", mapOf("limit" to "2", "offset" to "$offset")) }
        assertTrue("agent-3" in pages[1] && "agent-5" in pages[2], "pages: $pages")
        assertEquals(1, listCalls())

        dispatch(r, "agent.update", mapOf("agent_id" to "agent-1", "name" to "Renamed"))
        dispatch(r, "agent.list", mapOf("limit" to "2"))
        assertEquals(2, listCalls(), "a write through the host refetches")

        LocalBackendFixtureStore.writeAgent(root, "agent-new", name = "New")
        dispatch(r, "agent.list", mapOf("limit" to "2"))
        assertEquals(3, listCalls(), "an agent file changed on disk refetches")
    }
}
