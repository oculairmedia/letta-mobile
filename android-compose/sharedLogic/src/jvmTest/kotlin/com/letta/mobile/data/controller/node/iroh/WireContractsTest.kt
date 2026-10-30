package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.controller.AppServerControllerState
import com.letta.mobile.data.plugin.host.PluginHostFixture
import com.letta.mobile.data.storage.InMemoryAssetStore
import com.letta.mobile.data.runtime.ConversationRowsSource
import com.letta.mobile.data.runtime.TurnIdentityLedger
import com.letta.mobile.data.runtime.TurnIdentityStore
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

    @Test
    fun everyFixtureMatchesTheRealHandlerExactly() = runTest {
        for (fixture in fixtures()) {
            for (case in fixture.cases) {
                val name = case.getValue("name").jsonPrimitive.content
                val host = case.getValue("host_state").jsonObject
                // New fixture families must supply a real host-state adapter, never be skipped.
                val known = setOf("controller_state", "conversations", "hidden_agent_ids", "visibility_index")
                require(host.keys.all { it in known }) { "Unsupported host_state for ${fixture.method}/$name: ${host.keys}" }
                require("controller_state" in host)
                val state = when (host.getValue("controller_state").jsonPrimitive.content) {
                    "connected" -> AppServerControllerState.Connected
                    "disconnected" -> AppServerControllerState.Disconnected()
                    else -> error("Unsupported controller state for ${fixture.method}/$name")
                }
                withRouter(state, host) { router ->
                    assertTrue(fixture.method in router.registeredMethods, "Fixture names unregistered method ${fixture.method}")
                    val context = case.getValue("context").jsonObject
                    require(context.keys.all { it in setOf("authenticated", "authorized_conversation_ids", "authorized_agent_ids") })
                    val invocation = AdminRpcInvocation(
                        "contract", fixture.method, case.getValue("params").jsonObject,
                        AdminRpcRequestContext(
                            authenticated = context.getValue("authenticated").jsonPrimitive.boolean,
                            authorizedConversationIds = context["authorized_conversation_ids"]?.jsonArray
                                ?.map { it.jsonPrimitive.content }?.toSet(),
                            authorizedAgentIds = context["authorized_agent_ids"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet(),
                        ),
                    )
                    val response = Json.parseToJsonElement(router.dispatch(invocation)).jsonObject
                    assertEquals(fixture.response(name), response, "${fixture.method}/$name wire response")
                }
            }
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
            AdminRpcHostServices::class.java to setOf("controller", "subagentRegistrySource", "vibesyncBaseUrl", "pluginRoutes"),
            AdminRpcNativeSources::class.java to setOf(
                "nativeClient", "localBackendDir", "skillsListing", "slashCommands", "modelExposureFile",
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
        val native = if (host?.containsKey("conversations") == true) conversationNative(host) else rejectCalls("native")
        val backend = File(dir, "backend")
        if (host?.get("visibility_index")?.jsonPrimitive?.boolean == true) {
            LocalBackendFixtureStore.create(backend)
            host.getValue("hidden_agent_ids").jsonArray.forEach { id ->
                val agent = id.jsonPrimitive.content
                require(agent.matches(Regex("[A-Za-z0-9_-]+")))
                File(backend, "agents/$agent.json").writeText("""{"id":"$agent","hidden":true}""")
            }
        }
        val identityStore = rejectCalls<TurnIdentityStore>("identity-store")
        val subagents = object : SubagentRegistrySource {
            override suspend fun list(conversationId: String, includeTerminal: Boolean) = error("Unexpected subagent read")
            override suspend fun todos(conversationId: String, toolCallId: String) = error("Unexpected subagent read")
        }
        try {
            runtime.start()
            val router = AdminRpcRegistry.buildRouter(
                services = AdminRpcHostServices(
                    controller = controller(state),
                    subagentRegistrySource = subagents,
                    vibesyncBaseUrl = "http://127.0.0.1:9",
                    pluginRoutes = PluginAdminHandlers(runtime, InMemoryAssetStore()),
                ),
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
                ),
                notifications = AdminRpcNotifications(
                    agentChanges = AgentChangeNotifier(plugins.scope),
                    conversationChanges = ConversationChangeNotifier(plugins.scope),
                    turnIdentity = TurnIdentityLedger(
                        identityStore, ConversationRowsSource { _, _ -> error("Unexpected transcript read") },
                    ) { 0L },
                    permissionModes = PermissionModePublisher(plugins.scope),
                ),
            )
            block(router)
        } finally {
            runtime.close()
            plugins.close()
            dir.deleteRecursively()
        }
    }
}
