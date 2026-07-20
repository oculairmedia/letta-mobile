package com.letta.mobile.feature.editagent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.common.SnackbarDispatcher
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.icons.LettaIcons

@Composable
internal fun EditAgentDialogs(
    showActionSheet: Boolean,
    onShowActionSheetChange: (Boolean) -> Unit,
    showResetDialog: Boolean,
    onShowResetDialogChange: (Boolean) -> Unit,
    showDeleteDialog: Boolean,
    onShowDeleteDialogChange: (Boolean) -> Unit,
    showCloneDialog: Boolean,
    onShowCloneDialogChange: (Boolean) -> Unit,
    agentState: EditAgentUiState,
    viewModel: EditAgentViewModel,
    snackbar: SnackbarDispatcher,
    context: Context,
    onNavigateBack: () -> Unit,
) {
    ActionSheet(
        show = showActionSheet,
        onDismiss = { onShowActionSheetChange(false) },
        title = "Actions",
    ) {
        ActionSheetItem(
            text = stringResource(R.string.action_save_settings),
            icon = LettaIcons.Check,
            onClick = {
                onShowActionSheetChange(false)
                viewModel.saveAgent {
                    snackbar.dispatch(context.getString(R.string.screen_agent_edit_agent_saved))
                }
            },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_export_agent),
            icon = LettaIcons.Share,
            onClick = {
                onShowActionSheetChange(false)
                viewModel.exportAgent { exportData ->
                    val exported = shareAgentExport(context, exportData)
                    snackbar.dispatch(
                        context.getString(
                            if (exported) R.string.screen_settings_export_ready else R.string.screen_settings_export_unavailable
                        )
                    )
                }
            },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_clone_agent),
            icon = LettaIcons.Copy,
            onClick = {
                onShowActionSheetChange(false)
                onShowCloneDialogChange(true)
            },
        )
    }

    ConfirmDialog(
        show = showResetDialog,
        title = stringResource(R.string.screen_settings_reset_messages_title),
        message = stringResource(R.string.screen_settings_reset_messages_confirm),
        confirmText = stringResource(R.string.action_reset_messages),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = {
            onShowResetDialogChange(false)
            viewModel.resetMessages {
                snackbar.dispatch(context.getString(R.string.screen_settings_messages_reset))
            }
        },
        onDismiss = { onShowResetDialogChange(false) },
        destructive = true,
    )

    ConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.screen_agents_dialog_delete_title),
        message = stringResource(R.string.screen_agents_dialog_delete_confirm_permanent),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = {
            onShowDeleteDialogChange(false)
            viewModel.deleteAgent(onNavigateBack)
        },
        onDismiss = { onShowDeleteDialogChange(false) },
        destructive = true,
    )

    if (showCloneDialog) {
        CloneAgentDialog(
            initialName = agentState.name,
            isCloning = agentState.isCloning,
            onDismiss = { onShowCloneDialogChange(false) },
            onClone = { cloneName, overrideExistingTools, stripMessages ->
                onShowCloneDialogChange(false)
                viewModel.cloneAgent(
                    cloneName = cloneName,
                    overrideExistingTools = overrideExistingTools,
                    stripMessages = stripMessages,
                ) { response ->
                    snackbar.dispatch(
                        context.getString(
                            if (response.agentIds.size == 1) {
                                R.string.screen_settings_clone_success_single
                            } else {
                                R.string.screen_settings_clone_success_multiple
                            },
                            response.agentIds.size,
                        )
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SectionIndexSheet(
    onDismiss: () -> Unit,
    onSelect: (anchorKey: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entries = listOf(
        SectionAnchors.BASICS to R.string.screen_agent_edit_section_basics,
        SectionAnchors.MODELS to R.string.screen_agent_edit_section_models,
        SectionAnchors.MEMORY to R.string.screen_agent_edit_section_memory,
        SectionAnchors.TOOLS to R.string.screen_agent_edit_section_tools,
        SectionAnchors.RUNTIME to R.string.screen_agent_edit_section_runtime,
        SectionAnchors.ADVANCED to R.string.screen_agent_edit_section_advanced,
        SectionAnchors.DANGER to R.string.screen_create_project_danger_zone_title,
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.screen_agent_edit_jump_to_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            entries.forEach { (anchorKey, labelRes) ->
                val isDanger = anchorKey == SectionAnchors.DANGER
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(anchorKey) },
                    headlineContent = {
                        Text(
                            text = stringResource(labelRes),
                            color = if (isDanger) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

internal suspend fun androidx.compose.foundation.lazy.LazyListState.animateScrollToKey(
    targetKey: Any,
) {
    val visible = layoutInfo.visibleItemsInfo
    val direct = visible.firstOrNull { it.key == targetKey }?.index
    if (direct != null) {
        animateScrollToItem(direct)
        return
    }
    val total = layoutInfo.totalItemsCount
    if (total == 0) return

    var lastSeenIndex = visible.lastOrNull()?.index ?: 0
    var safety = 0
    while (lastSeenIndex < total - 1 && safety < 16) {
        val nextStart = (lastSeenIndex + 1).coerceAtMost(total - 1)
        scrollToItem(nextStart)
        val found = layoutInfo.visibleItemsInfo.firstOrNull { it.key == targetKey }?.index
        if (found != null) {
            animateScrollToItem(found)
            return
        }
        val newLast = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: break
        if (newLast <= lastSeenIndex) break
        lastSeenIndex = newLast
        safety++
    }
}

internal fun shareAgentExport(context: Context, exportData: String): Boolean {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, exportData)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.screen_settings_export_subject))
    }

    val chooser = Intent.createChooser(shareIntent, context.getString(R.string.action_export_agent))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
