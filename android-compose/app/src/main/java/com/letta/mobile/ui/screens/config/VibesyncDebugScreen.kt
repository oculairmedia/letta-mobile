package com.letta.mobile.ui.screens.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.LettaRefreshAction
import com.letta.mobile.ui.components.LettaRefreshable
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.header.HeaderAction
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.letta.mobile.ui.theme.LettaDimens

@Composable
fun VibesyncDebugScreen(
    onNavigateBack: () -> Unit,
    viewModel: VibesyncDebugViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LettaPageScaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        header = {
            // letta-mobile-ckmub.3.25: the shared header; touch pulls the page to reload it (ckmub.3.23).
            ShellHeader(
                title = HeaderTitle.Text(stringResource(R.string.screen_vibesync_debug_title)),
                controls = HeaderControls(
                    nav = HeaderNav.Back(onNavigateBack, stringResource(R.string.action_back)),
                    actions = listOf(HeaderAction.Custom { LettaRefreshAction(refreshing = false, onRefresh = viewModel::refresh) }),
                ),
            )
        },
    ) { padding ->
        LettaRefreshable(refreshing = false, onRefresh = viewModel::refresh, modifier = Modifier.padding(padding)) {
            VibesyncDebugContent(uiState, viewModel)
        }
    }
}

@Composable
private fun VibesyncDebugContent(uiState: UiState<VibesyncDebugUiState>, viewModel: VibesyncDebugViewModel) {
    when (val state = uiState) {
        is UiState.Loading -> Text(stringResource(R.string.common_loading), modifier = Modifier.padding(LettaDimens.Space.lg))
        is UiState.Error -> ErrorContent(message = state.message, onRetry = viewModel::refresh)
        is UiState.Success -> Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(LettaDimens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        ) {
            VibesyncStatusCard(state.data)
            VibesyncAgentsMdCard(state.data, viewModel)
        }
    }
}

@Composable
private fun VibesyncStatusCard(data: VibesyncDebugUiState) {
    val statusText = data.health?.status
        ?: if (data.stats != null) {
            stringResource(R.string.screen_vibesync_debug_status_stats_available)
        } else {
            data.healthError.orEmpty()
        }
    val uptimeText = (data.health?.uptime ?: data.stats?.uptime).toVibesyncDisplayText("human")
    val databaseText = (data.health?.database ?: data.stats?.database).toVibesyncDisplayText()
    CardGroup(title = { Text(stringResource(R.string.screen_vibesync_debug_status_section)) }) {
        item(headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_status_label)) }, supportingContent = { Text(statusText) })
        item(headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_uptime_label)) }, supportingContent = { Text(uptimeText) })
        item(headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_sse_clients_label)) }, supportingContent = { Text(data.stats?.sseClients?.toString().orEmpty()) })
        item(headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_database_label)) }, supportingContent = { Text(databaseText) })
    }
}

@Composable
private fun VibesyncAgentsMdCard(data: VibesyncDebugUiState, viewModel: VibesyncDebugViewModel) {
    val actionsEnabled = !data.isRefreshingAgentsMd
    val summary = data.refreshSummary
    val summaryText = summary?.let {
        stringResource(R.string.screen_vibesync_debug_refresh_summary, it.total, it.updated, it.skipped, it.errors)
    }.orEmpty()
    CardGroup(title = { Text(stringResource(R.string.screen_vibesync_debug_agents_md_section)) }) {
        item(
            headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_agents_md_refresh)) },
            supportingContent = { Text(summaryText) },
            leadingContent = { Icon(LettaIcons.Refresh, contentDescription = null) },
        )
        item(
            onClick = { viewModel.refreshAgentsMd(dryRun = true) }.takeIf { actionsEnabled },
            headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_agents_md_dry_run)) },
            leadingContent = { Icon(LettaIcons.Play, contentDescription = null) },
        )
        item(
            onClick = { viewModel.refreshAgentsMd(dryRun = false) }.takeIf { actionsEnabled },
            headlineContent = { Text(stringResource(R.string.screen_vibesync_debug_agents_md_apply)) },
            leadingContent = { Icon(LettaIcons.Check, contentDescription = null) },
        )
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
