package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.emptyFlow
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WrapperRecordsProductionWiringTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    // Most-specific accepted address wins, including optional filters and nested runtime fields.
    // Global includes resource-id-only APIs (run_id, task_id, block_id); it is not a safety verdict.
    private val addressClasses = mapOf(
        "agent-addressed" to words("""
            agent.delete agent.get agent.update block.attach block.detach block.list_agent
            block.create_agent block.delete_agent block.update_agent conversation.create conversation.list
            conversation.list_agent cron.delete_all goal.command goal.get memfs.list memfs.read memfs.history
            memfs.commit_diff memfs.file_at_ref memfs.enable memfs.write secret.list secret.apply
            workspace.search_files workspace.read_file schedule.create schedule.delete schedule.get schedule.list
            skill.list_agent slash_command.list_agent tool.attach tool.detach
            canvas.mount.default.set canvas.mount.default.clear
        """),
        "conversation-addressed" to words("""
            agent.context approval.submit conversation.archive conversation.compact conversation.context_limit
            conversation.delete conversation.fork conversation.get conversation.recompile conversation.restore
            conversation.update cron.add cron.list cron.update feed.subscribe message.get message.list
            model.update monitor.stop permission_mode.get permission_mode.set reflection.get reflection.run
            reflection.set run.list slash_command.execute subagent.dismiss subagent.list subagent.todos
            tool_return.get toolset.update canvas.mount.resolve canvas.mount.binding.bind
            canvas.mount.binding.replace canvas.mount.candidates
        """),
        "global" to words("""
            agent.count agent.create agent.list archive.list block.create block.delete block.get block.list block.update
            folder.list group.list health.check identity.get identity.list job.get job.list mcp.list
            model.list model.list.embedding model.exposure.get model.exposure.set passage.create passage.delete passage.list
            project.archive project.beadsRemoteStatus project.create project.delete project.get project.list
            project.provisionBeadsRemote project.update provider.connect provider.disconnect provider.list
            run.get run.record lease.upsert lease.list step.list skill.install skill.list skill.uninstall slash_command.list
            tool.create tool.delete tool.get tool.list tool.update cron.delete cron.get cron.runs cron.trigger cron.pause cron.resume
            pair.invite.create pair.invite.list pair.invite.revoke pair.peer.get pair.peer.list pair.peer.rename
            pair.peer.revoke pair.peer.set_agents pair.peer.set_capabilities
            plugins.list plugins.inspect plugins.install plugins.consent plugins.enable plugins.disable plugins.remove
            plugins.disableAll plugins.page.read plugins.view.action
            beads.ready beads.ready.count beads.issues.get beads.issues.list beads.dependencies.tree
            beads.dependencies.cycles beads.events.list beads.stats beads.context beads.config.get beads.memories.list
            beads.issues.create beads.issues.update beads.issues.close beads.issues.claim beads.issues.release
            beads.issues.claimNext beads.dependencies.add beads.dependencies.remove beads.config.set beads.memories.write
            voice.setup.get voice.setup.set voice.setup.clear voice.setup.test voice.models.list voice.transcribe voice.speech
            pins.get pins.set shell.state.get shell.state.propose service.credential.list service.credential.set
            service.credential.test service.credential.remove secret.request secret.request.status secret.request.list
            secret.request.get secret.request.submit secret.request.cancel
        """),
    )

    private fun words(value: String): Set<String> = value.trim().split(Regex("\\s+")).toSet()

    private val controllers = mutableListOf<DefaultAppServerController>()

    @AfterEach
    fun closeControllers() { controllers.forEach { it.close() } }

    private fun controller(): DefaultAppServerController {
        val native = Proxy.newProxyInstance(AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java)) {
            _, method, _ ->
            check(method.name == "getEvents") { "Unexpected App Server call: ${method.name}" }
            emptyFlow<AppServerReceivedFrame>()
        } as AppServerClient
        return DefaultAppServerController(native).also(controllers::add)
    }

    @Test
    fun everyProductionMethodHasAnExplicitAddressClassification() = runTest {
        val methods = buildProductionAdminRouter(
            controller(),
            beadsBaseUrl = "http://127.0.0.1:1",
            voiceRoutes = com.letta.mobile.data.controller.node.iroh.voice.VoiceEndpointRoutes.forHost(temporaryDirectory),
            pinsRoutes = com.letta.mobile.data.controller.node.iroh.pins.AgentPinsRoutes.forOnDeviceHost(
                temporaryDirectory, backgroundScope,
            ),
            shellStateRoutes = com.letta.mobile.data.controller.node.iroh.shellstate.ShellStateRoutes.forHost(
                temporaryDirectory, com.letta.mobile.data.controller.node.iroh.shellstate.ShellStatePublisher(backgroundScope),
            ),
            serviceCredentialRoutes = com.letta.mobile.data.controller.node.iroh.servicecredentials.ServiceCredentialRoutes
                .forHost(temporaryDirectory),
            feedRoutes = com.letta.mobile.data.controller.node.iroh.feed.FeedRoutes.forHost(
                com.letta.mobile.data.controller.node.iroh.feed.FeedJournalStore(temporaryDirectory),
            ),
        ).registeredMethods + com.letta.mobile.data.canvas.mount.wire.CanvasMountWire.METHODS +
            com.letta.mobile.data.secrets.wire.SecretRequestRouteWire.METHODS +
            com.letta.mobile.data.controller.node.iroh.PairingAdminHandlers.methods +
            com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry.subagentMethods
        val classified = addressClasses.values.flatten()
        assertEquals(classified.size, classified.toSet().size, "Each method must have exactly one address classification")
        assertEquals(classified.toSortedSet(), methods.toSortedSet(), "Unclassified admin RPC methods")
        println("ADMIN_RPC_ADDRESS_CLASSES=" + addressClasses.mapValues { it.value.size })
    }

    /**
     * letta-mobile-wisqe.2: the classification above is enforced, not just recorded. On the production router, an
     * agent-scoped peer holding the default chat grant (plus admin.full, so no capability gate hides a gap) is refused
     * every agent-addressed method naming another agent and every conversation-addressed method naming another agent's
     * conversation, before the handler can reach the App Server (whose only answer is the owner lookup).
     */
    @Test
    fun aScopedPeerIsRefusedEveryAddressedMethodOfAnotherAgent() = runTest {
        val native = Proxy.newProxyInstance(AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java)) {
            _, method, args ->
            when (method.name) {
                "getEvents" -> emptyFlow<AppServerReceivedFrame>()
                "conversationRetrieve" -> {
                    val command = args[0] as com.letta.mobile.data.transport.appserver.AppServerCommand.ConversationRetrieve
                    com.letta.mobile.data.transport.appserver.AppServerInboundFrame.ConversationRetrieveResponse(
                        requestId = command.requestId, success = true,
                        conversation = buildJsonObject { put("id", command.conversationId); put("agent_id", "agent-other") },
                    )
                }
                else -> error("Unexpected App Server call: ${method.name}")
            }
        } as AppServerClient
        val router = buildProductionAdminRouter(
            controller(),
            nativeClient = native,
            feedRoutes = com.letta.mobile.data.controller.node.iroh.feed.FeedRoutes.forHost(
                com.letta.mobile.data.controller.node.iroh.feed.FeedJournalStore(temporaryDirectory),
            ),
        )
        val scoped = com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext(
            authenticated = true,
            authorizedAgentIds = setOf("agent-mine"),
            grantedCapabilities = com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities.CHAT_ROLE + "admin.full",
        )
        val checked = mutableListOf<String>()
        for ((addressing, params) in listOf(
            "agent-addressed" to buildJsonObject { put("agent_id", "agent-other") },
            "conversation-addressed" to buildJsonObject { put("agent_id", "agent-mine"); put("conversation_id", "conv-other") },
        )) {
            // feed.subscribe proves the owner in its handler and reads the journal under the owner's own key, so a
            // foreign conversation id names an empty journal of the peer's own agent; FeedRoutesTest covers it.
            val methods = addressClasses.getValue(addressing).filter { it in router.registeredMethods && it != "feed.subscribe" }
            for (method in methods) {
                val reply = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("r", method, params, scoped))).jsonObject
                assertEquals(false, reply.getValue("success").jsonPrimitive.boolean, "$method: $reply")
                assertEquals(
                    com.letta.mobile.data.controller.node.iroh.AgentScopeGuard.DENIED,
                    reply["error"]?.jsonPrimitive?.content,
                    "$method must be refused by agent scope",
                )
                checked += method
            }
        }
        assertTrue(checked.size > 40, "the production router must register the addressed methods: $checked")
        println("ADMIN_RPC_SCOPE_ENFORCED=${checked.size}")
    }

    @Test
    fun explicitStorePathPersistsAndDefaultLivesBesideModelExposure() = runTest {
        val explicit = temporaryDirectory.resolve("explicit.json")
        val model = temporaryDirectory.resolve("model-exposure.json")
        val router = buildProductionAdminRouter(controller(), runRecordsFile = explicit.toString(), modelExposureFile = model.toString())
        val params = buildJsonObject {
            put("id", "lease-1"); put("owner", "agent-A"); put("label", "work")
            put("startedAt", "2026-11-01T00:00:00Z"); put("dueAt", "2026-11-03T00:00:00Z"); put("state", "active")
        }
        val written = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("write", "lease.upsert", params))).jsonObject
        assertTrue(written.getValue("success").jsonPrimitive.boolean, "$written")
        assertTrue(Files.isRegularFile(explicit))
        val reopened = buildProductionAdminRouter(controller(), runRecordsFile = explicit.toString(), modelExposureFile = model.toString())
        val frame = reopened.dispatch(AdminRpcInvocation("read", "lease.list", JsonObject(emptyMap())))
        val response = Json.parseToJsonElement(frame).jsonObject
        val row = response.getValue("result").jsonObject.getValue("leases").jsonArray.single().jsonObject
        assertEquals("lease-1", row.getValue("id").jsonPrimitive.content)
        val default = buildProductionAdminRouter(controller(), modelExposureFile = model.toString())
        default.dispatch(AdminRpcInvocation("default", "lease.upsert", params))
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("run-records.json")))
    }

    @Test
    fun corruptProductionStoreWarnsWithoutBodyAndServesEmpty() = runTest {
        val path = temporaryDirectory.resolve("corrupt.json")
        Files.writeString(path, "{CANARY-PRIVATE-DETAIL")
        val recorder = TelemetryRecorder()
        Telemetry.installRecorder(recorder).use {
            val router = buildProductionAdminRouter(controller(), runRecordsFile = path.toString())
            assertTrue(recorder.snapshot().any { it.tag == "WrapperRecords" && it.name == "store.corrupt" &&
                it.level == Telemetry.Level.WARN })
            assertTrue(recorder.snapshot().none { it.toString().contains("CANARY-PRIVATE-DETAIL") })
            val frame = router.dispatch(AdminRpcInvocation("read", "lease.list", JsonObject(emptyMap())))
            val result = Json.parseToJsonElement(frame).jsonObject.getValue("result").jsonObject
            assertTrue(result.getValue("leases").jsonArray.isEmpty())
        }
    }

    @Test
    fun unconfiguredRouterConstructionLeavesSharedHostStateUntouched() {
        val previousHome = requireNotNull(System.getProperty("user.home"))
        val file = temporaryDirectory.resolve(".letta/canvas-relay/run-records.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "{shared-state-canary")
        System.setProperty("user.home", temporaryDirectory.toString())
        try {
            buildProductionAdminRouter(controller())
            assertTrue(Files.exists(file), "an unconfigured factory must not quarantine shared host state")
            assertEquals("{shared-state-canary", Files.readString(file))
        } finally {
            System.setProperty("user.home", previousHome)
        }
    }

    @Test
    fun optionAndEnvironmentVariableAreRegistered() {
        val option = AppServerServeIrohCommand().registeredOptions().single { "--run-records-file" in it.names }
        assertEquals("LETTA_RUN_RECORDS_STORE", (option as OptionWithValues<*, *, *>).envvar)
    }
}
