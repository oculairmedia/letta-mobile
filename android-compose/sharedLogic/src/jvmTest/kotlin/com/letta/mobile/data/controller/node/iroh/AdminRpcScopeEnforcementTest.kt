package com.letta.mobile.data.controller.node.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * lgns8.12: cross-conversation content access is rejected at the admin_rpc
 * layer before any proxy call, and the conversation scope follows peer
 * capabilities.
 */
class AdminRpcScopeEnforcementTest {
    private fun router(): AdminRpcRouter {
        val r = AdminRpcRouter()
        ConversationAdminHandlers.register(r, "http://127.0.0.1:9")
        return r
    }

    private fun params(conversationId: String) = buildJsonObject {
        put("conversation_id", conversationId)
        put("limit", "5")
    }

    @Test
    fun crossConversationReadIsForbiddenForScopedPeers() = runTest {
        val restricted = AdminRpcRequestContext(
            authenticated = true,
            authorizedConversationIds = setOf("conv-mine"),
        )
        listOf("message.list", "message.get", "tool_return.get").forEach { method ->
            val response = router().dispatch(
                AdminRpcInvocation(
                    requestId = "r1",
                    method = method,
                    params = buildJsonObject {
                        put("conversation_id", "conv-other")
                        put("message_id", "m1")
                        put("limit", "5")
                    },
                    context = restricted,
                ),
            )
            assertTrue(response.contains("\"success\":false"), "$method must fail")
            assertTrue(response.contains("out of authorized scope"), "$method must deny on scope")
        }
    }

    @Test
    fun inScopeReadPassesTheScopeGate() = runTest {
        val restricted = AdminRpcRequestContext(
            authenticated = true,
            authorizedConversationIds = setOf("conv-mine"),
        )
        // The proxy target is unreachable in tests, so a passing scope gate is
        // observed as a non-scope error (connection failure), never the denial.
        val response = router().dispatch(
            AdminRpcInvocation(requestId = "r2", method = "message.list", params = params("conv-mine"), context = restricted),
        )
        assertFalse(response.contains("out of authorized scope"))
    }

    @Test
    fun unrestrictedContextReadsAnyConversation() = runTest {
        val unrestricted = AdminRpcRequestContext(authenticated = true, authorizedConversationIds = null)
        val response = router().dispatch(
            AdminRpcInvocation(requestId = "r3", method = "message.list", params = params("conv-any"), context = unrestricted),
        )
        assertFalse(response.contains("out of authorized scope"))
    }

    @Test
    fun conversationScopeFollowsPeerCapabilities() {
        assertNull(
            IrohPeerCapabilities.conversationScope(setOf(IrohPeerCapabilities.ADMIN_FULL), null),
            "admin.full is unrestricted",
        )
        assertNull(
            IrohPeerCapabilities.conversationScope(IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE, null),
            "the desktop role holds conversation.manage and is unrestricted",
        )
        assertEquals(
            setOf("conv-1"),
            IrohPeerCapabilities.conversationScope(setOf(IrohPeerCapabilities.CHAT_READ), "conv-1"),
            "a chat.read-only peer is bounded to its viewed conversation",
        )
        assertEquals(
            emptySet(),
            IrohPeerCapabilities.conversationScope(setOf(IrohPeerCapabilities.CHAT_READ), null),
            "a chat.read-only peer with no viewed conversation reads nothing",
        )
    }

    @Test
    fun denialsNeverLeakWhetherTheConversationExists() = runTest {
        val restricted = AdminRpcRequestContext(authenticated = true, authorizedConversationIds = emptySet())
        val response = router().dispatch(
            AdminRpcInvocation(requestId = "r4", method = "message.list", params = params("conv-x"), context = restricted),
        )
        assertTrue(response.contains("out of authorized scope"))
        assertFalse(response.contains("conv-x"), "denial must not echo the target resource")
    }
}
