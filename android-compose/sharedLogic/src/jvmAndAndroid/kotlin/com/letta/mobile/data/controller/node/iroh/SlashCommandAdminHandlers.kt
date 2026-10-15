package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-by4vm.84: `slash_command.list` / `slash_command.list_agent` from the App Server's own
 * command list ([NativeSlashCommandCatalog], fed by `update_device_status`), replacing the
 * shim-era `admin_rest` denial that left the phone's composer without server commands.
 *
 * Wire shape is the one Iroh clients already decode (`SlashCommandsResponse`): `commands` rows of
 * `{name, command:"/<id>", description, source, installed}`, plus `catalog_source` provenance.
 * Rows are commands for letta-code's `execute_command`, not skills, so `skill_name` is never set.
 * Without a catalog (no native App Server client) both methods answer `capability_unavailable`.
 */
object SlashCommandAdminHandlers {
    fun register(router: AdminRpcRouter, catalog: NativeSlashCommandCatalog?) {
        if (catalog == null) {
            CapabilityUnavailable.register(router, METHODS, service = "App Server command catalog")
            return
        }
        router.register(LIST) { envelope(catalog.global()) }
        router.register(LIST_AGENT) { params ->
            envelope(catalog.forAgent(AgentId(params.requireParam(AdminParamKey("agent_id")))))
        }
    }

    internal fun envelope(listing: NativeSlashCommandListing): JsonObject = buildJsonObject {
        put(
            "commands",
            buildJsonArray {
                listing.commands.forEach { command ->
                    add(
                        buildJsonObject {
                            put("name", command.id)
                            put("command", "/${command.id}")
                            put("description", command.description)
                            put("source", command.source)
                            put("installed", true)
                        },
                    )
                }
            },
        )
        put("catalog_source", listing.origin.wireName)
    }

    private const val LIST = "slash_command.list"
    private const val LIST_AGENT = "slash_command.list_agent"

    val METHODS: Set<String> = setOf(LIST, LIST_AGENT)
}
