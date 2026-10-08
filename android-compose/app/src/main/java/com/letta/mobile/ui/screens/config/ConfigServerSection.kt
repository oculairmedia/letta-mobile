package com.letta.mobile.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.PreviewLightDark
import ca.oculair.meridian.R
import com.letta.mobile.data.transport.appserver.AppServerProbeResult
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.preview.LettaPreviewFrame
import com.letta.mobile.ui.theme.LettaDimens

// The settings screen's server card: connection mode, server URL and API token.

@Composable
internal fun ServerSection(
    state: ConfigUiState,
    server: ServerSettingsCallbacks,
    localModel: LocalModelCallbacks,
    embeddedModel: EmbeddedModelCallbacks,
) {
    CardGroup(title = {
        ConfigSectionTitle(stringResource(R.string.screen_config_server_section))
    }) {
        item(
            headlineContent = {
                ConnectionModeSelector(
                    mode = state.mode,
                    onModeChange = server.onModeChange,
                )
            },
        )
        item(
            headlineContent = {
                ServerUrlField(
                    mode = state.mode,
                    serverUrl = state.serverUrl,
                    onServerUrlChange = server.onServerUrlChange,
                )
            },
        )
        if (state.mode == ServerMode.LOCAL) {
            item(
                headlineContent = {
                    EmbeddedRuntimeStatusItem(status = state.embeddedRuntimeStatus)
                },
            )
            item(
                headlineContent = {
                    LocalModelSettingsItem(state = state, callbacks = localModel, embeddedModel = embeddedModel)
                },
            )
        }
        if (state.mode != ServerMode.LOCAL) {
            item(
                headlineContent = {
                    ApiTokenField(
                        value = state.apiToken,
                        onValueChange = server.onApiTokenChange,
                    )
                },
            )
        }
        if (state.mode == ServerMode.SELF_HOSTED) {
            item(
                headlineContent = {
                    ConnectionTestRow(
                        state = state.connectionTest,
                        enabled = state.serverUrl.isNotBlank(),
                        onTestConnection = server.onTestConnection,
                    )
                },
            )
        }
    }
}

/** "Test connection" for a self-hosted App Server, with the classified result (F01). */
@Composable
internal fun ConnectionTestRow(
    state: ConnectionTestUiState,
    enabled: Boolean,
    onTestConnection: () -> Unit,
) {
    val running = state == ConnectionTestUiState.Running
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        OutlinedButton(
            onClick = onTestConnection,
            enabled = enabled && !running,
            modifier = Modifier.testTag(CONNECTION_TEST_BUTTON_TAG),
        ) {
            Text(
                stringResource(
                    if (running) R.string.screen_config_test_connection_running else R.string.screen_config_test_connection,
                ),
            )
        }
        connectionTestMessage(state)?.let { (message, isError) ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(CONNECTION_TEST_RESULT_TAG),
            )
        }
    }
}

@Composable
private fun connectionTestMessage(state: ConnectionTestUiState): Pair<String, Boolean>? = when (state) {
    ConnectionTestUiState.Idle, ConnectionTestUiState.Running -> null
    ConnectionTestUiState.NotSupported -> stringResource(R.string.screen_config_test_connection_not_supported) to false
    is ConnectionTestUiState.Finished -> when (val result = state.result) {
        is AppServerProbeResult.Ok -> stringResource(
            R.string.screen_config_test_connection_ok,
            result.identity.lettaCodeVersion,
            result.identity.backend,
            result.identity.protocolVersion,
        ) to false
        is AppServerProbeResult.Authentication ->
            stringResource(R.string.screen_config_test_connection_authentication, result.detail) to true
        is AppServerProbeResult.Incompatible ->
            stringResource(R.string.screen_config_test_connection_incompatible, result.reason) to true
        is AppServerProbeResult.Unavailable ->
            stringResource(R.string.screen_config_test_connection_unavailable, result.detail) to true
    }
}

internal const val CONNECTION_TEST_BUTTON_TAG = "config_test_connection"
internal const val CONNECTION_TEST_RESULT_TAG = "config_test_connection_result"

@Composable
private fun ConnectionModeSelector(
    mode: ServerMode,
    onModeChange: (ServerMode) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val options = listOf(
        ServerMode.CLOUD to R.string.common_cloud,
        ServerMode.SELF_HOSTED to R.string.common_self_hosted,
        ServerMode.LOCAL to R.string.common_local_runtime,
    )
    FormItem(
        label = { Text(stringResource(R.string.screen_config_connection_mode)) },
        description = { Text(stringResource(R.string.screen_config_connection_mode_description)) },
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (option, label) ->
                SegmentedButton(
                    selected = mode == option,
                    onClick = {
                        HapticEffects.segmentTick(haptic, view, enabled = mode != option)
                        onModeChange(option)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
}

@Composable
private fun ServerUrlField(
    mode: ServerMode,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
) {
    val isCloud = mode == ServerMode.CLOUD
    val isLocal = mode == ServerMode.LOCAL
    FormItem(label = { Text(stringResource(R.string.common_server_url)) }) {
        OutlinedTextField(
            value = when {
                isCloud -> ConfigViewModel.DEFAULT_CLOUD_URL
                isLocal -> ConfigViewModel.LOCAL_RUNTIME_URL
                else -> serverUrl
            },
            onValueChange = onServerUrlChange,
            placeholder = { Text(stringResource(R.string.screen_config_server_url_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(LettaIcons.Link, null) },
            readOnly = isCloud || isLocal,
            enabled = !isCloud && !isLocal,
            singleLine = true,
        )
    }
}

@Composable
private fun ApiTokenField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var tokenVisible by remember { mutableStateOf(false) }
    FormItem(label = { Text(stringResource(R.string.common_api_token)) }) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(LettaIcons.Key, null) },
            trailingIcon = {
                TokenVisibilityButton(
                    visible = tokenVisible,
                    onToggle = { tokenVisible = !tokenVisible },
                )
            },
            singleLine = true,
        )
    }
}

@Composable
internal fun TokenVisibilityButton(
    visible: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = stringResource(
                if (visible) R.string.screen_config_hide_token else R.string.screen_config_show_token
            ),
        )
    }
}

@PreviewLightDark
@Composable
private fun ConnectionModeSelectorPreview() {
    LettaPreviewFrame {
        ConnectionModeSelector(
            mode = ServerMode.SELF_HOSTED,
            onModeChange = {},
        )
    }
}
