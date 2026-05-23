package com.letta.mobile.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(LettaIcons.Refresh, stringResource(R.string.action_refresh))
                    }
                },
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
                val statusText = state.data.health?.status
                    ?: if (state.data.stats != null) {
                        stringResource(R.string.screen_vibesync_debug_status_stats_available)
                    } else {
                        state.data.healthError.orEmpty()
                    }
                val uptimeText = (state.data.health?.uptime ?: state.data.stats?.uptime).toVibesyncDisplayText("human")
                val databaseText = (state.data.health?.database ?: state.data.stats?.database).toVibesyncDisplayText()
                CardGroup(title = { Text(stringResource(R.string.screen_vibesync_debug_status_section)) }) {
                    item(headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_status_label)) }, supportingContent = { Text(statusText) })
                    item(headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_uptime_label)) }, supportingContent = { Text(uptimeText) })
                    item(headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_sse_clients_label)) }, supportingContent = { Text(state.data.stats?.sseClients?.toString().orEmpty()) })
                    item(headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_database_label)) }, supportingContent = { Text(databaseText) })
                }
                CardGroup(title = { Text(stringResource(R.string.screen_vibesync_debug_agents_md_section)) }) {
                    val actionsEnabled = !state.data.isRefreshingAgentsMd
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
                    item(
                        onClick = if (actionsEnabled) {
                            { viewModel.refreshAgentsMd(dryRun = true) }
                        } else {
                            null
                        },
                        headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_agents_md_dry_run)) },
                        leadingContent = { Icon(LettaIcons.Play, contentDescription = null) },
                    )
                    item(
                        onClick = if (actionsEnabled) {
                            { viewModel.refreshAgentsMd(dryRun = false) }
                        } else {
                            null
                        },
                        headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_agents_md_apply)) },
                        leadingContent = { Icon(LettaIcons.Check, contentDescription = null) },
                    )
                }
            }
        }
    }
}

private fun JsonElement?.toVibesyncDisplayText(preferredKey: String? = null): String = when (this) {
    null -> ""
    is JsonPrimitive -> contentOrNull ?: toString()
    is JsonObject -> preferredKey
        ?.let { key -> (this[key] as? JsonPrimitive)?.contentOrNull }
        ?: entries.joinToString(separator = ", ") { (key, value) -> "$key=${value.toVibesyncDisplayText()}" }
    is JsonArray -> joinToString(separator = ", ") { it.toVibesyncDisplayText() }
}
