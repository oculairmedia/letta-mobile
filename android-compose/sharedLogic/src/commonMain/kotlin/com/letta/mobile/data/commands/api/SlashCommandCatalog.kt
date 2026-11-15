package com.letta.mobile.data.commands.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Catalog projection shared by direct clients and the wrapper's listing routes. */
internal class SlashCommandCatalog {
    private val state = MutableStateFlow(SlashCommandCatalogState())

    fun ingest(agentId: String?, status: JsonObject, generation: Long? = null) {
        state.update { previous ->
            if (isStale(previous, generation)) return@update previous
            val current = if (generation != null && generation != previous.generation) SlashCommandCatalogState(generation) else previous
            current.copy(
                supported = strings(status["supported_commands"]) ?: current.supported,
                mods = if (agentId == null) current.mods else current.mods + (agentId to mods(status["mod_commands"])),
            )
        }
    }

    private fun isStale(previous: SlashCommandCatalogState, generation: Long?): Boolean {
        val known = previous.generation ?: return false
        val incoming = generation ?: return false
        return incoming < known
    }

    /** Rows and whether the host has supplied its built-in catalog, from the same snapshot. */
    fun listing(agentId: String?): Pair<List<AgentSlashCommand>, Boolean> {
        val snapshot = state.value
        val builtin = (snapshot.supported ?: AppServerCommandDescriptions.DEFAULT_SUPPORTED_COMMANDS)
            .filterNot { it in HIDDEN }.map { id ->
                AgentSlashCommand(
                    rawCommand = "/$id", name = id, description = AppServerCommandDescriptions.descriptionFor(id),
                    source = "app_server", installed = true,
                )
            }
        val ids = builtin.mapTo(mutableSetOf()) { it.command }
        return (builtin + snapshot.mods[agentId].orEmpty().filterNot { it.command in ids }) to (snapshot.supported != null)
    }

    private fun strings(value: JsonElement?): List<String>? = (value as? JsonArray)?.mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }

    private fun mods(value: JsonElement?): List<AgentSlashCommand> = (value as? JsonArray).orEmpty().mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        AgentSlashCommand(
            rawCommand = "/$id", name = id, description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            source = "mod", installed = true,
        )
    }

    companion object {
        val HIDDEN: Set<String> = setOf("browser_device_mcp_oauth", "reflection")
    }
}

private data class SlashCommandCatalogState(
    val generation: Long? = null,
    val supported: List<String>? = null,
    val mods: Map<String, List<AgentSlashCommand>> = emptyMap(),
)
