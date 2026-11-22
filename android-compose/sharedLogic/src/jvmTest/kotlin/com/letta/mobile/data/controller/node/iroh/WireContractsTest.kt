package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.plugin.host.PluginHostFixture
import com.letta.mobile.data.storage.InMemoryAssetStore
import com.letta.mobile.data.runtime.ConversationRowsSource
import com.letta.mobile.data.runtime.TurnIdentityLedger
import com.letta.mobile.data.runtime.TurnIdentityStore
import com.letta.mobile.data.controller.node.iroh.servicecredentials.FileServiceCredentialPersistence
import com.letta.mobile.data.controller.node.iroh.servicecredentials.FileServiceSecretStore
import com.letta.mobile.data.controller.node.iroh.servicecredentials.ServiceCredentialContractHarness
import com.letta.mobile.data.controller.node.iroh.servicecredentials.ServiceCredentialRouteSet
import com.letta.mobile.data.controller.node.iroh.servicecredentials.ServiceCredentialStore
import com.letta.mobile.data.controller.node.iroh.servicecredentials.TypeSafeJevAdapter
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.iroh.WireContractFixture
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The runtime registry, not a lexical approximation, owns the uncovered-method ratchet. */
class WireContractsTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "scripts/ci/check-wire-contracts.py").isFile }
    private val fixtureRoot = File(root, "android-compose/sharedLogic/src/commonTest/resources/contracts")

    private fun fixtures(): List<WireContractFixture> = fixtureRoot.walkTopDown()
        .filter { it.isFile && it.extension == "json" }.sortedBy { it.path }
        // Semantic fixtures have their own common tests, not Admin RPC methods.
        .filterNot {
            it.relativeTo(fixtureRoot).invariantSeparatorsPath in setOf(
                "active-turn/v1.json", "object-verbs/v1.json", "current-focus/v1.json", "feed-delta/v1.json",
            )
        }
        .map { WireContractFixture(it.readText()) }.toList().also {
            assertTrue(it.isNotEmpty(), "Expected at least one contract fixture")
        }

    @Test
    fun everyRegisteredMethodHasAContractOrAnUncoveredBaselineEntry() = runTest {
        withRouter(AppServerControllerState.Connected) { router ->
            val baseline = File(root, "scripts/ci/wire-contract-uncovered-baseline.txt").readLines()
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            assertEquals(baseline.sorted().distinct(), baseline, "Baseline must be sorted and unique")
            val errors = coverageErrors(router.registeredMethods, fixtures().map { it.method }.toSet(), baseline.toSet())
            assertTrue(errors.isEmpty(), errors.joinToString("\n"))
            println("Wire contracts: ${router.methodCount} registered methods, ${baseline.size} uncovered")
        }
    }

    private val contextKeys = setOf("authenticated", "authorized_conversation_ids", "authorized_agent_ids", "authenticated_endpoint_id")

    @Test
    fun everyFixtureMatchesTheRealHandlerExactly() = runTest {
        for (fixture in fixtures()) {
            for (case in fixture.cases) {
                val name = case.getValue("name").jsonPrimitive.content
                val host = case.getValue("host_state").jsonObject
                // New fixture families must supply a real host-state adapter, never be skipped.
                val known = setOf(
                    "controller_state", "conversations", "hidden_agent_ids", "visibility_index",
                    "reflection_settings", "monitor_success", "toolset_response", "cron_control", "execute_command_response", "voice",
                    "pins", "shell_state", "service_credentials", "feed", "agents", "agent_list_error",
                    "messages",
                )
                require(host.keys.all { it in known }) { "Unsupported host_state for ${fixture.method}/$name: ${host.keys}" }
                require("controller_state" in host)
                if (runsThroughStreamServer(fixture, case, host)) continue
                val state = when (host.getValue("controller_state").jsonPrimitive.content) {
                    "connected" -> AppServerControllerState.Connected
                    "disconnected" -> AppServerControllerState.Disconnected()
                    else -> error("Unsupported controller state for ${fixture.method}/$name")
                }
                withRouter(state, host) { router ->
                    assertTrue(fixture.method in router.registeredMethods, "Fixture names unregistered method ${fixture.method}")
                    val context = case.getValue("context").jsonObject
                    require(context.keys.all { it in contextKeys })
                    val invocation = AdminRpcInvocation(
                        "contract", fixture.method, case.getValue("params").jsonObject,
                        AdminRpcRequestContext(
                            authenticated = context.getValue("authenticated").jsonPrimitive.boolean,
                            authorizedConversationIds = context["authorized_conversation_ids"]?.jsonArray
                                ?.map { it.jsonPrimitive.content }?.toSet(),
                            authorizedAgentIds = context["authorized_agent_ids"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet(),
                            authenticatedEndpointId = context["authenticated_endpoint_id"]?.jsonPrimitive?.content,
                        ),
                    )
                    val response = Json.parseToJsonElement(router.dispatch(invocation)).jsonObject
                    assertEquals(LettaJsMessagePaging.expanded(fixture.response(name)), response, "${fixture.method}/$name wire response")
                }
            }
        }
    }

    /**
     * Voice and service credential cases run through the real stream server, capability gate and routes
     * (one voice method is streamed, and several cases are refusals of the transport's gate). True when
     * [case] was one of them and has been checked.
     */
    private suspend fun runsThroughStreamServer(fixture: WireContractFixture, case: JsonObject, host: JsonObject): Boolean {
        val name = case.getValue("name").jsonPrimitive.content
        when {
            "voice" in host -> {
                val expected = case["server_frames"]?.jsonArray?.map { it.jsonObject }.orEmpty() + fixture.response(name)
                val frames = com.letta.mobile.data.controller.node.iroh.voice.VoiceContractHarness.run(fixture.method, case)
                assertEquals(expected, frames, "${fixture.method}/$name wire frames")
            }
            "service_credentials" in host -> {
                val response = ServiceCredentialContractHarness.run(fixture.method, case)
                assertEquals(fixture.response(name), response, "${fixture.method}/$name wire response")
            }
            else -> return false
        }
        return true
    }

    @Test
    fun producerContractCopiesMatchSharedClientFixtures() {
        val copies = listOf("run.record", "lease.upsert", "lease.list").map { "$it/wrapper-records-v1.json" } +
            listOf("cron.pause/control-v1.json", "cron.resume/control-v1.json")
        for (relative in copies) {
            val shared = Json.parseToJsonElement(File(fixtureRoot, relative).readText())
            val producer = Json.parseToJsonElement(File(root, "docs/reference/contracts/$relative").readText())
            assertEquals(shared, producer, "$relative producer/client fixture drift")
        }
    }

    @Test
    fun registryRatchetRejectsEveryKindOfMembershipDrift() {
        assertTrue(coverageErrors(setOf("new.method"), emptySet(), emptySet()).single().contains("New uncovered methods: new.method"))
        assertTrue(coverageErrors(setOf("a.method"), setOf("a.method"), setOf("a.method")).single().contains("Stale baseline entries"))
        assertTrue(coverageErrors(emptySet(), emptySet(), setOf("removed.method")).single().contains("removed.method"))
        assertTrue(coverageErrors(emptySet(), setOf("orphan.method"), emptySet()).single().contains("Fixtures for unregistered methods"))
    }

    private fun coverageErrors(registered: Set<String>, covered: Set<String>, baseline: Set<String>): List<String> = buildList {
        val new = registered - covered - baseline
        val stale = baseline - (registered - covered)
        val orphan = covered - registered
        if (new.isNotEmpty()) add("New uncovered methods: ${new.sorted().joinToString(", ")}")
        if (stale.isNotEmpty()) add("Stale baseline entries (remove them): ${stale.sorted().joinToString(", ")}")
        if (orphan.isNotEmpty()) add("Fixtures for unregistered methods: ${orphan.sorted().joinToString(", ")}")
    }

    private fun requireKnownWiring() {
        // An added optional service must not silently remain at its null default and
        // conceal a new conditional method family from this runtime enumeration.
        val builder = AdminRpcRegistry::class.java.declaredMethods.single { it.name == "buildRouter" }
        assertEquals(
            listOf(
                AdminRpcHostServices::class.java, AdminRpcPairing::class.java,
                AdminRpcNativeSources::class.java, AdminRpcNotifications::class.java,
            ),
            builder.parameterTypes.toList(),
            "Update full wire-contract host wiring for new router inputs; never leave a tier disabled",
        )
        val inputs = mapOf(
            AdminRpcHostServices::class.java to setOf(
                "controller", "subagentRegistrySource", "vibesyncBaseUrl", "beadsBaseUrl", "beadsTokenFile", "beadsRoutes", "pluginRoutes",
                "canvasMountRoutes", "secretRequestRoutes",
            ),
            AdminRpcNativeSources::class.java to setOf(
                "nativeClient", "localBackendDir", "skillsListing", "slashCommands", "modelExposureFile", "wrapperRecords",
            ),
            AdminRpcPairing::class.java to setOf("pairingService", "pairingQr"),
            AdminRpcNotifications::class.java to setOf("agentChanges", "conversationChanges", "turnIdentity", "permissionModes"),
        )
        inputs.forEach { (type, configured) ->
            val fields = type.declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { it.name }.toSet()
            assertEquals(configured, fields, "Update full wire-contract host wiring for ${type.simpleName}; never omit a new tier")
        }
    }

    private inline fun <reified T : Any> rejectCalls(label: String): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected $label call: ${method.name}") } as T

    private fun controller(state: AppServerControllerState): AppServerController {
        val snapshot = MutableStateFlow(state)
        return Proxy.newProxyInstance(
            AppServerController::class.java.classLoader, arrayOf(AppServerController::class.java),
        ) { _, method, _ ->
            check(method.name == "getState") { "Unexpected controller call: ${method.name}" }
            snapshot
        } as AppServerController
    }

    private fun conversationNative(host: kotlinx.serialization.json.JsonObject): AppServerClient = Proxy.newProxyInstance(
        AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java),
    ) { _, method, args ->
        check(method.name == "conversationList") { "Unexpected native call: ${method.name}" }
        val command = args[0] as com.letta.mobile.data.transport.appserver.AppServerCommand.ConversationList
        val query = command.query.orEmpty()
        val hide = query["include_hidden"] != kotlinx.serialization.json.JsonPrimitive(true)
        val rows = host.getValue("conversations").jsonArray.filterNot {
            hide && it.jsonObject["hidden"]?.jsonPrimitive?.content == "true"
        }
        val agent = query["agent_id"]?.jsonPrimitive?.content
        val own = rows.filter { agent == null || it.jsonObject["agent_id"]?.jsonPrimitive?.content == agent }
        val after = query["after"]?.jsonPrimitive?.content
        val cursor = own.indexOfFirst { it.jsonObject["id"]?.jsonPrimitive?.content == after }
        val limit = query["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
        com.letta.mobile.data.transport.appserver.AppServerInboundFrame.ConversationListResponse(
            command.requestId, true,
            conversations = kotlinx.serialization.json.JsonArray(own.drop(cursor + 1).take(limit)),
        )
    } as AppServerClient

    private fun reflectionNative(host: JsonObject): AppServerClient = Proxy.newProxyInstance(
        AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java),
    ) { _, method, args ->
        val stored = host.getValue("reflection_settings").jsonObject
        when (method.name) {
            "getReflectionSettings" -> {
                val command = args[0] as com.letta.mobile.data.transport.appserver.AppServerCommand.GetReflectionSettings
                com.letta.mobile.data.transport.appserver.AppServerInboundFrame.GetReflectionSettingsResponse(
                    requestId = command.requestId, success = true, reflectionSettings = stored,
                )
            }
            "setReflectionSettings" -> {
                val command = args[0] as com.letta.mobile.data.transport.appserver.AppServerCommand.SetReflectionSettings
                com.letta.mobile.data.transport.appserver.AppServerInboundFrame.SetReflectionSettingsResponse(
                    requestId = command.requestId,
                    success = true,
                    reflectionSettings = JsonObject(stored + command.settings),
                    scope = command.scope,
                )
            }
            else -> error("Unexpected native call: ${method.name}")
        }
    } as AppServerClient

    private fun monitorNative(host: JsonObject): AppServerClient = Proxy.newProxyInstance(
        AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java),
    ) { _, method, args ->
        check(method.name == "monitorStop") { "Unexpected native call: ${method.name}" }
        val command = args[0] as com.letta.mobile.data.transport.appserver.AppServerCommand.MonitorStop
        assertTrue(command.requestId.isNotBlank())
        assertEquals("process-1", command.processId)
        assertEquals(com.letta.mobile.data.transport.appserver.AppServerRuntimeScope("agent-1", "conv-1"), command.runtime)
        com.letta.mobile.data.transport.appserver.AppServerInboundFrame.MonitorStopResponse(
            command.requestId, host.getValue("monitor_success").jsonPrimitive.boolean,
        )
    } as AppServerClient

    private fun toolsetNative(host: JsonObject): AppServerClient = Proxy.newProxyInstance(
        AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java),
    ) { _, method, args ->
        check(method.name == "updateToolset") { "Unexpected native call: ${method.name}" }
        val command = args[0] as com.letta.mobile.data.transport.appserver.AppServerCommand.UpdateToolset
        val answer = host.getValue("toolset_response").jsonObject
        com.letta.mobile.data.transport.appserver.AppServerInboundFrame.UpdateToolsetResponse(
            requestId = command.requestId.orEmpty(),
            success = answer.getValue("success").jsonPrimitive.boolean,
            currentToolset = answer.getValue("current_toolset").jsonPrimitive.content,
            error = answer.getValue("error").jsonPrimitive.content,
        )
    } as AppServerClient

    private fun cronControlNative(host: JsonObject): AppServerClient = Proxy.newProxyInstance(
        AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java),
    ) { _, method, args ->
        val stored = host.getValue("cron_control").jsonObject
        val success = stored.getValue("success").jsonPrimitive.boolean
        val task = stored["task"] as? JsonObject
        val error = stored["error"]?.jsonPrimitive?.content
        when (method.name) {
            "cronPause" -> {
                val command = args[0] as com.letta.mobile.data.transport.appserver.AppServerCommand.CronPause
                assertEquals("task-1", command.taskId)
                assertTrue(command.requestId.isNotBlank())
                com.letta.mobile.data.transport.appserver.AppServerInboundFrame.CronPauseResponse(command.requestId, success, task, error)
            }
            "cronResume" -> {
                val command = args[0] as com.letta.mobile.data.transport.appserver.AppServerCommand.CronResume
                assertEquals("task-1", command.taskId)
                assertEquals("2026-12-01T09:00:00Z", command.scheduledFor)
                assertTrue(command.requestId.isNotBlank())
                com.letta.mobile.data.transport.appserver.AppServerInboundFrame.CronResumeResponse(command.requestId, success, task, error)
            }
            else -> error("Unexpected native call: ${method.name}")
        }
    } as AppServerClient

    private fun commandNative(host: JsonObject): AppServerClient = Proxy.newProxyInstance(
        AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java),
    ) { _, method, args ->
        check(method.name == "executeCommand") { "Unexpected native call: ${method.name}" }
        val command = args[0] as com.letta.mobile.data.transport.appserver.AppServerCommand.ExecuteCommand
        val stored = host.getValue("execute_command_response").jsonObject
        Json.decodeFromJsonElement(
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.ExecuteCommandResponse.serializer(),
            JsonObject(stored + ("request_id" to kotlinx.serialization.json.JsonPrimitive(command.requestId))),
        )
    } as AppServerClient

    /**
     * letta-code 0.30.5 `listAgents` over the case's stored rows (`host_state.agents`): hidden records
     * dropped (`hidden: true`, or unset with the `role:subagent` tag), then the first `limit` rows,
     * where only a JSON number counts and anything else means 20. `agent_list_error` answers
     * `success: false` with that text. Any other native call fails the test.
     */
    private fun agentNative(host: JsonObject): AppServerClient = Proxy.newProxyInstance(
        AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java),
    ) { _, method, args ->
        check(method.name == "agentList") { "Unexpected native call: ${method.name}" }
        val command = args[0] as com.letta.mobile.data.transport.appserver.AppServerCommand.AgentList
        val error = host["agent_list_error"]?.jsonPrimitive?.content
        val limit = (command.query?.get("limit") as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeUnless { it.isString }?.content?.toIntOrNull() ?: 20
        val shown = host.getValue("agents").jsonArray.map { it.jsonObject }.filterNot(::isHiddenAgentRecord)
        com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AgentListResponse(
            requestId = command.requestId,
            success = error == null,
            agents = kotlinx.serialization.json.JsonArray(if (error == null) shown.take(limit) else emptyList()),
            error = error,
        )
    } as AppServerClient

    private fun isHiddenAgentRecord(row: JsonObject): Boolean = when (row["hidden"]?.jsonPrimitive?.content) {
        "true" -> true
        "false" -> false
        else -> row["tags"]?.jsonArray.orEmpty().any { it.jsonPrimitive.content == "role:subagent" }
    }

    /** The App Server's message paging over the case's stored rows ([LettaJsMessagePaging]); any other native call fails. */
    private fun messagesNative(host: JsonObject): AppServerClient {
        val paging = LettaJsMessagePaging.fromFixture(host.getValue("messages").jsonObject)
        return Proxy.newProxyInstance(
            AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java),
        ) { _, method, args ->
            check(method.name == "conversationMessagesList") { "Unexpected native call: ${method.name}" }
            paging.list(args[0] as com.letta.mobile.data.transport.appserver.AppServerCommand.ConversationMessagesList)
        } as AppServerClient
    }

    private fun fixtureNative(host: JsonObject?): AppServerClient = when {
        host?.containsKey("agents") == true -> agentNative(host)
        host?.containsKey("messages") == true -> messagesNative(host)
        host?.containsKey("monitor_success") == true -> monitorNative(host)
        host?.containsKey("toolset_response") == true -> toolsetNative(host)
        host?.containsKey("cron_control") == true -> cronControlNative(host)
        host?.containsKey("execute_command_response") == true -> commandNative(host)
        host?.containsKey("conversations") == true -> conversationNative(host)
        host?.containsKey("reflection_settings") == true -> reflectionNative(host)
        else -> rejectCalls("native")
    }

    private suspend fun withRouter(
        state: AppServerControllerState,
        host: kotlinx.serialization.json.JsonObject? = null,
        block: suspend (AdminRpcRouter) -> Unit,
    ) {
        requireKnownWiring()
        val dir = kotlin.io.path.createTempDirectory("wire-contract-host").toFile()
        val plugins = PluginHostFixture(File(dir, "plugins").toPath())
        val runtime = plugins.runtime()
        // Health must not query native data. Any accidental native call fails rather than
        // returning a permissive fake response for an id the App Server cannot resolve.
        val native = fixtureNative(host)
        val backend = File(dir, "backend")
        if (host?.get("visibility_index")?.jsonPrimitive?.boolean == true) {
            LocalBackendFixtureStore.create(backend)
            host.getValue("hidden_agent_ids").jsonArray.forEach { id ->
                val agent = id.jsonPrimitive.content
                require(agent.matches(Regex("[A-Za-z0-9_-]+")))
                File(backend, "agents/$agent.json").writeText("""{"id":"$agent","hidden":true}""")
            }
        }
        // message.list serves every row through the turn identity ledger, as the wrapper wires it: a file store in
        // host state and the App Server's own rows. Every other family must never touch the ledger.
        val serving = host?.containsKey("messages") == true
        val identityStore =
            if (serving) FileTurnIdentityStore(File(dir, "turn-identity")) else rejectCalls<TurnIdentityStore>("identity-store")
        val identityRows =
            if (serving) AppServerConversationRows(native) else ConversationRowsSource { _, _ -> error("Unexpected transcript read") }
        try {
            runtime.start()
            val router = AdminRpcRegistry.buildRouter(
                services = fullServices(state, PluginAdminHandlers(runtime, InMemoryAssetStore()), dir),
                pairing = AdminRpcPairing(
                    pairingService = IrohPairingService(InMemoryPairedPeerStore()),
                    pairingQr = PairingQrConfig(PairQrSigner { _, _, _ -> error("Unexpected QR mint") }, "0".repeat(64)),
                ),
                native = AdminRpcNativeSources(
                    nativeClient = native,
                    localBackendDir = backend.path.takeUnless { host?.get("visibility_index")?.jsonPrimitive?.boolean == false },
                    skillsListing = NativeSkillsCatalog(
                        hostEnumeration = { kotlinx.serialization.json.JsonArray(emptyList()) },
                    ).asListingSource(),
                    slashCommands = NativeSlashCommandCatalog(),
                    modelExposureFile = File(dir, "model-exposure.json").path,
                    wrapperRecords = com.letta.mobile.data.controller.node.iroh.records.FileWrapperRecordStore(
                        File(dir, "wrapper-records.json").toPath(),
                    ),
                ),
                notifications = AdminRpcNotifications(
                    agentChanges = AgentChangeNotifier(plugins.scope),
                    conversationChanges = ConversationChangeNotifier(plugins.scope),
                    turnIdentity = TurnIdentityLedger(identityStore, identityRows) { 0L },
                    permissionModes = PermissionModePublisher(plugins.scope),
                ),
            )
            // The production wrapper registers the voice, pins, shell state, service credential and feed routes on the
            // built router; so does this host.
            listOf(
                voiceRoutes(dir), pinsRoutes(host), shellStateRoutes(host), serviceCredentialRoutes(dir), feedRoutes(host, dir),
            ).forEach { it.register(router) }
            block(router)
        } finally {
            runtime.close()
            plugins.close()
            dir.deleteRecursively()
        }
    }

    private fun fullServices(
        state: AppServerControllerState,
        pluginRoutes: AdminRpcRoutes,
        dir: File,
    ): AdminRpcHostServices = AdminRpcHostServices(
        controller = controller(state),
        subagentRegistrySource = rejectSubagentCalls(),
        vibesyncBaseUrl = "http://127.0.0.1:9",
        beadsBaseUrl = "http://127.0.0.1:9",
        beadsRoutes = beadsRoutes(),
        pluginRoutes = pluginRoutes,
        canvasMountRoutes = canvasMountRoutes(dir),
        secretRequestRoutes = secretRequestRoutes(),
    )

    /** The real pins routes over the case's stored list (`host_state.pins.stored`, absent or null: nothing stored). */
    private fun pinsRoutes(host: JsonObject?): AdminRpcRoutes {
        val stored = host?.get("pins")?.jsonObject?.get("stored")?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        val seed = stored?.let {
            Json.decodeFromJsonElement(com.letta.mobile.data.pins.shared.AgentPinsSnapshot.serializer(), it)
        } ?: com.letta.mobile.data.pins.shared.AgentPinsSnapshot()
        return com.letta.mobile.data.controller.node.iroh.pins.AgentPinsRouteSet(
            com.letta.mobile.data.controller.node.iroh.pins.AgentPinsStore(
                com.letta.mobile.data.controller.node.iroh.pins.InMemoryAgentPinsPersistence(seed),
            ),
            // Never attached: the push is covered by AgentPinsRoutesTest and ConnectionRegistryTest.
            com.letta.mobile.data.controller.node.iroh.pins.AgentPinsPublisher(
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            ),
        )
    }

    /** The real shell state routes over the case's stored document (`host_state.shell_state.stored`, absent or null: nothing stored). */
    private fun shellStateRoutes(host: JsonObject?): AdminRpcRoutes {
        val stored = host?.get("shell_state")?.jsonObject?.get("stored")?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        val seed = stored?.let {
            Json.decodeFromJsonElement(com.letta.mobile.data.shellstate.ShellStateSnapshot.serializer(), it)
        } ?: com.letta.mobile.data.controller.node.iroh.shellstate.ShellStateStore.EMPTY
        return com.letta.mobile.data.controller.node.iroh.shellstate.ShellStateRouteSet(
            com.letta.mobile.data.controller.node.iroh.shellstate.ShellStateStore(
                com.letta.mobile.data.controller.node.iroh.shellstate.InMemoryShellStatePersistence(seed),
                clock = { com.letta.mobile.data.controller.node.iroh.shellstate.ShellStateFixtures.CLOCK_MS },
            ),
            // Never attached: the push is covered by ShellStateRoutesTest and ConnectionRegistryTest.
            com.letta.mobile.data.controller.node.iroh.shellstate.ShellStatePublisher(
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            ),
        )
    }

    /** The real feed route over the journal the case's `host_state.feed.turns` wrote, in a file store beside the host's other state. */
    private fun feedRoutes(host: JsonObject?, dir: File): AdminRpcRoutes {
        val store = File(dir, "conversation-feed").toPath()
        val journal = com.letta.mobile.data.controller.node.iroh.feed.FeedContractSeed.journal(host, store)
        return com.letta.mobile.data.controller.node.iroh.feed.FeedRoutes.forHost(journal)
    }

    /** The real voice routes over file stores in the test's directory; any speech server call fails the test. */
    private fun voiceRoutes(dir: File): AdminRpcRoutes = com.letta.mobile.data.controller.node.iroh.voice.VoiceRoutes(
        com.letta.mobile.data.controller.node.iroh.voice.VoiceSetupStore(
            com.letta.mobile.data.controller.node.iroh.voice.FileVoiceSetupPersistence(File(dir, "voice/voice-setup.json").toPath()),
            com.letta.mobile.data.controller.node.iroh.voice.FileVoiceKeyStore(File(dir, "voice/voice-speech-key").toPath()),
        ) { 0L },
        com.letta.mobile.data.controller.node.iroh.voice.KtorVoiceSpeechUpstream(
            io.ktor.client.engine.mock.MockEngine { error("Unexpected speech server call") },
        ),
        com.letta.mobile.data.controller.node.iroh.voice.VoiceInFlight(),
    ) { 0L }
    /** The real service credential routes over file stores in the test's directory; any TypeSafe call fails the test. */
    private fun serviceCredentialRoutes(dir: File): AdminRpcRoutes = ServiceCredentialRouteSet(
        ServiceCredentialStore(
            listOf(TypeSafeJevAdapter(io.ktor.client.engine.mock.MockEngine { error("Unexpected TypeSafe call") })),
            FileServiceCredentialPersistence(File(dir, "services/service-credentials.json").toPath()),
            FileServiceSecretStore(File(dir, "services/service-credential-secrets").toPath()),
        ),
    ) { 0L }

    /** The real canvas mount routes under a secure policy; no fixture may reach the host's records. */
    private fun canvasMountRoutes(dir: File): AdminRpcRoutes = com.letta.mobile.data.controller.node.iroh.mounts.CanvasMountRoutes(
        com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy.PairedPeersOnly,
        com.letta.mobile.data.controller.node.iroh.mounts.CanvasMountStoreLocation(
            File(dir, "product-state").apply { mkdirs() }.toPath(),
            com.letta.mobile.data.model.HostId("contract-host"),
        ),
        com.letta.mobile.data.controller.node.iroh.mounts.CanvasMountSources(
            com.letta.mobile.data.controller.node.iroh.mounts.CanvasMountCatalog(
                com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory(),
                com.letta.mobile.data.canvas.InMemoryCanvasRelayStore(),
            ),
            rejectCalls("canvas mount directory"),
        ),
        com.letta.mobile.data.controller.node.iroh.mounts.TEST_CALL_SCOPE,
    )

    /** The real secret request routes under a secure policy over a test keystore; no fixture reaches a vault. */
    private fun secretRequestRoutes(): AdminRpcRoutes = checkNotNull(
        com.letta.mobile.data.controller.node.iroh.secrets.SecretRequestFeature.forHost(
            com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy.PairedPeersOnly,
            com.letta.mobile.data.controller.node.iroh.secrets.SecretRequestStorage(
                com.letta.mobile.data.secrets.host.TestKeystoreProvider(),
                com.letta.mobile.data.secrets.journal.InMemorySecretRequestJournal(),
            ),
            com.letta.mobile.data.secrets.host.SecretItemScope.PerAgent,
        ),
    ).routes

    private fun rejectSubagentCalls(): SubagentRegistrySource = object : SubagentRegistrySource {
        override suspend fun list(conversationId: String, includeTerminal: Boolean) = error("Unexpected subagent read")
        override suspend fun todos(conversationId: String, toolCallId: String) = error("Unexpected subagent read")
    }

    private fun beadsRoutes(): AdminRpcRoutes {
        val client = com.letta.mobile.data.controller.node.iroh.beads.BeadsRouteClient("http://127.0.0.1:9", null)
        val routes = com.letta.mobile.data.controller.node.iroh.beads.BeadsRoutes
        return AdminRpcRoutes { router ->
            (routes.reads + routes.writes).forEach { (method, operation) ->
                router.register(method) { params -> client.execute(operation, params) }
            }
        }
    }
}
