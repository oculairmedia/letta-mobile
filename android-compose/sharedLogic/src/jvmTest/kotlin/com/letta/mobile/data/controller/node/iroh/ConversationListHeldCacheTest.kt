package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** Per-agent conversation.list pages come from one held full list until a host write or a record change. */
class ConversationListHeldCacheTest {
    private suspend fun dispatch(r: AdminRpcRouter, method: String, params: Map<String, String>): String =
        r.dispatch(
            AdminRpcInvocation(
                requestId = "t-1",
                method = method,
                params = buildJsonObject { params.forEach { (k, v) -> put(k, v) } },
                context = AdminRpcRequestContext.Authenticated,
            ),
        )

    private fun ids(response: String): List<String> =
        Json.parseToJsonElement(response).jsonObject.getValue("result").jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test
    fun agentPagesShareOneFetchAndSearchesAndCrossAgentListsPassThrough() = runTest {
        val root = kotlin.io.path.createTempDirectory("conversation-list-cache").toFile()
        LocalBackendFixtureStore.create(root)
        java.io.File(root, "conversations").mkdirs() // the signature lists it; absent means no cache
        val native = FakeNativeClient(
            extraConversationIds = listOf("conv-a2", "conv-b1"),
            conversationAgentIds = mapOf("conv-1" to "agent-a", "conv-a2" to "agent-a", "conv-b1" to "agent-b"),
        )
        val r = AdminRpcRouter()
        ConversationAdminHandlers.register(
            r,
            tiers = NativeReadTiers(
                nativeClient = native,
                localBackendStore = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1"),
            ),
        )
        fun listCalls() = native.calls.count { it == "conversation_list" }

        assertEquals(listOf("conv-1", "conv-a2"), ids(dispatch(r, "conversation.list", mapOf("agent_id" to "agent-a"))))
        assertEquals(listOf("conv-b1"), ids(dispatch(r, "conversation.list", mapOf("agent_id" to "agent-b"))))
        assertEquals(listOf("conv-a2"), ids(dispatch(r, "conversation.list", mapOf("agent_id" to "agent-a", "after" to "conv-1"))))
        assertEquals(1, listCalls(), "every agent's page is cut from one full fetch")

        dispatch(r, "conversation.list", mapOf("agent_id" to "agent-a", "summary_search" to "x"))
        dispatch(r, "conversation.list", emptyMap())
        assertEquals(3, listCalls(), "a search and a cross-agent list still go to the App Server")

        dispatch(r, "conversation.list", mapOf("agent_id" to ""))
        dispatch(r, "conversation.list", mapOf("agent_id" to "12345"))
        assertEquals(5, listCalls(), "an agent_id the App Server ignores (empty, all digits) passes through")

        dispatch(r, "conversation.update", mapOf("conversation_id" to "conv-1", "summary" to "renamed"))
        dispatch(r, "conversation.list", mapOf("agent_id" to "agent-a"))
        assertEquals(6, listCalls(), "a write through the host drops the held list")
    }
}
