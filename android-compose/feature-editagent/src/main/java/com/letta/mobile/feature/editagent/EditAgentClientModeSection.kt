package com.letta.mobile.feature.editagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.letta.mobile.feature.editagent.R
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.bot.connection.ClientModeConnectionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun EditAgentClientModeSection(
    state: EditAgentUiState,
    onClientModeEnabledChange: (Boolean) -> Unit,
    onClientModeBaseUrlChange: (String) -> Unit,
    onClientModeApiKeyChange: (String) -> Unit,
    onTestClientModeConnection: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardGroup(title = { Text(stringResource(R.string.screen_settings_client_mode_section)) }) {
            item(
                headlineContent = {
                    FormItem(
                        label = { Text(stringResource(R.string.screen_settings_client_mode_enable)) },
                        description = {
                            Text(stringResource(R.string.screen_settings_client_mode_enable_description))
                        },
                        tail = {
                            Switch(
                                checked = state.clientModeEnabled,
                                onCheckedChange = onClientModeEnabledChange,
                            )
                        },
                    )
                },
            )
        }

        if (state.clientModeEnabled) {
            CardGroup {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.clientModeBaseUrl,
                            onValueChange = onClientModeBaseUrlChange,
                            label = { Text(stringResource(R.string.screen_settings_client_mode_server_url)) },
                            placeholder = {
                                Text(stringResource(R.string.screen_settings_client_mode_server_url_placeholder))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.clientModeApiKey,
                            onValueChange = onClientModeApiKeyChange,
                            label = { Text(stringResource(R.string.screen_settings_client_mode_api_key)) },
                            placeholder = {
                                Text(stringResource(R.string.screen_settings_client_mode_api_key_placeholder))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.screen_settings_client_mode_api_key_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                item(
                    headlineContent = {
                        FormItem(
                            label = { Text(stringResource(R.string.screen_settings_client_mode_test_connection)) },
                            description = {
                                val statusText = clientModeConnectionStatusText(state.clientModeConnectionState)
                                if (statusText != null) {
                                    Text(
                                        text = statusText,
                                        color = clientModeConnectionStatusColor(state.clientModeConnectionState),
                                    )
                                } else {
                                    Text(stringResource(R.string.screen_settings_client_mode_test_connection_helper))
                                }
                            },
                            tail = {
                                OutlinedButton(
                                    onClick = onTestClientModeConnection,
                                    enabled = state.clientModeConnectionState !is ClientModeConnectionState.Testing &&
                                        state.clientModeBaseUrl.isNotBlank(),
                                ) {
                                    if (state.clientModeConnectionState is ClientModeConnectionState.Testing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Text(stringResource(R.string.screen_settings_client_mode_test_connection_action))
                                    }
                                }
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun clientModeConnectionStatusText(state: ClientModeConnectionState): String? {
    val formatter = remember {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
    }

    return when (state) {
        ClientModeConnectionState.Idle -> null
        ClientModeConnectionState.Testing -> stringResource(R.string.screen_settings_client_mode_testing)
        is ClientModeConnectionState.Success -> stringResource(
            R.string.screen_settings_client_mode_success,
            formatter.format(Instant.ofEpochMilli(state.testedAtMillis).atZone(ZoneId.systemDefault())),
        )
        is ClientModeConnectionState.Failure -> stringResource(
            R.string.screen_settings_client_mode_failure,
            state.message,
            formatter.format(Instant.ofEpochMilli(state.testedAtMillis).atZone(ZoneId.systemDefault())),
        )
    }
}

@Composable
private fun clientModeConnectionStatusColor(state: ClientModeConnectionState): Color = when (state) {
    ClientModeConnectionState.Idle,
    ClientModeConnectionState.Testing,
    -> MaterialTheme.colorScheme.onSurfaceVariant
    is ClientModeConnectionState.Success -> MaterialTheme.colorScheme.tertiary
    is ClientModeConnectionState.Failure -> MaterialTheme.colorScheme.error
}
