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
        NativeAdmin.resetCircuitForTest()
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
        NativeAdmin.resetCircuitForTest()
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
    ): AdminRpcRouter {
        val r = AdminRpcRouter()
        HealthAdminHandlers.register(r, controller)
        ModelAdminHandlers.register(r, client)
        SkillAdminHandlers.register(r, nativeClient = client)
        ConversationAdminHandlers.register(r, NativeReadTiers(nativeClient = client))
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
    fun modelListServesNativelyByDefaultWithShimDown() = runTest {
        val client = FakeControlClient()
        val r = router(client = client)

        val native = dispatch(r, "model.list", emptyMap())
        assertTrue(native.contains("\"success\":true") && native.contains("anthropic/claude"))
        assertTrue("list_models" in client.calls)
    }

    @Test
    fun skillInstallAndUninstallAreNativeOnlyIncludingNameFallback() = runTest {
        val client = FakeControlClient()
        val r = router(client = client)

        val enable = dispatch(r, "skill.install", mapOf("skill_path" to "/skills/demo"))
        assertTrue(enable.contains("\"success\":true") && enable.contains("\"enabled\":true"))
        assertTrue("skill_enable:/skills/demo" in client.calls)

        val enableByName = dispatch(r, "skill.install", mapOf("name" to "/skills/by-name", "agent_id" to "agent-1"))
        assertTrue(enableByName.contains("\"enabled\":true"))
        assertTrue("skill_enable:/skills/by-name" in client.calls)

        val disable = dispatch(r, "skill.uninstall", mapOf("name" to "demo", "agent_id" to "agent-1"))
        assertTrue(disable.contains("\"success\":true") && disable.contains("\"disabled\":true"))
        assertTrue("skill_disable:demo" in client.calls)
    }

    @Test
    fun skillListProjectsFromInjectedCatalogWithoutShim() = runTest {
        val catalog = NativeSkillsCatalog()
        catalog.hydrateFromHost(
            buildJsonArray {
                add(buildJsonObject { put("name", "demo") })
            },
        )
        val r = AdminRpcRouter()
        SkillAdminHandlers.register(
            r,
            nativeClient = FakeControlClient(),
            skillsListing = catalog.asListingSource(),
        )
        val listed = dispatch(r, "skill.list", emptyMap())
        assertTrue(listed.contains("\"success\":true") && listed.contains("demo"))
        val agentListed = dispatch(r, "skill.list_agent", mapOf("agent_id" to "agent-1"))
        assertTrue(agentListed.contains("\"success\":false"))
        assertTrue(agentListed.contains("capability_unavailable"))
        assertFalse(agentListed.contains("demo"), "must not present global catalog as agent installs")
    }

    @Test
    fun skillListReturnsEmptyUntilCatalogHydrated() = runTest {
        val catalog = NativeSkillsCatalog()
        val r = AdminRpcRouter()
        SkillAdminHandlers.register(r, skillsListing = catalog.asListingSource())
        val listed = dispatch(r, "skill.list", emptyMap())
        assertTrue(listed.contains("\"success\":true"))
        assertTrue(listed.contains("\"hydrated\":false"))
        assertTrue(listed.contains("\"skills\":[]"))
    }

    /**
     * lgns8.21.2 regression. `hydrated` was emitted as the literal `true` on the
     * non-cold-start branch, so any listing source — including one that had never
     * observed an authoritative enumeration — was reported as an authoritative
     * catalog. Reverting `put("hydrated", hydrated)` to `put("hydrated", true)`
     * fails this test.
     */
    @Test
    fun skillListNeverFabricatesHydratedTrueForUnhydratedSource() = runTest {
        val unhydrated = object : SkillsListingSource {
            // Returns a non-null (but non-authoritative) array, exercising the
            // branch that previously hard-coded hydrated=true.
            override fun currentSkills() = buildJsonArray { }
        }
        val r = AdminRpcRouter()
        SkillAdminHandlers.register(r, skillsListing = unhydrated)
        val listed = dispatch(r, "skill.list", emptyMap())
        assertTrue(listed.contains("\"success\":true"))
        assertFalse(listed.contains("\"hydrated\":true"), "must not claim an authoritative catalog: $listed")
        assertTrue(listed.contains("\"hydrated\":false"))
        assertTrue(listed.contains("\"catalog_source\":\"unavailable\""))
    }

    /**
     * lgns8.21.2 regression. With no listing source at all there is no possible
     * authoritative catalog, so `skill.list` must fail closed rather than return
     * an empty list marked hydrated.
     */
    @Test
    fun skillListWithoutAnySourceReportsCapabilityUnavailable() = runTest {
        val r = AdminRpcRouter()
        SkillAdminHandlers.register(r, nativeClient = FakeControlClient())
        val listed = dispatch(r, "skill.list", emptyMap())
        assertTrue(listed.contains("\"success\":false"))
        assertTrue(listed.contains("capability_unavailable"))
    }

    @Test
    fun conversationDeleteDeniesFailClosedUnconditionally() = runTest {
        val denied = dispatch(router(), "conversation.delete", mapOf("conversation_id" to "conv-1"))
        assertTrue(denied.contains("\"success\":false"))
        assertTrue(denied.contains("capability_unavailable"))
        assertFalse(denied.contains("conv-1"), "denial must not echo the resource")
    }
}
