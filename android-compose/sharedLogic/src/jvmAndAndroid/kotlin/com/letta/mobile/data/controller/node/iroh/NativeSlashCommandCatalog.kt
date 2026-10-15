package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.commands.AppServerCommandDescriptions
import com.letta.mobile.data.commands.AppServerSlashCommandApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/** Where a slash-command listing came from. */
enum class SlashCommandCatalogOrigin(val wireName: String) {
    /** An observed `update_device_status` frame (`supported_commands` / `mod_commands`). */
    DeviceStatus("device_status"),

    /** No device status seen yet: the client's copy of letta-code's command list. */
    BuiltinDefault("builtin_default"),
}

/** One command the App Server will run through `execute_command`. */
data class NativeSlashCommand(val id: String, val description: String, val source: String)

/** A listing plus its provenance, for `slash_command.*` (letta-mobile-by4vm.84). */
data class NativeSlashCommandListing(
    val commands: List<NativeSlashCommand>,
    val origin: SlashCommandCatalogOrigin,
)

/**
 * letta-mobile-by4vm.84: the App Server's slash commands, as the controller observes them.
 *
 * ## Wire reality (verified against `@letta-ai/letta-code` 0.34.14, `letta.js`)
 *
 * - There is no `list_commands` request. The command list travels only on
 *   `update_device_status.device_status`: `supported_commands` is the frozen
 *   `SUPPORTED_REMOTE_COMMANDS` constant (process-global, identical in every frame), and
 *   `mod_commands` (`[{id, description, args?}]`, present only when non-empty) lists the commands
 *   the agent's loaded mods registered — so it is agent-scoped.
 * - The server pushes no device status on connect; it arrives with a runtime `sync` or a runtime
 *   state change, which the controller's runtime lane sees for every conversation it serves.
 *
 * Until a frame is observed the listing falls back to
 * [AppServerSlashCommandApi.DEFAULT_SUPPORTED_COMMANDS] — the same list a direct App Server
 * client uses — labelled [SlashCommandCatalogOrigin.BuiltinDefault], so a client is never handed
 * an empty composer list just because the wrapper restarted.
 */
class NativeSlashCommandCatalog {
    @Volatile
    private var supported: List<String>? = null

    private val modCommandsByAgent = ConcurrentHashMap<String, List<NativeSlashCommand>>()

    fun ingest(frame: AppServerInboundFrame) {
        if (frame !is AppServerInboundFrame.UpdateDeviceStatus) return
        val status = frame.deviceStatus
        stringList(status[SUPPORTED_COMMANDS])?.let { supported = it }
        // `mod_commands` is omitted when the agent has none, so absence clears the agent's entry.
        if (!frame.runtime.isAgentFree) {
            modCommandsByAgent[frame.runtime.agentId] = modCommands(status[MOD_COMMANDS])
        }
    }

    fun start(scope: CoroutineScope, events: Flow<AppServerReceivedFrame>): Job =
        scope.launch { events.collect { ingest(it.frame) } }

    /** The process-global listing: letta-code's built-in remote commands. */
    fun global(): NativeSlashCommandListing {
        val observed = supported
        val ids = observed ?: AppServerSlashCommandApi.DEFAULT_SUPPORTED_COMMANDS
        val commands = ids.filterNot { it in HIDDEN }.map { id ->
            NativeSlashCommand(id, AppServerCommandDescriptions.descriptionFor(id), SOURCE_APP_SERVER)
        }
        val origin = if (observed == null) SlashCommandCatalogOrigin.BuiltinDefault else SlashCommandCatalogOrigin.DeviceStatus
        return NativeSlashCommandListing(commands, origin)
    }

    /** [global] plus the commands [agent]'s loaded mods registered, when a frame named it. */
    fun forAgent(agent: AgentId): NativeSlashCommandListing {
        val base = global()
        val mods = modCommandsByAgent[agent.value].orEmpty()
        val builtinIds = base.commands.mapTo(HashSet()) { it.id }
        return base.copy(commands = base.commands + mods.filterNot { it.id in builtinIds })
    }

    private fun stringList(element: JsonElement?): List<String>? =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }

    private fun modCommands(element: JsonElement?): List<NativeSlashCommand> =
        (element as? JsonArray).orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            NativeSlashCommand(id, description, SOURCE_MOD)
        }

    companion object {
        const val SOURCE_APP_SERVER = "app_server"
        const val SOURCE_MOD = "mod"
        private const val SUPPORTED_COMMANDS = "supported_commands"
        private const val MOD_COMMANDS = "mod_commands"

        /**
         * Advertised but not listed: `browser_device_mcp_oauth` is the desktop's MCP OAuth
         * device-flow callback, never typed by a person, and `reflection` is letta-code's alias
         * of `reflect` (`normalized === "reflect" ? "reflection"`), which would list twice.
         */
        val HIDDEN: Set<String> = setOf("browser_device_mcp_oauth", "reflection")
    }
}
