package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** lgns8.8: policy-gated control capabilities. Shim base is unreachable. */
class ControlCapabilityHandlersTest {
    private var savedFactory: (() -> AdminProxyTransport)? = null

    @BeforeTest
    fun pinUnreachableShim() {
        // The shim base points at a discard port, but AdminProxyClient's shared
        // defaultTransportFactory is mutable process-wide and other tests in the
        // suite leave a fake installed. Pin a deterministic always-failing
        // transport so "shim unavailable" holds regardless of test order.
        savedFactory = AdminProxyClient.defaultTransportFactory
        AdminProxyClient.defaultTransportFactory = {
            AdminProxyTransport { _, _, _ -> error("shim unavailable (d6e8g test harness)") }
        }
    }

    @AfterTest
    fun restoreShimFactory() {
        savedFactory?.let { AdminProxyClient.defaultTransportFactory = it }
    }

    private class FakeControlClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()
        val calls = mutableListOf<String>()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")

        override suspend fun input(command: AppServerCommand.Input) = error("unused")

        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")

        override suspend fun listModels(command: AppServerCommand.ListModels): AppServerInboundFrame.ListModelsResponse {
            calls += "list_models"
            return AppServerInboundFrame.ListModelsResponse(
                requestId = command.requestId,
                success = true,
                entries = buildJsonArray { add(buildJsonObject { put("handle", "anthropic/claude") }) },
            )
        }

        override suspend fun skillEnable(command: AppServerCommand.SkillEnable): AppServerInboundFrame.SkillEnableResponse {
            calls += "skill_enable:${command.skillPath}"
            return AppServerInboundFrame.SkillEnableResponse(requestId = command.requestId, success = true, skillName = "demo")
        }

        override suspend fun skillDisable(command: AppServerCommand.SkillDisable): AppServerInboundFrame.SkillDisableResponse {
            calls += "skill_disable:${command.name}"
            return AppServerInboundFrame.SkillDisableResponse(requestId = command.requestId, success = true)
        }
    }

    private class StaticController(state: AppServerControllerState) : AppServerController {
        override val state = MutableStateFlow(state)

        override suspend fun startRuntime(
            agentId: AgentId,
            conversationId: ConversationId,
            cwd: String?,
            mode: AppServerPermissionMode?,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ) = error("unused")

        override fun runTurn(command: com.letta.mobile.runtime.TurnCommand) = error("unused")

        override suspend fun sync(
            runtime: AppServerRuntimeScope,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ) = error("unused")

        override suspend fun abort(runtime: AppServerRuntimeScope, runId: String?) = error("unused")
    }

    private fun router(
        client: AppServerClient? = null,
        controller: AppServerController? = null,
        shimRetired: Boolean = false,
    ): AdminRpcRouter {
        val r = AdminRpcRouter()
        HealthAdminHandlers.register(r, "http://127.0.0.1:9", controller)
        ModelAdminHandlers.register(r, "http://127.0.0.1:9", client)
        SkillAdminHandlers.register(r, "http://127.0.0.1:9", client)
        ConversationAdminHandlers.register(r, "http://127.0.0.1:9", client, shimRetired)
        return r
    }

    private suspend fun dispatch(r: AdminRpcRouter, method: String, params: Map<String, String>): String =
        r.dispatch(
            AdminRpcInvocation(
                requestId = "t",
                method = method,
                params = buildJsonObject { params.forEach { (k, v) -> put(k, v) } },
                context = AdminRpcRequestContext.Authenticated,
            ),
        )

    @Test
    fun healthReportsControllerReadinessNatively() = runTest {
        val connected = dispatch(router(controller = StaticController(AppServerControllerState.Connected)), "health.check", emptyMap())
        assertTrue(connected.contains("\"status\":\"ok\"") && connected.contains("\"native\":true"))

        val down = dispatch(
            router(controller = StaticController(AppServerControllerState.Disconnected("socket lost"))),
            "health.check",
            emptyMap(),
        )
        assertTrue(down.contains("\"status\":\"degraded\"") && down.contains("\"controller_state\":\"disconnected\""))
    }

    @Test
    fun modelListNativePathIsOptInAndServesWithShimDown() = runTest {
        val client = FakeControlClient()
        val r = router(client = client)

        val default = dispatch(r, "model.list", emptyMap())
        assertTrue(default.contains("\"success\":false"), "default path uses the (unreachable) shim catalog")
        assertTrue(client.calls.isEmpty())

        val native = dispatch(r, "model.list", mapOf("native" to "true"))
        assertTrue(native.contains("\"success\":true") && native.contains("anthropic/claude"))
        assertTrue("list_models" in client.calls)
    }

    @Test
    fun skillInstallAndUninstallRouteNativelyOnNativeParams() = runTest {
        val client = FakeControlClient()
        val r = router(client = client)

        val enable = dispatch(r, "skill.install", mapOf("skill_path" to "/skills/demo"))
        assertTrue(enable.contains("\"success\":true") && enable.contains("\"enabled\":true"))
        assertTrue("skill_enable:/skills/demo" in client.calls)

        val disable = dispatch(r, "skill.uninstall", mapOf("name" to "demo"))
        assertTrue(disable.contains("\"success\":true") && disable.contains("\"disabled\":true"))
        assertTrue("skill_disable:demo" in client.calls)
    }

    @Test
    fun conversationDeleteDeniesFailClosedAfterCutover() = runTest {
        val retired = dispatch(router(shimRetired = true), "conversation.delete", mapOf("conversation_id" to "conv-1"))
        assertTrue(retired.contains("\"success\":false"))
        assertTrue(retired.contains("capability_unavailable"))
        assertFalse(retired.contains("conv-1"), "denial must not echo the resource")

        // Before cutover the shim path is still attempted (unreachable here).
        val active = dispatch(router(shimRetired = false), "conversation.delete", mapOf("conversation_id" to "conv-1"))
        assertFalse(active.contains("capability_unavailable"))
    }
}
