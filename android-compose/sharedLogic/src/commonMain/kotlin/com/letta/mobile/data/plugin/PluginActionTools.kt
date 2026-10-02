package com.letta.mobile.data.plugin

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.ExternalTool
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import com.letta.mobile.data.controller.extras.MutableToolSource
import kotlinx.serialization.json.JsonObject

/** One agent tool a plugin action becomes (plan section 6.3): `<idShort>_<action>`, described with the plugin's name. */
data class PluginToolDefinition(
    val pluginId: String,
    val action: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/** Runs a plugin action for an agent; the host integration (.29) routes it to the plugin's driver and scrubs the answer. */
fun interface PluginActionInvoker {
    suspend fun invoke(definition: PluginToolDefinition, input: JsonObject, caller: ExternalToolCaller): ExternalToolResult
}

/** A plugin action as a host tool: advertised whatever the App Server's capabilities, like the host's canvas tools. */
class PluginActionTool(val definition: PluginToolDefinition, private val invoker: PluginActionInvoker) : HostExternalTool {
    override val name: String get() = definition.name
    override val description: String get() = definition.description
    override val inputSchema: JsonObject get() = definition.inputSchema

    /** Unused for a [HostExternalTool], which is advertised regardless; required by [ExternalTool]. */
    override val capability: Capability = Capability.ImageHydration

    override suspend fun invoke(input: JsonObject, agentId: String?): ExternalToolResult = invoke(input, ExternalToolCaller(agentId))

    override suspend fun invoke(input: JsonObject, caller: ExternalToolCaller): ExternalToolResult = invoker.invoke(definition, input, caller)
}

/**
 * Plugin actions as agent tools (plan section 6.3, mapping only; the wiring into the host is .29).
 * Only actions whose `visibility` holds `agent` become tools, and only for plugins whose tools are
 * advertised (enabled, active, or updating from one of those). The tool source always publishes
 * the FULL set, as every `runtime_start` replaces the runtime's tools (letta-mobile-s416w.27).
 */
object PluginActionTools {
    /** The id of the one [MutableToolSource] that carries every plugin's tools. */
    const val SOURCE_ID: String = "plugins"

    fun definitions(manifest: PluginManifest): List<PluginToolDefinition> = manifest.actions
        .filter { (_, action) -> PluginActionVisibility.AGENT in action.visibility }
        .map { (name, action) ->
            PluginToolDefinition(
                pluginId = manifest.id,
                action = name,
                name = manifest.toolName(name),
                description = "${manifest.name}: ${action.description.orEmpty()}",
                inputSchema = action.input,
            )
        }

    /** The tools of every advertised plugin in [state], in install order. */
    fun toolsFor(state: PluginRegistryState, invoker: PluginActionInvoker): List<ExternalTool> =
        state.advertised.flatMap { definitions(it.manifest) }.map { PluginActionTool(it, invoker) }

    /** Publishes [state]'s whole tool set to [source]; the registry re-advertises when the definitions changed. */
    fun publish(source: MutableToolSource, state: PluginRegistryState, invoker: PluginActionInvoker) {
        source.publish(toolsFor(state, invoker))
    }
}
