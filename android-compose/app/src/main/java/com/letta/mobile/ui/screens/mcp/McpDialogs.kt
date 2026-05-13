package com.letta.mobile.ui.screens.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.effectiveArgs
import com.letta.mobile.data.model.effectiveAuthHeader
import com.letta.mobile.data.model.effectiveAuthToken
import com.letta.mobile.data.model.effectiveCommand
import com.letta.mobile.data.model.effectiveCustomHeaders
import com.letta.mobile.data.model.effectiveEnv
import com.letta.mobile.data.model.effectiveServerType
import com.letta.mobile.data.model.effectiveServerUrl
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.MultiFieldInputDialog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

private const val MCP_TYPE_STDIO = "stdio"
private const val MCP_TYPE_SSE = "sse"
private const val MCP_TYPE_STREAMABLE_HTTP = "streamable_http"

@androidx.compose.runtime.Immutable
internal data class McpServerFormState(
    val serverName: String = "",
    val transportType: String = MCP_TYPE_STREAMABLE_HTTP,
    val serverUrl: String = "",
    val command: String = "",
    val argsText: String = "",
    val authHeader: String = "",
    val authToken: String = "",
    val customHeadersText: String = "",
    val envText: String = "",
    val rawConfigText: String = "",
)

@Composable
internal fun ServerFormDialog(
    initialServer: McpServer?,
    initialFormStateOverride: McpServerFormState? = null,
    onDismiss: () -> Unit,
    onCreate: (McpServerCreateParams) -> Unit,
    onUpdate: (String, McpServerUpdateParams) -> Unit,
) {
    var formState by remember(initialServer, initialFormStateOverride) {
        mutableStateOf(initialFormStateOverride ?: initialFormState(initialServer))
    }
    val validationMessage = validateForm(formState)

    MultiFieldInputDialog(
        show = true,
        title = if (initialServer == null) {
            stringResource(R.string.screen_mcp_dialog_add_title)
        } else {
            stringResource(R.string.screen_mcp_dialog_edit_title)
        },
        confirmText = if (initialServer == null) {
            stringResource(R.string.action_add)
        } else {
            stringResource(R.string.action_save)
        },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = validationMessage == null,
        onConfirm = {
            val config = buildConfig(formState).getOrNull() ?: return@MultiFieldInputDialog
            if (initialServer == null) {
                onCreate(
                    McpServerCreateParams(
                        serverName = formState.serverName.trim(),
                        config = config,
                    )
                )
            } else {
                onUpdate(
                    initialServer.id,
                    McpServerUpdateParams(
                        serverName = formState.serverName.trim(),
                        config = config,
                    )
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup {
                item(
                    headlineContent = {
                        FormItem(label = { Text(stringResource(R.string.screen_mcp_server_name_label)) }) {
                            OutlinedTextField(
                                value = formState.serverName,
                                onValueChange = { formState = formState.copy(serverName = it) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                )
                item(
                    headlineContent = {
                        FormItem(label = { Text(stringResource(R.string.screen_mcp_transport_type)) }) {
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = formState.transportType == MCP_TYPE_STREAMABLE_HTTP,
                                    onClick = { formState = formState.copy(transportType = MCP_TYPE_STREAMABLE_HTTP) },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                                ) {
                                    Text(stringResource(R.string.screen_mcp_transport_streamable_http))
                                }
                                SegmentedButton(
                                    selected = formState.transportType == MCP_TYPE_SSE,
                                    onClick = { formState = formState.copy(transportType = MCP_TYPE_SSE) },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                                ) {
                                    Text(stringResource(R.string.screen_mcp_transport_sse))
                                }
                                SegmentedButton(
                                    selected = formState.transportType == MCP_TYPE_STDIO,
                                    onClick = { formState = formState.copy(transportType = MCP_TYPE_STDIO) },
                                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                                ) {
                                    Text(stringResource(R.string.screen_mcp_transport_stdio))
                                }
                            }
                        }
                    },
                )
            }

            if (formState.transportType == MCP_TYPE_STDIO) {
                CardGroup {
                    item(
                        headlineContent = {
                            FormItem(label = { Text(stringResource(R.string.screen_mcp_command_label)) }) {
                                OutlinedTextField(
                                    value = formState.command,
                                    onValueChange = { formState = formState.copy(command = it) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            FormItem(label = { Text(stringResource(R.string.screen_mcp_args_label)) }) {
                                OutlinedTextField(
                                    value = formState.argsText,
                                    onValueChange = { formState = formState.copy(argsText = it) },
                                    placeholder = { Text(stringResource(R.string.screen_mcp_args_placeholder)) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            FormItem(label = { Text(stringResource(R.string.screen_mcp_env_label)) }) {
                                OutlinedTextField(
                                    value = formState.envText,
                                    onValueChange = { formState = formState.copy(envText = it) },
                                    placeholder = { Text(stringResource(R.string.screen_mcp_key_value_placeholder)) },
                                    minLines = 3,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                }
            } else {
                CardGroup {
                    item(
                        headlineContent = {
                            FormItem(label = { Text(stringResource(R.string.common_server_url)) }) {
                                OutlinedTextField(
                                    value = formState.serverUrl,
                                    onValueChange = { formState = formState.copy(serverUrl = it) },
                                    placeholder = { Text(stringResource(R.string.screen_mcp_url_placeholder)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            FormItem(label = { Text(stringResource(R.string.screen_mcp_auth_header_label)) }) {
                                OutlinedTextField(
                                    value = formState.authHeader,
                                    onValueChange = { formState = formState.copy(authHeader = it) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            FormItem(label = { Text(stringResource(R.string.screen_mcp_auth_token_label)) }) {
                                OutlinedTextField(
                                    value = formState.authToken,
                                    onValueChange = { formState = formState.copy(authToken = it) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            FormItem(label = { Text(stringResource(R.string.screen_mcp_custom_headers_label)) }) {
                                OutlinedTextField(
                                    value = formState.customHeadersText,
                                    onValueChange = { formState = formState.copy(customHeadersText = it) },
                                    placeholder = { Text(stringResource(R.string.screen_mcp_key_value_placeholder)) },
                                    minLines = 3,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                }
            }

            CardGroup {
                item(
                    headlineContent = {
                        FormItem(label = { Text(stringResource(R.string.screen_mcp_raw_config_label)) }) {
                            OutlinedTextField(
                                value = formState.rawConfigText,
                                onValueChange = { formState = formState.copy(rawConfigText = it) },
                                placeholder = { Text(stringResource(R.string.screen_mcp_raw_config_placeholder)) },
                                minLines = 4,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                )
            }

            validationMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

internal fun initialFormState(server: McpServer?): McpServerFormState {
    if (server == null) return McpServerFormState()

    val envText = server.effectiveEnv()?.entries
        ?.joinToString("\n") { (key, value) -> "$key=$value" }
        .orEmpty()
    val customHeadersText = server.effectiveCustomHeaders()?.entries
        ?.joinToString("\n") { (key, value) -> "$key=$value" }
        .orEmpty()

    return McpServerFormState(
        serverName = server.serverName,
        transportType = server.effectiveServerType() ?: if (server.effectiveCommand() != null) MCP_TYPE_STDIO else MCP_TYPE_STREAMABLE_HTTP,
        serverUrl = server.effectiveServerUrl().orEmpty(),
        command = server.effectiveCommand().orEmpty(),
        argsText = server.effectiveArgs().joinToString(" "),
        authHeader = server.effectiveAuthHeader().orEmpty(),
        authToken = server.effectiveAuthToken().orEmpty(),
        customHeadersText = customHeadersText,
        envText = envText,
        rawConfigText = "",
    )
}

internal fun validateForm(state: McpServerFormState): String? {
    if (state.serverName.isBlank()) {
        return "Server name is required"
    }
    if (state.transportType == MCP_TYPE_STDIO && state.command.isBlank() && state.rawConfigText.isBlank()) {
        return "Command is required for stdio servers"
    }
    if (state.transportType != MCP_TYPE_STDIO && state.serverUrl.isBlank() && state.rawConfigText.isBlank()) {
        return "Server URL is required for HTTP-based servers"
    }
    if (state.transportType != MCP_TYPE_STDIO && state.serverUrl.isNotBlank()) {
        val url = state.serverUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Server URL must start with http:// or https://"
        }
    }
    if (state.rawConfigText.isNotBlank() && buildConfig(state).isFailure) {
        return "Raw config must be valid JSON"
    }
    if (parseKeyValueObject(state.customHeadersText).isFailure) {
        return "Custom headers must use one KEY=value pair per line"
    }
    if (parseKeyValueObject(state.envText).isFailure) {
        return "Environment variables must use one KEY=value pair per line"
    }
    return null
}

internal fun buildConfig(state: McpServerFormState): Result<JsonObject> {
    if (state.rawConfigText.isNotBlank()) {
        return runCatching {
            Json.parseToJsonElement(state.rawConfigText).jsonObject
        }
    }

    return runCatching {
        val customHeaders = parseKeyValueObject(state.customHeadersText).getOrThrow()
        val env = parseKeyValueObject(state.envText).getOrThrow()
        val args = state.argsText.trim()
            .split(Regex("[\\s,]+"))
            .filter { it.isNotBlank() }

        buildJsonObject {
            put("mcp_server_type", JsonPrimitive(state.transportType))
            if (state.transportType == MCP_TYPE_STDIO) {
                put("command", JsonPrimitive(state.command.trim()))
                put("args", buildJsonArray { args.forEach { add(JsonPrimitive(it)) } })
                env?.let { put("env", it) }
            } else {
                put("server_url", JsonPrimitive(state.serverUrl.trim()))
                state.authHeader.trim().takeIf { it.isNotBlank() }?.let { put("auth_header", JsonPrimitive(it)) }
                state.authToken.trim().takeIf { it.isNotBlank() }?.let { put("auth_token", JsonPrimitive(it)) }
                customHeaders?.let { put("custom_headers", it) }
            }
        }
    }
}

internal fun parseKeyValueObject(text: String): Result<JsonObject?> {
    if (text.isBlank()) return Result.success(null)

    return runCatching {
        buildJsonObject {
            text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val separatorIndex = line.indexOf('=')
                    require(separatorIndex > 0 && separatorIndex < line.length - 1)
                    val key = line.substring(0, separatorIndex).trim()
                    val value = line.substring(separatorIndex + 1).trim()
                    require(key.isNotBlank())
                    put(key, JsonPrimitive(value))
                }
        }
    }
}
