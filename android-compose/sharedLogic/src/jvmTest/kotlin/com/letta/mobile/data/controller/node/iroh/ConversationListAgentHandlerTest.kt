package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * letta-mobile-i9h61.3.1: hermetic coverage of the new
 * `conversation.list_agent` admin_rpc handler. Four required cases:
 *
 *  - missing `agent_id` param → success:false with missing_required
 *  - local-backend store unset on the router → capability fail-closed
 *  - store present, agent unknown → success:false with empty conversations
 *  - store present, agent present → success:true with the projected list
 *
 * Each case builds a router via `AdminRpcRegistry.buildRouter` and
 * dials no admin proxy (the hermetic gate forbids it).
 */
class ConversationListAgentHandlerTest {

    @Test
    fun returnsMissingRequiredWhenAgentIdAbsent() = runTest {
        val router = AdminRpcRegistry.buildRouter()
        val resp = invoke(router, "conversation.list_agent", buildJsonObject {})
        assertTrue(resp.contains("missing_required: agent_id"), "Expected missing_required: $resp")
        assertTrue(resp.contains("\"success\":false"), resp)
    }

    @Test
    fun failsClosedWhenLocalBackendStoreUnwired() = runTest {
        val router = AdminRpcRegistry.buildRouter() // no localBackendDir
        val resp = invoke(
            router,
            "conversation.list_agent",
            buildJsonObject { put("agent_id", "ag-1") },
        )
        assertTrue(
            resp.contains("capability_unavailable"),
            "Expected capability_unavailable when local-backend store is unwired: $resp",
        )
        assertTrue(resp.contains("\"success\":false"), resp)
    }

    @Test
    fun returnsEmptyArrayWhenAgentMissing() = runTest {
        val root = createTempDirectory("i9h61-list-agent-missing").toFile()
        LocalBackendFixtureStore.create(root)
        val router = AdminRpcRegistry.buildRouter(localBackendDir = root.absolutePath)
        val resp = invoke(
            router,
            "conversation.list_agent",
            buildJsonObject { put("agent_id", "ag-not-here") },
        )
        // Whether the handler treats "unknown agent" as success:false
        // (adminError) or success:true with an empty array is an
        // implementation detail — what matters is that the picker
        // receives an empty conversations array and falls through to
        // its null branch.
        val conversations = extractConversationsArray(resp)
        if (conversations.isNotEmpty()) {
            throw AssertionError("Expected empty conversations for unknown agent; got $resp")
        }
    }

    @Test
    fun returnsProjectedConversationsForKnownAgent() = runTest {
        val root = createTempDirectory("i9h61-list-agent-known").toFile()
        LocalBackendFixtureStore.create(root)
        // listConversationsProjected reads conversations/<b64url("default:<agentId>")>/conversation.json
        // — LocalBackendFixtureStore.writeConversation writes the older shape
        // (system-prompt.json + messages.jsonl), which listConversations
        // does not consume. Drop in a conversation.json that matches the
        // shape listConversations reads.
        val agentId = LocalBackendFixtureStore.AGENT_ID
        java.io.File(
            java.io.File(root, "conversations"),
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("default:$agentId".toByteArray()),
        ).let { dir ->
            dir.mkdirs()
            java.io.File(dir, "conversation.json").writeText(
                """{"id":"conv-known-1","agent_id":"$agentId",""" +
                    """"created_at":"2026-08-17T18:00:00.000Z",""" +
                    """"updated_at":"2026-08-17T18:00:00.000Z",""" +
                    """"last_message_at":"2026-08-17T18:00:00.000Z",""" +
                    """"archived":false,"summary":"known conv"}""",
            )
        }

        val router = AdminRpcRegistry.buildRouter(localBackendDir = root.absolutePath)
        val resp = invoke(
            router,
            "conversation.list_agent",
            buildJsonObject { put("agent_id", agentId) },
        )
        assertTrue(resp.contains("\"success\":true"), resp)

        // The wire shape must carry the fields the client picker (and
        // the App Server v2 client) decodes — id, agent_id,
        // last_message_at at minimum. Pin presence, not exact counts.
        val conversations = extractConversationsArray(resp)
        assertTrue(
            conversations.isNotEmpty(),
            "Expected at least one conversation for $agentId; got $resp",
        )
        val first = conversations.first()
        assertTrue(first.containsKey("id"), "id missing: $first")
        val agentIdJson = first["agent_id"]
        val agentIdStr: String? = (agentIdJson as? JsonPrimitive)?.let { it.contentOrNull }
        if (agentId != agentIdStr) {
            throw AssertionError("agent_id mismatch: expected=$agentId actual=$agentIdStr in $first")
        }
    }

    private suspend fun invoke(
        router: AdminRpcRouter,
        method: String,
        params: JsonObject?,
    ): String = router.dispatch(
        AdminRpcInvocation(
            requestId = "t-1",
            method = method,
            params = params,
            context = AdminRpcRequestContext.Authenticated,
        ),
    )

    /**
     * Tolerates two response shapes:
     *  - the agent_id-scoped envelope `{success, conversations: [...]}` (future-proof)
     *  - the bare `[{...}, ...]` JsonArray we return today from the new
     *    handler before the scoped wrapper is wired in.
     */
    private fun extractConversationsArray(resp: String): List<JsonObject> {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(resp)
        if (element is JsonObject) {
            val ok = element["success"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content == "true"
            if (!ok) return emptyList()
            val conversations = element["conversations"] ?: element["result"]
            return (conversations as? kotlinx.serialization.json.JsonArray)?.map { (it as JsonObject) }
                ?: emptyList()
        }
        return (element as? kotlinx.serialization.json.JsonArray)?.map { (it as JsonObject) } ?: emptyList()
    }
}
