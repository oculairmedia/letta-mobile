package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.commands.AppServerSlashCommandApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.GoalStatusResponse
import com.letta.mobile.data.model.SlashCommandsResponse
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.84: `slash_command.*` from the App Server's device status, and a documented
 * no-goal `goal.get`, instead of the shim-era `admin_rest` denials.
 *
 * Device-status fixtures follow letta-code 0.34.14's `buildDeviceStatus`: `supported_commands` is
 * the frozen `SUPPORTED_REMOTE_COMMANDS`, `mod_commands` appears only when the agent has some.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlashCommandAndGoalAdminHandlersTest {
    private val lenient = Json { ignoreUnknownKeys = true }

    @Test
    fun beforeAnyDeviceStatusTheBuiltinListIsServedAndLabelled() {
        val listing = NativeSlashCommandCatalog().global()
        assertEquals(SlashCommandCatalogOrigin.BuiltinDefault, listing.origin)
        assertEquals(AppServerSlashCommandApi.DEFAULT_SUPPORTED_COMMANDS, listing.commands.map { it.id })
    }

    @Test
    fun deviceStatusReplacesTheBuiltinListAndHidesNonComposerCommands() {
        val catalog = NativeSlashCommandCatalog()
        catalog.ingest(deviceStatus("agent-1", supported = listOf("browser_device_mcp_oauth", "clear", "reflect", "reflection", "compact")))
        val listing = catalog.global()
        assertEquals(SlashCommandCatalogOrigin.DeviceStatus, listing.origin)
        assertEquals(listOf("clear", "reflect", "compact"), listing.commands.map { it.id })
        assertEquals("Compact conversation history", listing.commands.last().description)
    }

    @Test
    fun modCommandsAreScopedToTheAgentWhoseStatusNamedThem() {
        val catalog = NativeSlashCommandCatalog()
        catalog.ingest(deviceStatus("agent-1", supported = listOf("clear"), mods = listOf("deploy" to "Ship it", "clear" to "dup")))
        catalog.ingest(deviceStatus("agent-2", supported = listOf("clear")))

        val first = catalog.forAgent(AgentId("agent-1")).commands
        assertEquals(listOf("clear", "deploy"), first.map { it.id }, "a mod id that shadows a built-in is listed once")
        assertEquals(NativeSlashCommandCatalog.SOURCE_MOD, first.last().source)
        assertEquals(listOf("clear"), catalog.forAgent(AgentId("agent-2")).commands.map { it.id })
        assertEquals(listOf("clear"), catalog.global().commands.map { it.id }, "mods never leak into the global list")
    }

    @Test
    fun aLaterStatusWithoutModCommandsClearsThem() {
        val catalog = NativeSlashCommandCatalog()
        catalog.ingest(deviceStatus("agent-1", supported = listOf("clear"), mods = listOf("deploy" to "Ship it")))
        catalog.ingest(deviceStatus("agent-1", supported = listOf("clear")))
        assertEquals(listOf("clear"), catalog.forAgent(AgentId("agent-1")).commands.map { it.id })
    }

    @Test
    fun listAgentAnswersTheShapeIrohClientsDecode() = runTest {
        val catalog = NativeSlashCommandCatalog()
        catalog.ingest(deviceStatus("agent-1", supported = listOf("compact"), mods = listOf("deploy" to "Ship it")))
        val router = AdminRpcRouter().also { SlashCommandAdminHandlers.register(it, catalog) }

        val result = success(router, "slash_command.list_agent", buildJsonObject { put("agent_id", "agent-1") })

        assertEquals("device_status", result.getValue("catalog_source").jsonPrimitive.content)
        val android = lenient.decodeFromJsonElement(SlashCommandsResponse.serializer(), result).commands
        assertEquals(listOf("/compact", "/deploy"), android.map { it.command })
        assertTrue(android.all { it.installed && it.skillName == null })
        val shared = lenient.decodeFromJsonElement(com.letta.mobile.data.commands.SlashCommandsResponse.serializer(), result).commands
        assertEquals(listOf("compact", "deploy"), shared.map { it.command })
    }

    @Test
    fun theWiredCatalogLearnsFromTheClientsEvents() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<AppServerReceivedFrame>()
        val catalog = NativeSlashCommandCatalog().also { it.start(backgroundScope, events) }
        val router = AdminRpcRouter().also { SlashCommandAdminHandlers.register(it, catalog) }

        events.emit(AppServerProtocol.decodeFrame(deviceStatusJson("agent-1", listOf("doctor"), emptyList())))

        val result = success(router, "slash_command.list", null)
        assertEquals("device_status", result.getValue("catalog_source").jsonPrimitive.content)
        val commands = lenient.decodeFromJsonElement(SlashCommandsResponse.serializer(), result).commands
        assertEquals(listOf("/doctor"), commands.map { it.command })
    }

    @Test
    fun listAgentRequiresAnAgent() = runTest {
        val router = AdminRpcRouter().also { SlashCommandAdminHandlers.register(it, NativeSlashCommandCatalog()) }
        val response = dispatch(router, "slash_command.list_agent", null)
        assertFalse(response.getValue("success").jsonPrimitive.boolean)
    }

    @Test
    fun theRegistryServesSlashCommandsWhenANativeClientIsWired() = runTest {
        val router = AdminRpcRegistry.buildRouter(nativeClient = SilentClient())
        val result = success(router, "slash_command.list", null)
        assertEquals("builtin_default", result.getValue("catalog_source").jsonPrimitive.content)
    }

    @Test
    fun withoutANativeClientSlashCommandsAreUnavailable() = runTest {
        val router = AdminRpcRegistry.buildRouter()
        val error = dispatch(router, "slash_command.list_agent", buildJsonObject { put("agent_id", "agent-1") })
            .getValue("error").jsonPrimitive.content
        assertTrue(error.startsWith("capability_unavailable"), error)
    }

    @Test
    fun goalGetAnswersADocumentedNoGoal() = runTest {
        val router = AdminRpcRouter().also { GoalAdminHandlers.register(it) }
        val result = success(router, "goal.get", buildJsonObject { put("agent_id", "agent-1") })

        assertEquals(false, result.getValue("supported").jsonPrimitive.boolean)
        val decoded = lenient.decodeFromJsonElement(GoalStatusResponse.serializer(), result)
        assertNull(decoded.goal)
        assertEquals("agent-1", decoded.agentId)
        assertEquals("", decoded.conversationId, "no conversation claim, so the chat never mismatches it")
    }

    @Test
    fun goalCommandIsAPermanentTypedDenial() = runTest {
        val router = AdminRpcRouter().also { GoalAdminHandlers.register(it) }
        val error = dispatch(router, "goal.command", buildJsonObject {
            put("agent_id", "agent-1")
            put("command", "/goal ship")
        }).getValue("error").jsonPrimitive.content
        assertTrue(error.startsWith("capability_unavailable") && "no goal mode" in error, error)
    }

    private fun deviceStatus(agent: String, supported: List<String>, mods: List<Pair<String, String>> = emptyList()) =
        AppServerProtocol.decodeFrame(deviceStatusJson(agent, supported, mods)).frame

    private fun deviceStatusJson(agent: String, supported: List<String>, mods: List<Pair<String, String>>): String {
        val supportedJson = supported.joinToString(",") { "\"$it\"" }
        val modsField = if (mods.isEmpty()) {
            ""
        } else {
            ",\"mod_commands\":[" + mods.joinToString(",") { (id, text) -> """{"id":"$id","description":"$text"}""" } + "]"
        }
        return """
            {"type":"update_device_status","runtime":{"agent_id":"$agent","conversation_id":"conv-1"},
             "event_seq":1,"emitted_at":"2026-10-15T00:00:00Z","idempotency_key":"k-$agent",
             "device_status":{"current_available_skills":[],"supported_commands":[$supportedJson]$modsField}}
        """.trimIndent()
    }

    private suspend fun success(router: AdminRpcRouter, method: String, params: JsonObject?): JsonObject {
        val response = dispatch(router, method, params)
        assertEquals(true, response.getValue("success").jsonPrimitive.boolean, "$response")
        return response.getValue("result").jsonObject
    }

    private suspend fun dispatch(router: AdminRpcRouter, method: String, params: JsonObject?): JsonObject =
        Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("t-1", method, params))).jsonObject

    /** A native client the slash handlers must never call: they only read observed frames. */
    private class SilentClient : AppServerClient {
        override val events = MutableSharedFlow<AppServerReceivedFrame>()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = unexpected()
        override suspend fun input(command: AppServerCommand.Input): Unit = unexpected()
        override suspend fun sync(command: AppServerCommand.Sync) = unexpected()
        override suspend fun abort(command: AppServerCommand.AbortMessage) = unexpected()
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = unexpected()
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unexpected()

        private fun unexpected(): Nothing = error("Unexpected App Server operation")
    }
}
