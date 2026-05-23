package com.letta.mobile.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibesyncDebugScreen(
    onNavigateBack: () -> Unit,
    viewModel: VibesyncDebugViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_vibesync_debug_title)) },
                navigationIcon = { TextButton(onClick = onNavigateBack) { Text(stringResource(R.string.action_back)) } },
                actions = { TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.action_refresh)) } },
                colors = LettaTopBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        when (val state = uiState) {
            is UiState.Loading -> Text(stringResource(R.string.common_loading), modifier = Modifier.padding(padding).padding(16.dp))
            is UiState.Error -> ErrorContent(message = state.message, onRetry = viewModel::refresh, modifier = Modifier.padding(padding))
            is UiState.Success -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CardGroup(title = { Text(stringResource(R.string.screen_vibesync_debug_status_section)) }) {
                    item(headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_status_label)) }, supportingContent = { Text(state.data.health?.status.orEmpty()) })
                    item(headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_uptime_label)) }, supportingContent = { Text(state.data.health?.uptime?.humanValue().orEmpty()) })
                    item(headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_sse_clients_label)) }, supportingContent = { Text(state.data.stats?.sseClients?.toString().orEmpty()) })
                    item(headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_database_label)) }, supportingContent = { Text(state.data.stats?.database?.toString().orEmpty()) })
                }
                CardGroup(title = { Text(stringResource(R.string.screen_vibesync_debug_agents_md_section)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_agents_md_refresh)) },
                        supportingContent = {
                            val summary = state.data.refreshSummary
                            if (summary != null) {
                                Text(
                                    stringResource(
                                        R.string.screen_vibesync_debug_refresh_summary,
                                        summary.total,
                                        summary.updated,
                                        summary.skipped,
                                        summary.errors,
                                    )
                                )
                            } else {
                                Text("")
                            }
                        },
                        leadingContent = { Icon(LettaIcons.Refresh, contentDescription = null) },
                    )
                }
                Button(enabled = !state.data.isRefreshingAgentsMd, onClick = { viewModel.refreshAgentsMd(dryRun = true) }) {
                    Text(stringResource(R.string.screen_vibesync_debug_agents_md_dry_run))
                }
                Button(enabled = !state.data.isRefreshingAgentsMd, onClick = { viewModel.refreshAgentsMd(dryRun = false) }) {
                    Text(stringResource(R.string.screen_vibesync_debug_agents_md_apply))
                }
            }
        }
    }
}

private fun JsonElement.humanValue(): String = ((this as? JsonObject)?.get("human") as? JsonPrimitive)?.content.orEmpty()
