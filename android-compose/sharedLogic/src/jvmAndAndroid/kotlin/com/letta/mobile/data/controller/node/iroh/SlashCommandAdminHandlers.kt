package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `slash_command.list` / `slash_command.list_agent` from the App Server's own
 * command list ([NativeSlashCommandCatalog], fed by `update_device_status`), replacing the
 * shim-era `admin_rest` denial that left the phone's composer without server commands.
 *
 * Wire shape is the one Iroh clients already decode (`SlashCommandsResponse`): `commands` rows of
 * `{name, command:"/<id>", description, source, installed}`, plus `catalog_source` provenance.
 * Rows are commands for letta-code's `execute_command`, not skills, so `skill_name` is never set.
 * Without a catalog (no native App Server client) both methods answer `capability_unavailable`.
 */
object SlashCommandAdminHandlers {
    fun register(router: AdminRpcRouter, catalog: NativeSlashCommandCatalog?, nativeClient: AppServerClient? = null) {
        registerExecution(router, nativeClient)
        if (catalog == null) {
            CapabilityUnavailable.register(router, setOf(LIST, LIST_AGENT), service = "App Server command catalog")
            return
        }
        router.register(LIST) { envelope(catalog.global()) }
        router.register(LIST_AGENT) { params ->
            envelope(catalog.forAgent(AgentId(params.requireParam(AdminParamKey("agent_id")))))
        }
    }

    private fun registerExecution(router: AdminRpcRouter, nativeClient: AppServerClient?) {
        router.registerScoped(EXECUTE) { params, context ->
            val runtime = AppServerRuntimeScope(
                agentId = params.requireParam(AdminParamKey("agent_id")),
                conversationId = params.requireParam(AdminParamKey("conversation_id")),
            )
            if (!context.canAccessConversation(runtime.conversationId)) adminError("forbidden: conversation not in scope")
            val commandId = params.requireParam(AdminParamKey("command_id"))
            val client = nativeClient ?: adminError("capability_unavailable: slash commands require the native App Server client")
            val response = client.executeCommand(
                AppServerCommand.ExecuteCommand(
                    requestId = NativeAdmin.requestId(),
                    commandId = commandId,
                    args = param(params, AdminParamKey("args")),
                    runtime = runtime,
                ),
            )
            if (!response.success) adminError(response.error ?: "execute_command failed")
            buildJsonObject {
                put("output", response.output?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                put("result", response.result ?: JsonNull)
            }
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

    private const val EXECUTE = "slash_command.execute"

    val METHODS: Set<String> = setOf(LIST, LIST_AGENT, EXECUTE)
}
