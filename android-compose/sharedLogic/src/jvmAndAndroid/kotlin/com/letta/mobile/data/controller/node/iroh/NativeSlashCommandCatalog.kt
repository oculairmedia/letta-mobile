package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.commands.api.SlashCommandQuery
import com.letta.mobile.data.commands.AppServerCommandDescriptions
import com.letta.mobile.data.commands.api.SlashCommandCatalog
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Where a slash-command listing came from. */
enum class SlashCommandCatalogOrigin(val wireName: String) {
    /** An observed `update_device_status` frame (`supported_commands` / `mod_commands`). */
    DeviceStatus("device_status"),

    /** No device status seen yet: the client's copy of letta-code's command list. */
    BuiltinDefault("builtin_default"),
}

/** One command the App Server will run through `execute_command`. */
data class NativeSlashCommand(val id: String, val description: String, val source: String)

/** A listing plus its provenance, for `slash_command.*`. */
data class NativeSlashCommandListing(
    val commands: List<NativeSlashCommand>,
    val origin: SlashCommandCatalogOrigin,
)

/**
 * The App Server's slash commands, as the controller observes them.
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
 * [AppServerCommandDescriptions.DEFAULT_SUPPORTED_COMMANDS] — the same list a direct App Server
 * client uses — labelled [SlashCommandCatalogOrigin.BuiltinDefault], so a client is never handed
 * an empty composer list just because the wrapper restarted.
 */
class NativeSlashCommandCatalog {
    private val catalog = SlashCommandCatalog()

    fun ingest(frame: AppServerInboundFrame) = ingest(frame, null)

    private fun ingest(frame: AppServerInboundFrame, generation: Long?) {
        if (frame !is AppServerInboundFrame.UpdateDeviceStatus) return
        catalog.ingest(
            frame.runtime.agentId.takeUnless { frame.runtime.isAgentFree }?.let(::SlashCommandQuery), frame.deviceStatus, generation,
        )
    }

    fun start(scope: CoroutineScope, events: Flow<AppServerReceivedFrame>): Job =
        scope.launch { events.collect { ingest(it.frame, it.connectionGeneration) } }

    /** The App Server's global built-in catalog. */
    fun global(): NativeSlashCommandListing = listing(null)

    /** The global catalog plus only this agent's mod commands. */
    fun forAgent(agent: AgentId): NativeSlashCommandListing = listing(agent.value)

    private fun listing(agent: String?): NativeSlashCommandListing {
        val (commands, observed) = catalog.listing(agent?.let(::SlashCommandQuery))
        return NativeSlashCommandListing(
            commands.map { NativeSlashCommand(it.command, it.description, it.source) },
            if (observed) SlashCommandCatalogOrigin.DeviceStatus else SlashCommandCatalogOrigin.BuiltinDefault,
        )
    }

    companion object {
        const val SOURCE_APP_SERVER = "app_server"
        const val SOURCE_MOD = "mod"
        val HIDDEN: Set<String> = SlashCommandCatalog.HIDDEN
    }
}
