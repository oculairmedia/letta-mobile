package com.letta.mobile.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.LettaConfig

/**
 * Canonical Backend configuration surface matching the Desktop pattern.
 * Supports Cloud, Self-hosted (Iroh and HTTP), and Local runtimes.
 */
@Composable
fun BackendSettingsCard(
    config: LettaConfig,
    onConfigSaved: (LettaConfig) -> Unit,
    onTokenCleared: () -> Unit = {},
    onIrohIdentityReset: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var serverUrl by remember(config.serverUrl) { mutableStateOf(config.serverUrl) }
    var mode by remember(config.mode) { mutableStateOf(config.mode) }
    var tokenInput by remember { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Backend",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Server URL Field
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Server URL",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    placeholder = {
                        Text(
                            text = when (mode) {
                                LettaConfig.Mode.SELF_HOSTED -> "iroh://<node_id>@<ip>:<port> or https://..."
                                LettaConfig.Mode.CLOUD -> "https://api.letta.com"
                                LettaConfig.Mode.LOCAL -> "http://127.0.0.1:8283"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                )
            }

            // Mode Selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Mode",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LettaConfig.Mode.entries.forEach { option ->
                        val active = mode == option
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (active) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { mode = option },
                        ) {
                            Text(
                                text = when (option) {
                                    LettaConfig.Mode.CLOUD -> "Cloud"
                                    LettaConfig.Mode.SELF_HOSTED -> "Self-hosted"
                                    LettaConfig.Mode.LOCAL -> "Local runtime"
                                },
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                ),
                                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Access Token Field
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Access token",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    placeholder = {
                        Text(
                            text = if (config.accessToken == null) "Optional" else "Saved token hidden",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                )
            }

            // Action Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        val normalizedUrl = serverUrl.trim()
                        val newConfig = config.copy(
                            mode = mode,
                            serverUrl = normalizedUrl,
                            accessToken = tokenInput.trim().takeIf { it.isNotBlank() } ?: config.accessToken,
                        )
                        onConfigSaved(newConfig)
                        tokenInput = ""
                    },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Save")
                }

                if (config.accessToken != null) {
                    OutlinedButton(
                        onClick = {
                            tokenInput = ""
                            onTokenCleared()
                        },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Clear token")
                    }
                }

                if (onIrohIdentityReset != null) {
                    OutlinedButton(
                        onClick = onIrohIdentityReset,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Reset Iroh identity")
                    }
                }
            }
        }
    }
}
