package com.letta.mobile.ui.screens.config

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.health.ServerHealthState
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import kotlinx.coroutines.launch

/**
 * letta-mobile-cdlk: Bottom-sheet surface that replaces the full-screen
 * `ConfigListRoute` navigation from the active-backend pill.
 *
 * Contract:
 *   - Tap a row: switch active backend, dismiss the sheet.
 *   - Tap the edit pencil: dismiss + navigate to [ConfigScreen] for the
 *     selected backend. The current MVP makes the row active first because
 *     [ConfigViewModel] always loads the active config; once the screen
 *     supports a configId arg, the side-effect can be dropped.
 *   - Long-press a row: open an inline delete-confirm dialog.
 *   - Tap "+ Add server": dismiss + navigate to a fresh
 *     [ConfigScreen] in create-new mode (`ConfigRoute(createNew = true)`).
 *
 * The full-screen `ConfigListScreen` remains reachable from Settings →
 * Configs for power users who want a list view with finer-grained controls.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BackendSwitcherSheet(
    onDismiss: () -> Unit,
    onNavigateToAddNewServer: () -> Unit,
    onNavigateToEditServer: () -> Unit,
    viewModel: ConfigListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    // letta-mobile-qmxn: re-probe backend health each time the sheet opens
    // so the dot reflects current state (e.g. shim came back up between
    // visits) rather than the last cached probe result.
    LaunchedEffect(Unit) { viewModel.refreshHealth() }

    fun dismissAndThen(after: () -> Unit) {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            onDismiss()
            after()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_config_list_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            when (val state = uiState) {
                is UiState.Loading -> Text(
                    text = stringResource(R.string.action_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is UiState.Error -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is UiState.Success -> {
                    if (state.data.configs.isEmpty()) {
                        Text(
                            text = stringResource(R.string.screen_config_list_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.data.configs.forEach { config ->
                            BackendSwitcherRow(
                                config = config,
                                onSelect = {
                                    viewModel.setActiveConfig(config.id)
                                    dismissAndThen {}
                                },
                                onEdit = {
                                    viewModel.setActiveConfig(config.id)
                                    dismissAndThen(onNavigateToEditServer)
                                },
                                onLongPress = { pendingDeleteId = config.id },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            FilledTonalButton(
                onClick = { dismissAndThen(onNavigateToAddNewServer) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = LettaIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(LettaIconSizing.Inline),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.screen_config_list_add_server))
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    ConfirmDialog(
        show = pendingDeleteId != null,
        title = stringResource(R.string.screen_config_dialog_delete_title),
        message = stringResource(R.string.screen_config_dialog_delete_confirm),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = {
            pendingDeleteId?.let(viewModel::deleteConfig)
            pendingDeleteId = null
        },
        onDismiss = { pendingDeleteId = null },
        destructive = true,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackendSwitcherRow(
    config: ServerConfig,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onLongPress: () -> Unit,
) {
    val isOffline = config.health == ServerHealthState.OFFLINE
    // letta-mobile-qmxn: tap-on-dead is silent-but-visible. Bumping
    // `refusalTrigger` re-fires the shake+flash animation in HealthRowShell
    // without switching active backends. Keyed by config.id so the counter
    // doesn't migrate to a different row when the list is reordered or the
    // adjacent row is deleted (rows here are not emitted via a keyed list,
    // so this is the only thing pinning the per-row state to its identity).
    var refusalTrigger by remember(config.id) { mutableIntStateOf(0) }

    val baseContainerColor = if (config.isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (config.isActive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    HealthRowShell(
        baseContainerColor = baseContainerColor,
        contentColor = contentColor,
        refusalTrigger = refusalTrigger,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = {
                    if (isOffline) refusalTrigger++ else onSelect()
                },
                onLongClick = onLongPress,
                onLongClickLabel = stringResource(R.string.action_delete),
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HealthDot(
                health = config.health,
                modifier = Modifier
                    .align(Alignment.Top)
                    .padding(top = 7.dp, end = 8.dp),
            )
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (config.mode) {
                        ServerMode.CLOUD -> LettaIcons.Cloud
                        ServerMode.SELF_HOSTED -> LettaIcons.Storage
                        ServerMode.LOCAL -> LettaIcons.Psychology
                    },
                    contentDescription = null,
                    modifier = Modifier.size(LettaIconSizing.Inline),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = when (config.mode) {
                        ServerMode.CLOUD -> stringResource(R.string.common_letta_cloud)
                        ServerMode.SELF_HOSTED -> config.url.removePrefix("https://").removePrefix("http://")
                        ServerMode.LOCAL -> stringResource(R.string.common_local_kotlin_runtime)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (config.isActive) {
                    Text(
                        text = stringResource(R.string.common_active),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = LettaIcons.Edit,
                    contentDescription = stringResource(R.string.action_edit),
                    modifier = Modifier.size(LettaIconSizing.Inline),
                )
            }
        }
    }
}
