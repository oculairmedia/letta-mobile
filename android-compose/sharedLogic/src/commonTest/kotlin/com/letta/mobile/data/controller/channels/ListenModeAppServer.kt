package com.letta.mobile.data.controller.channels

import com.letta.mobile.data.transport.appserver.ScriptedAppServerTransport
import com.letta.mobile.data.transport.appserver.requestId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A wire-level fake of an App Server started as `letta server --listen` (letta-code 0.34.70),
 * copying the checks its socket router makes on a channel command.
 *
 * The real router (letta.js `createMessageRouter`) first checks that the frame is a known
 * protocol command; `isChannelsListCommand` accepts `channels_list` with a string `request_id`
 * and nothing else. A recognised `channel_*` command is then handled only when
 * `runtime.serviceCommandTypes.has(type)`, and that set is filled only by
 * `startChannelGatewayForRuntime`, which `server --listen` never reaches. With the set empty no
 * later branch matches, so the router returns without a reply and without an error frame.
 * Measured against a scratch `server --listen` of the same build: `app_server_info` answered in
 * about 100 ms, `channels_list` / `channel_accounts_list` / `channel_start` never answered.
 *
 * @param channelServiceRunning true models a server whose channel service is running, which
 *   answers from [channels]: each channel has one enabled account, `<channel>-bot`, and every
 *   `channel_start` succeeds.
 */
internal class ListenModeAppServer(
    private val channelServiceRunning: Boolean = false,
    private val channels: List<String> = emptyList(),
) {
    /** Every channel command the router accepted as well-formed and then dropped unanswered. */
    val droppedChannelCommands = mutableListOf<JsonObject>()

    /** `<channel>/<account>` for every `channel_start` the running channel service answered. */
    val startedAccounts = mutableListOf<String>()

    val transport = ScriptedAppServerTransport { command -> answer(command) }

    private fun answer(command: JsonObject): List<String> {
        val type = (command["type"] as? JsonPrimitive)?.content
        return when {
            type == "app_server_info" -> listOf(appServerInfo(command.requestId))
            type !in CHANNEL_SERVICE_COMMAND_TYPES -> emptyList()
            !isWellFormedChannelCommand(command) -> emptyList()
            !channelServiceRunning -> {
                droppedChannelCommands += command
                emptyList()
            }
            type == "channels_list" -> listOf(channelsList(command.requestId))
            type == "channel_accounts_list" -> listOf(accountsList(command.requestId, command.string("channel_id")))
            type == "channel_start" -> listOf(channelStart(command))
            else -> emptyList()
        }
    }

    /** `isChannelsListCommand` and its siblings: a string `request_id` is the only common check. */
    private fun isWellFormedChannelCommand(command: JsonObject): Boolean =
        (command["request_id"] as? JsonPrimitive)?.isString == true

    private fun appServerInfo(requestId: String) =
        """{"type":"app_server_info_response","request_id":"$requestId","success":true,""" +
            """"backend":"local","letta_code_version":"0.34.70","protocol_version":1,""" +
            """"capabilities":{"agent_management":true,"conversation_management":true,""" +
            """"memory_management":true,"runtime_start":true,"split_channels":false}}"""

    private fun channelsList(requestId: String): String {
        val summaries = channels.joinToString(",") { id ->
            """{"channel_id":"$id","display_name":"$id","configured":true,"enabled":true,"running":false}"""
        }
        return """{"type":"channels_list_response","request_id":"$requestId","success":true,"channels":[$summaries]}"""
    }

    private fun accountsList(requestId: String, channelId: String?): String {
        val accounts = if (channelId in channels) {
            """[{"channel_id":"$channelId","account_id":"$channelId-bot","enabled":true,"configured":true}]"""
        } else {
            "[]"
        }
        return """{"type":"channel_accounts_list_response","request_id":"$requestId","success":true,""" +
            """"channel_id":"$channelId","accounts":$accounts}"""
    }

    private fun channelStart(command: JsonObject): String {
        startedAccounts += "${command.string("channel_id")}/${command.string("account_id")}"
        return """{"type":"channel_start_response","request_id":"${command.requestId}","success":true}"""
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    companion object {
        /** letta.js `CHANNEL_SERVICE_COMMAND_TYPES`, verbatim. */
        val CHANNEL_SERVICE_COMMAND_TYPES: Set<String> = setOf(
            "channels_list",
            "channel_accounts_list",
            "channel_account_create",
            "channel_account_update",
            "channel_account_bind",
            "channel_account_unbind",
            "channel_account_delete",
            "channel_account_start",
            "channel_account_stop",
            "channel_get_config",
            "channel_set_config",
            "channel_start",
            "channel_stop",
            "channel_pairings_list",
            "channel_pairing_bind",
            "channel_routes_list",
            "channel_targets_list",
            "channel_target_bind",
            "channel_route_update",
            "channel_route_remove",
        )

        /** The wrapper's production request timeout (`--request-timeout-ms` default). */
        const val WRAPPER_REQUEST_TIMEOUT_MS: Long = 120_000
    }
}
