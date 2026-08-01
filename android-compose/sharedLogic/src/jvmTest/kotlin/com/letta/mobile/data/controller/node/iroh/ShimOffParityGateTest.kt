package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
 * outage can never crash chat.
 *
 * Phase 1 strengthening (runbook):
 * - native successes must not dial the proxy transport;
 * - intentionally unavailable methods must return typed capability errors;
 * - a bare `success:false` is not enough for required product methods.
 */
class ShimOffParityGateTest {
    private companion object {
        const val PEER_NODE_ID = "peer-node-1"
    }

    private var savedFactory: (() -> AdminProxyTransport)? = null
    private val proxyDialCount = java.util.concurrent.atomic.AtomicInteger(0)

    @BeforeTest
    fun shimOff() {
        // Clear NativeAdmin's process-wide circuit breaker so a prior class's
        // native-timeout test can't leave native short-circuited for the
        // runtime-owned ops this gate asserts serve natively.
        NativeAdmin.resetCircuitForTest()
        proxyDialCount.set(0)
        // lettashim is unreachable: every proxy dial fails fast (not a hang).
        savedFactory = AdminProxyClient.defaultTransportFactory
        AdminProxyClient.defaultTransportFactory = {
            AdminProxyTransport { _, _, _ ->
                proxyDialCount.incrementAndGet()
                error("shim unavailable (parity gate: shim off)")
            }
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

        // message.get / tool_return.get project a single message out of this page,
        // so the runtime must actually hold the message the gate asks for.
        override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList) =
            AppServerInboundFrame.ConversationMessagesListResponse(
                command.requestId,
                true,
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "m-1")
                            put("type", "tool_return_message")
                            put("conversation_id", "conv-1")
                            put("tool_call_id", "tc-1")
                        },
                    )
                },
            )

        override suspend fun getReflectionSettings(command: AppServerCommand.GetReflectionSettings) =
            AppServerInboundFrame.GetReflectionSettingsResponse(
                command.requestId,
                true,
                buildJsonObject { put("trigger", "manual") },
            )

        override suspend fun setReflectionSettings(command: AppServerCommand.SetReflectionSettings) =
            AppServerInboundFrame.SetReflectionSettingsResponse(
                command.requestId,
                true,
                buildJsonObject { put("trigger", "manual") },
            )

        override suspend fun listModels(command: AppServerCommand.ListModels) =
            AppServerInboundFrame.ListModelsResponse(command.requestId, true, JsonArray(emptyList()))

        override suspend fun skillEnable(command: AppServerCommand.SkillEnable) =
            AppServerInboundFrame.SkillEnableResponse(command.requestId, true, "demo")

        override suspend fun skillDisable(command: AppServerCommand.SkillDisable) =
            AppServerInboundFrame.SkillDisableResponse(command.requestId, true)

        /** A native CronTask, the shape `schedule.*` projects to ScheduledMessage. */
        private fun cronTask() = buildJsonObject {
            put("id", "t-1")
            put("agent_id", "agent-1")
            put("conversation_id", "conv-1")
            put("name", "demo")
            put("description", "demo")
            put("cron", "0 0 * * *")
            put("recurring", true)
            put("prompt", "hi")
        }

        override suspend fun cronList(command: AppServerCommand.CronList) =
            AppServerInboundFrame.CronListResponse(command.requestId, true, buildJsonArray { add(cronTask()) })

        override suspend fun cronAdd(command: AppServerCommand.CronAdd) =
            AppServerInboundFrame.CronAddResponse(command.requestId, true, cronTask())

        override suspend fun cronGet(command: AppServerCommand.CronGet) =
            AppServerInboundFrame.CronGetResponse(command.requestId, true, found = true, task = cronTask())

        // lgns8.9: block.update_agent is a NATIVE write (memfs memory file).
        override suspend fun writeMemoryFile(command: AppServerCommand.WriteMemoryFile) =
            AppServerInboundFrame.WriteMemoryFileResponse(command.requestId, true, command.agentId, command.path)

        override suspend fun cronRuns(command: AppServerCommand.CronRuns) =
            AppServerInboundFrame.CronRunsResponse(command.requestId, true)

        override suspend fun cronTrigger(command: AppServerCommand.CronTrigger) =
            AppServerInboundFrame.CronTriggerResponse(command.requestId, true)

        override suspend fun cronUpdate(command: AppServerCommand.CronUpdate) =
            AppServerInboundFrame.CronUpdateResponse(command.requestId, true)

        override suspend fun cronDelete(command: AppServerCommand.CronDelete) =
            AppServerInboundFrame.CronDeleteResponse(command.requestId, true, found = true)

        override suspend fun cronDeleteAll(command: AppServerCommand.CronDeleteAll) =
            AppServerInboundFrame.CronDeleteAllResponse(command.requestId, true)
    }

    /**
     * Minimal live App Server controller. The matrix routes `approval.submit`
     * (and health/control ops) through `controller_native`, so a shim-off parity
     * run must model the controller being PRESENT — its absence is a separate,
     * explicitly asserted failure mode ([approvalSubmitFailsClosedWithoutController]).
     */
    private class GateController : AppServerController {
        override val state: Flow<AppServerControllerState> = flowOf(AppServerControllerState.Connected)

        var submittedApprovalRequestId: String? = null

        override suspend fun startRuntime(
            agentId: AgentId,
            conversationId: ConversationId,
            cwd: String?,
            mode: AppServerPermissionMode?,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): CanonicalRuntime = CanonicalRuntime(
            scope = AppServerRuntimeScope(agentId = agentId.value, conversationId = conversationId.value),
        )

        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = emptyFlow()

        override suspend fun sync(
            runtime: AppServerRuntimeScope,
            recoverApprovals: Boolean,
            forceDeviceStatus: Boolean,
        ): AppServerInboundFrame.SyncResponse = error("unused")

        override suspend fun abort(
            runtime: AppServerRuntimeScope,
            runId: String?,
        ): AppServerInboundFrame.AbortMessageResponse = error("unused")

        override suspend fun submitApproval(
            agentId: AgentId,
            conversationId: ConversationId?,
            approvalRequestId: String,
            approve: Boolean,
            reason: String?,
            toolCallId: String?,
            updatedInput: JsonObject?,
        ) {
            submittedApprovalRequestId = approvalRequestId
        }
    }

    /** A peer must already be paired for the pair.peer.* rows to be exercisable. */
    private fun pairedPeerStore() = InMemoryPairedPeerStore().apply {
        save(PairedPeer(nodeId = PEER_NODE_ID, name = "gate-peer", pairedAtMs = 1))
    }

    private fun productionRouter(
        controller: AppServerController? = GateController(),
        localBackendDir: String? = null,
    ): AdminRpcRouter =
        AdminRpcRegistry.buildRouter(
            adminBaseUrl = "http://127.0.0.1:9", // shim host — but transport is forced to fail
            controller = controller,
            subagentRegistrySource = EmptySubagentSource,
            pairingService = IrohPairingService(pairedPeerStore()),
            nativeClient = NativeRuntime(),
            shimRetired = true,
            vibesyncBaseUrl = null, // VibeSync not injected
            // lgns8.21.2: production (AppServerServeIrohCommand) ALWAYS injects the
            // native catalog's listing source, so the shim-off parity model must too.
            // Without it skill.list correctly reports capability_unavailable — a
            // wiring gap in the harness, not a parity gap in the controller.
            skillsListing = gateSkillsCatalog().asListingSource(),
            // lgns8.9: the read-only on-disk tier. Left OUT by default so the
            // store-owned rows are asserted to FAIL CLOSED without it; the
            // store-injected run is a separate, explicit expectation.
            localBackendDir = localBackendDir,
        )

    /**
     * The host-enumerated catalog the wrapper hydrates in production. Hydrated
     * here so the gate asserts the served-natively path, not the fail-closed
     * unhydrated one (covered by ControlCapabilityHandlersTest).
     */
    private fun gateSkillsCatalog() = NativeSkillsCatalog().apply {
        hydrateFromHost(buildJsonArray { add(buildJsonObject { put("name", "gate-skill") }) })
    }

    /** A tool id the controller-native catalog actually holds (tool.get must resolve). */
    private val firstCatalogToolId: String =
        NativeAdminCatalogs.toolCatalog().first().jsonObject.getValue("id").jsonPrimitive.content

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
        // pairing rows address a peer by node id; reflection.set carries settings
        put("node_id", PEER_NODE_ID)
        put("capabilities", "chat.read")
        put("trigger", "manual")
        put("step_count", "5")
        // lgns8.9 addressable rows: a schedule (cron task), a catalog tool, and
        // the ids the fixture store holds for the local_backend_store bucket.
        put("schedule_id", "t-1")
        put("tool_id", firstCatalogToolId)
        put("run_id", LocalBackendFixtureStore.RUN_ID)
        put("block_id", LocalBackendFixtureStore.blockId)
        put("label", LocalBackendFixtureStore.BLOCK_LABEL)
        put("value", "updated by the parity gate")
        if (method == "schedule.create") {
            put("messages", Json.parseToJsonElement("""[{"role":"user","content":"hi"}]"""))
            put("schedule", Json.parseToJsonElement("""{"type":"recurring","cron_expression":"0 0 * * *"}"""))
        }
        // native opt-ins / skill path installs
        if (method == "skill.install") put("skill_path", "/skills/demo")
        if (method == "approval.submit") {
            put(
                "payload",
                buildJsonObject {
                    put("streaming", false)
                    put(
                        "messages",
                        Json.parseToJsonElement(
                            """[{"type":"approval","approval_request_id":"approval-1","approve":true,""" +
                                """"approvals":[{"tool_call_id":"tc-1","approve":true}]}]""",
                        ),
                    )
                },
            )
        }
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
        // lgns8.21.10: DERIVED from the executable ownership matrix — every row whose
        // post-shim owner is app_server_v2/controller_native with fallback `none` must
        // serve natively. No second hand-maintained list to drift: a new matrix row
        // automatically becomes a parity expectation here.
        val nativeOk = IrohAdminOwnershipMatrix.shimFreeNativeMethods()
        assertTrue(nativeOk.isNotEmpty(), "ownership matrix declares no shim-free native methods")
        val dialsBefore = proxyDialCount.get()
        // Collect every failure: one regressed method must not mask the rest.
        val failures = mutableListOf<String>()
        nativeOk.forEach { method ->
            // A fresh router per method: destructive rows (pair.peer.revoke) must not
            // make the outcome depend on iteration order.
            val obj = dispatch(productionRouter(), method)
            if (obj["success"]?.jsonPrimitive?.content != "true") {
                failures += "$method -> $obj"
            } else if (!obj["error"]?.jsonPrimitive?.content.isNullOrBlank()) {
                failures += "$method succeeded but carried an error payload -> $obj"
            }
        }
        assertEquals(
            emptyList(),
            failures,
            "matrix rows owned by ${IrohAdminOwnershipMatrix.SHIM_FREE_OWNERS} with fallback=none must serve " +
                "natively shim-off (success:true required; bare success:false is not parity)",
        )
        assertEquals(
            dialsBefore,
            proxyDialCount.get(),
            "required native product methods must not dial AdminProxyClient when App Server answers",
        )
    }

    @Test
    fun capabilityGatedAndUnroutedOpsDenyCleanlyWithoutCrashing() = runTest {
        val router = productionRouter()
        // lgns8.21.10: derived — every capability_gated_unsupported row must return
        // the exact capability_unavailable contract, not a bare success:false.
        val gated = IrohAdminOwnershipMatrix.capabilityGatedMethods()
        assertTrue("conversation.delete" in gated, "conversation.delete must stay capability-gated")
        gated.forEach { method ->
            val obj = dispatch(router, method)
            assertEquals("false", obj["success"]?.jsonPrimitive?.content, "$method")
            assertTrue(
                obj["error"]?.jsonPrimitive?.content?.contains("capability_unavailable") == true,
                "$method must deny with capability_unavailable: $obj",
            )
        }

        // project.*: capability_unavailable (no VibeSync service injected)
        ProjectAdminHandlers.PROJECT_METHODS.forEach { method ->
            val obj = dispatch(router, method)
            assertEquals("false", obj["success"]?.jsonPrimitive?.content, "$method")
            assertTrue(obj["error"]?.jsonPrimitive?.content?.contains("capability_unavailable") == true, "$method")
        }
    }

    @Test
    fun boundedAdminMethodsDegradeToCleanFailureNotCrash() = runTest {
        val router = productionRouter()
        // lgns8.21.10: derived — bounded services (admin REST, VibeSync) are NOT
        // injected here, so every such row must degrade to a clean success:false
        // rather than throwing or hanging. Previously eight methods were listed by
        // hand while the matrix declared 45.
        val bounded = IrohAdminOwnershipMatrix.boundedServiceMethods()
        assertTrue(bounded.isNotEmpty(), "ownership matrix declares no bounded-service methods")
        bounded.forEach { method ->
            val obj = dispatch(router, method)
            assertEquals(
                "false",
                obj["success"]?.jsonPrimitive?.content,
                "$method should fail gracefully with its bounded service absent: $obj",
            )
        }
    }

    /**
     * The matrix is the single source of truth: every registered method is
     * classified into exactly one gate expectation above, so a new row cannot
     * land without gate coverage (lgns8.21.10).
     */
    @Test
    fun everyRegisteredMethodCarriesAGateExpectation() = runTest {
        val router = productionRouter()
        val covered = IrohAdminOwnershipMatrix.shimFreeNativeMethods() +
            IrohAdminOwnershipMatrix.capabilityGatedMethods() +
            IrohAdminOwnershipMatrix.boundedServiceMethods() +
            IrohAdminOwnershipMatrix.localBackendStoreMethods()
        assertEquals(
            emptySet(),
            router.registeredMethods - covered.toSet(),
            "registered admin_rpc methods without a shim-off parity expectation — " +
                "add an ownership matrix row (post_shim_owner/post_shim_fallback) for them",
        )
        assertEquals(
            covered.size,
            covered.toSet().size,
            "a method may not appear in two gate expectation buckets",
        )
    }

    /**
     * lgns8.9: with NO local backend root configured, every store-owned row must
     * deny with the typed capability contract — never throw, never hang, and
     * above all never dial an admin HTTP host as a fallback.
     */
    @Test
    fun localBackendStoreOwnedOpsFailClosedWithoutAStore() = runTest {
        val router = productionRouter()
        val storeOwned = IrohAdminOwnershipMatrix.localBackendStoreMethods()
        assertTrue(storeOwned.isNotEmpty(), "ownership matrix declares no local_backend_store methods")
        storeOwned.forEach { method ->
            val obj = dispatch(router, method)
            assertEquals("false", obj["success"]?.jsonPrimitive?.content, "$method")
            assertTrue(
                obj["error"]?.jsonPrimitive?.content?.contains("capability_unavailable") == true,
                "$method without a store must deny with capability_unavailable: $obj",
            )
        }
        assertEquals(0, proxyDialCount.get(), "an absent store must never fall back to an admin HTTP host")
    }

    /**
     * lgns8.9 FAIL-ON-REVERT: with the store wired, every store-owned row serves
     * from disk. If `buildRouter` ever ignores `localBackendDir` again (the
     * regression this slice repaired), these rows go back to
     * capability_unavailable and this test fails.
     */
    @Test
    fun localBackendStoreOwnedOpsServeFromTheStoreWhenInjected() = runTest {
        val root = kotlin.io.path.createTempDirectory("lgns8-9-gate-store").toFile()
        LocalBackendFixtureStore.create(root)
        val router = productionRouter(localBackendDir = root.absolutePath)

        val failures = mutableListOf<String>()
        IrohAdminOwnershipMatrix.localBackendStoreMethods().forEach { method ->
            val obj = dispatch(router, method)
            if (obj["success"]?.jsonPrimitive?.content != "true") failures += "$method -> $obj"
        }
        assertEquals(
            emptyList(),
            failures,
            "local_backend_store rows must serve from the injected store — a router that ignores " +
                "localBackendDir regresses them to capability_unavailable",
        )
        assertEquals(0, proxyDialCount.get(), "store-served rows must never dial an admin HTTP host")
    }

    /**
     * Explicit absent-service failure for the controller-backed row: with no live
     * controller the op must deny with `capability_unavailable`, never silently
     * succeed or dial the shim (lgns8.21.10 bounded-service contract).
     */
    @Test
    fun approvalSubmitFailsClosedWithoutController() = runTest {
        val router = productionRouter(controller = null)
        val obj = dispatch(router, "approval.submit")

        assertEquals("false", obj["success"]?.jsonPrimitive?.content, "$obj")
        assertTrue(
            obj["error"]?.jsonPrimitive?.content?.contains("capability_unavailable") == true,
            "approval.submit without a controller must deny with capability_unavailable: $obj",
        )
        assertEquals(0, proxyDialCount.get(), "absent controller must not fall back to the shim")
    }

    private suspend fun dispatch(router: AdminRpcRouter, method: String) = Json.parseToJsonElement(
        router.dispatch(AdminRpcInvocation("g", method, params(method), AdminRpcRequestContext.Authenticated)),
    ).jsonObject
}
