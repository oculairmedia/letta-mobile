package com.letta.mobile.ui.screens.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (configId: String?) -> Unit,
    viewModel: BotSettingsViewModel = hiltViewModel(),
) {
    val configs by viewModel.configs.collectAsStateWithLifecycle()
    val isBotRunning by viewModel.isBotRunning.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            TopAppBar(
                title = { Text("Bot Settings") },
                colors = LettaTopBarDefaults.topAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNavigateToEdit(null) }) {
                Icon(LettaIcons.Add, contentDescription = "Add bot config")
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Service controls
            CardGroup(title = { Text("Service") }) {
                item(
                    headlineContent = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = if (isBotRunning) "Bot is running" else "Bot is stopped",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (isBotRunning) {
                                OutlinedButton(
                                    onClick = { viewModel.stopBot() },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Text("Stop")
                                }
                            } else {
                                Button(onClick = { viewModel.startBot() }) {
                                    Text("Start")
                                }
                            }
                        }
                    },
                )
            }

            // Config list
            if (configs.isEmpty()) {
                EmptyState(
                    icon = LettaIcons.Agent,
                    message = "No bot configurations yet.\nTap + to create one.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                )
            } else {
                CardGroup(title = { Text("Configurations") }) {
                    configs.forEach { config ->
                        item(
                            onClick = { onNavigateToEdit(config.id) },
                            headlineContent = {
                                Text(config.displayName.ifBlank { "Unnamed Bot" })
                            },
                            supportingContent = {
                                // letta-mobile-w2hx.4: was `${mode} · ${agentId.take(12)}…`
                                // Bot is now a transport, not an agent binder — show
                                // mode + transport + a heartbeat indicator instead.
                                val heartbeatLabel = if (config.heartbeatEnabled &&
                                    !config.heartbeatAgentId.isNullOrBlank()
                                ) {
                                    " \u00B7 \u23F1 ${config.heartbeatAgentId!!.take(8)}\u2026"
                                } else ""
                                Text(
                                    "${config.mode.name} \u00B7 ${config.transport.name}$heartbeatLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    LettaIcons.Agent,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = config.enabled,
                                    onCheckedChange = { viewModel.toggleConfigEnabled(config) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
