package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ToolsetHandlersTest {
    @Test
    fun forwardsExactlyOnceAndReturnsTheHostResult() = runTest {
        val commands = mutableListOf<AppServerCommand.UpdateToolset>()
        val answer = router(commands, succeeds = true).dispatch("r", "toolset.update", params())
        assertTrue(answer.contains("\"success\":true"), answer)
        assertTrue(answer.contains("\"current_toolset\":\"default\""), answer)
        val command = commands.single()
        assertEquals("auto", command.toolsetPreference)
        assertEquals("agent-1", command.runtime?.agentId)
        assertEquals("conv-1", command.runtime?.conversationId)
        assertTrue(!command.requestId.isNullOrBlank())
    }

    @Test
    fun rejectsMissingPreferenceAndSurfacesHostFailure() = runTest {
        val commands = mutableListOf<AppServerCommand.UpdateToolset>()
        val router = router(commands, succeeds = false)
        val missing = router.dispatch("r", "toolset.update", params(preference = false))
        assertTrue(missing.contains("toolset_preference"), missing)
        assertTrue(commands.isEmpty())
        val failed = router.dispatch("r", "toolset.update", params())
        assertTrue(failed.contains("unsupported toolset"), failed)
        assertEquals(1, commands.size)
    }

    @Test
    fun scopesAndAuthenticationAreCheckedBeforeSending() = runTest {
        val commands = mutableListOf<AppServerCommand.UpdateToolset>()
        val router = router(commands, succeeds = true)
        val denied = listOf(
            AdminRpcRequestContext.Unauthenticated,
            AdminRpcRequestContext(authenticated = true, authorizedConversationIds = setOf("other")),
            AdminRpcRequestContext(authenticated = true, authorizedAgentIds = setOf("other")),
        )
        for (context in denied) {
            val result = router.dispatch(AdminRpcInvocation("r", "toolset.update", params(), context))
            assertTrue(result.contains("\"success\":false"), result)
        }
        assertTrue(commands.isEmpty())
        assertEquals(IrohPeerCapabilities.CONVERSATION_MANAGE, IrohPeerCapabilities.forAdminMethod("toolset.update"))
    }

    private fun params(preference: Boolean = true) = buildJsonObject {
        put("agent_id", "agent-1")
        put("conversation_id", "conv-1")
        if (preference) put("toolset_preference", "auto")
    }

    private fun router(commands: MutableList<AppServerCommand.UpdateToolset>, succeeds: Boolean): AdminRpcRouter {
        val client = Proxy.newProxyInstance(
            AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java),
        ) { _, method, args ->
            check(method.name == "updateToolset") { "Unexpected call: ${method.name}" }
            val command = args[0] as AppServerCommand.UpdateToolset
            commands += command
            AppServerInboundFrame.UpdateToolsetResponse(
                requestId = command.requestId.orEmpty(), success = succeeds,
                error = if (succeeds) null else "unsupported toolset", currentToolset = "default",
            )
        } as AppServerClient
        return AdminRpcRegistry.buildRouter(native = AdminRpcNativeSources(nativeClient = client))
    }
}
