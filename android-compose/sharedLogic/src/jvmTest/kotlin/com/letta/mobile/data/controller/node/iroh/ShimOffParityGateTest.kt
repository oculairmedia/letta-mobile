package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * lgns8.10 — shim-off parity gate.
 *
 * Proves that with lettashim UNAVAILABLE (the proxy transport fails), the
 * runtime-owned path serves NATIVELY, capability-gated ops deny cleanly, and
 * every remaining bounded-admin method degrades to a well-formed
 * `success:false` envelope — the router NEVER throws or hangs, so a shim
 * outage can never crash chat. This is the achievable core of the acceptance
 * gate: the runtime path is off-shim; the admin surface degrades gracefully
 * (the admin surface itself cannot be fully off-shim — see
 * docs/architecture/lgns8-epic-status-and-shim-retirement-ceiling.md).
 */
class ShimOffParityGateTest {
    private var savedFactory: (() -> AdminProxyTransport)? = null

    @BeforeTest
    fun shimOff() {
        // Clear NativeAdmin's process-wide circuit breaker so a prior class's
        // native-timeout test can't leave native short-circuited for the
        // runtime-owned ops this gate asserts serve natively.
        NativeAdmin.resetCircuitForTest()
        // lettashim is unreachable: every proxy dial fails fast (not a hang).
        savedFactory = AdminProxyClient.defaultTransportFactory
        AdminProxyClient.defaultTransportFactory = {
            AdminProxyTransport { _, _, _ -> error("shim unavailable (parity gate: shim off)") }
        }
    }

    @AfterTest
    fun restore() {
        savedFactory?.let { AdminProxyClient.defaultTransportFactory = it }
    }

    private object EmptySubagentSource : SubagentRegistrySource {
        override suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry> = emptyList()

        override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? = null
    }

    /** Serves every runtime-native op successfully; abstract turn ops are unused by admin_rpc. */
    private class NativeRuntime : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")

        override suspend fun input(command: AppServerCommand.Input) = error("unused")

        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")

        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")

        private fun agentObj() = buildJsonObject { put("id", "agent-1") }
        private fun convObj() = buildJsonObject { put("id", "conv-1") }

        override suspend fun agentList(command: AppServerCommand.AgentList) =
            AppServerInboundFrame.AgentListResponse(command.requestId, true, buildJsonArray { add(agentObj()) })

        override suspend fun agentRetrieve(command: AppServerCommand.AgentRetrieve) =
            AppServerInboundFrame.AgentRetrieveResponse(command.requestId, true, agentObj())

        override suspend fun agentCreate(command: AppServerCommand.AgentCreate) =
            AppServerInboundFrame.AgentCreateResponse(command.requestId, true, agentObj())

        override suspend fun agentUpdate(command: AppServerCommand.AgentUpdate) =
            AppServerInboundFrame.AgentUpdateResponse(command.requestId, true, agentObj())

        override suspend fun agentDelete(command: AppServerCommand.AgentDelete) =
            AppServerInboundFrame.AgentDeleteResponse(command.requestId, true)

        override suspend fun conversationList(command: AppServerCommand.ConversationList) =
            AppServerInboundFrame.ConversationListResponse(command.requestId, true, buildJsonArray { add(convObj()) })

        override suspend fun conversationRetrieve(command: AppServerCommand.ConversationRetrieve) =
            AppServerInboundFrame.ConversationRetrieveResponse(command.requestId, true, convObj())

        override suspend fun conversationCreate(command: AppServerCommand.ConversationCreate) =
            AppServerInboundFrame.ConversationCreateResponse(command.requestId, true, convObj())

        override suspend fun conversationUpdate(command: AppServerCommand.ConversationUpdate) =
            AppServerInboundFrame.ConversationUpdateResponse(command.requestId, true, convObj())

        override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList) =
            AppServerInboundFrame.ConversationMessagesListResponse(command.requestId, true, JsonArray(emptyList()))

        override suspend fun listModels(command: AppServerCommand.ListModels) =
            AppServerInboundFrame.ListModelsResponse(command.requestId, true, JsonArray(emptyList()))

        override suspend fun skillEnable(command: AppServerCommand.SkillEnable) =
            AppServerInboundFrame.SkillEnableResponse(command.requestId, true, "demo")

        override suspend fun skillDisable(command: AppServerCommand.SkillDisable) =
            AppServerInboundFrame.SkillDisableResponse(command.requestId, true)

        override suspend fun cronList(command: AppServerCommand.CronList) =
            AppServerInboundFrame.CronListResponse(command.requestId, true, JsonArray(emptyList()))

        override suspend fun cronAdd(command: AppServerCommand.CronAdd) =
            AppServerInboundFrame.CronAddResponse(command.requestId, true)

        override suspend fun cronGet(command: AppServerCommand.CronGet) =
            AppServerInboundFrame.CronGetResponse(command.requestId, true)

        override suspend fun cronRuns(command: AppServerCommand.CronRuns) =
            AppServerInboundFrame.CronRunsResponse(command.requestId, true)

        override suspend fun cronTrigger(command: AppServerCommand.CronTrigger) =
            AppServerInboundFrame.CronTriggerResponse(command.requestId, true)

        override suspend fun cronUpdate(command: AppServerCommand.CronUpdate) =
            AppServerInboundFrame.CronUpdateResponse(command.requestId, true)

        override suspend fun cronDelete(command: AppServerCommand.CronDelete) =
            AppServerInboundFrame.CronDeleteResponse(command.requestId, true)

        override suspend fun cronDeleteAll(command: AppServerCommand.CronDeleteAll) =
            AppServerInboundFrame.CronDeleteAllResponse(command.requestId, true)
    }

    private fun productionRouter(): AdminRpcRouter =
        AdminRpcRegistry.buildRouter(
            adminBaseUrl = "http://127.0.0.1:9", // shim host — but transport is forced to fail
            controller = null,
            subagentRegistrySource = EmptySubagentSource,
            pairingService = IrohPairingService(InMemoryPairedPeerStore()),
            nativeClient = NativeRuntime(),
            shimRetired = true,
            vibesyncBaseUrl = null, // VibeSync not injected
        )

    private fun params(method: String) = buildJsonObject {
        put("agent_id", "agent-1")
        put("conversation_id", "conv-1")
        put("message_id", "m-1")
        put("project_id", "p-1")
        put("task_id", "t-1")
        put("tool_call_id", "tc-1")
        put("name", "demo")
        put("cron", "0 0 * * *")
        put("prompt", "hi")
        // native opt-ins
        if (method == "model.list") put("native", "true")
        if (method == "skill.install") put("skill_path", "/skills/demo")
    }

    @Test
    fun noAdminMethodThrowsOrHangsWithShimOff() = runTest {
        val router = productionRouter()
        router.registeredMethods.forEach { method ->
            val raw = router.dispatch(
                AdminRpcInvocation(
                    requestId = "gate",
                    method = method,
                    params = params(method),
                    context = AdminRpcRequestContext.Authenticated,
                ),
            )
            // Every response is a well-formed admin_rpc_response envelope — never
            // an unhandled throw / hang that would crash the turn.
            val obj = Json.parseToJsonElement(raw).jsonObject
            assertEquals("admin_rpc_response", obj["type"]?.jsonPrimitive?.content, "$method: malformed envelope")
            assertEquals("gate", obj["request_id"]?.jsonPrimitive?.content, "$method")
            assertTrue(obj["success"] != null, "$method: missing success")
        }
    }

    @Test
    fun runtimeOwnedOpsSucceedNativelyWithShimOff() = runTest {
        val router = productionRouter()
        // Runtime-owned ops that ARE wired native-first today. Note the honest
        // gaps: message.get/tool_return.get are still shim-backed single-message
        // fetches (lgns8.7 made only message.list native — the single-message
        // projection over conversation_messages_list is a follow-up), and
        // skill.uninstall is native only when NOT agent-scoped. Those degrade
        // gracefully shim-off (asserted elsewhere), they do not serve natively.
        val nativeOk = listOf(
            "agent.list", "agent.get", "agent.create", "agent.update", "agent.delete",
            "conversation.list", "conversation.get", "conversation.create",
            "conversation.update", "conversation.archive", "conversation.restore",
            "message.list",
            "model.list", "skill.install",
            "cron.list", "cron.add", "cron.get", "cron.trigger",
        )
        nativeOk.forEach { method ->
            val obj = Json.parseToJsonElement(
                router.dispatch(
                    AdminRpcInvocation("g", method, params(method), AdminRpcRequestContext.Authenticated),
                ),
            ).jsonObject
            assertEquals("true", obj["success"]?.jsonPrimitive?.content, "$method must serve natively shim-off: $obj")
        }
    }

    @Test
    fun capabilityGatedAndUnroutedOpsDenyCleanlyWithoutCrashing() = runTest {
        val router = productionRouter()
        // conversation.delete: capability_unavailable (shimRetired, absent upstream)
        val del = Json.parseToJsonElement(
            router.dispatch(AdminRpcInvocation("g", "conversation.delete", params("conversation.delete"), AdminRpcRequestContext.Authenticated)),
        ).jsonObject
        assertEquals("false", del["success"]?.jsonPrimitive?.content)
        assertTrue(del["error"]?.jsonPrimitive?.content?.contains("capability_unavailable") == true)

        // project.*: capability_unavailable (no VibeSync service injected)
        ProjectAdminHandlers.PROJECT_METHODS.forEach { method ->
            val obj = Json.parseToJsonElement(
                router.dispatch(AdminRpcInvocation("g", method, params(method), AdminRpcRequestContext.Authenticated)),
            ).jsonObject
            assertEquals("false", obj["success"]?.jsonPrimitive?.content, "$method")
            assertTrue(obj["error"]?.jsonPrimitive?.content?.contains("capability_unavailable") == true, "$method")
        }
    }

    @Test
    fun boundedAdminMethodsDegradeToCleanFailureNotCrash() = runTest {
        val router = productionRouter()
        // These have no native backend; with the shim off they must fail
        // gracefully (success:false) — never throw/hang.
        listOf("run.list", "tool.list", "block.list", "archive.list", "identity.list", "schedule.list", "job.list", "mcp.list")
            .forEach { method ->
                val obj = Json.parseToJsonElement(
                    router.dispatch(AdminRpcInvocation("g", method, params(method), AdminRpcRequestContext.Authenticated)),
                ).jsonObject
                assertEquals("false", obj["success"]?.jsonPrimitive?.content, "$method should fail gracefully shim-off")
            }
    }
}
