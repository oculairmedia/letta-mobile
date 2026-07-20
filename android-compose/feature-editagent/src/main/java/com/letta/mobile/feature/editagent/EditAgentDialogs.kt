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

internal data class EditAgentDialogVisibility(
    val showActionSheet: Boolean,
    val onShowActionSheetChange: (Boolean) -> Unit,
    val showResetDialog: Boolean,
    val onShowResetDialogChange: (Boolean) -> Unit,
    val showDeleteDialog: Boolean,
    val onShowDeleteDialogChange: (Boolean) -> Unit,
    val showCloneDialog: Boolean,
    val onShowCloneDialogChange: (Boolean) -> Unit,
)

internal data class EditAgentDialogsHost(
    val agentState: EditAgentUiState,
    val viewModel: EditAgentViewModel,
    val snackbar: SnackbarDispatcher,
    val context: Context,
    val onNavigateBack: () -> Unit,
)

@Composable
internal fun EditAgentDialogs(
    visibility: EditAgentDialogVisibility,
    host: EditAgentDialogsHost,
) {
    EditAgentActionSheet(visibility, host)
    EditAgentResetDialog(visibility, host)
    EditAgentDeleteDialog(visibility, host)
    EditAgentCloneDialog(visibility, host)
}

@Composable
private fun EditAgentActionSheet(
    visibility: EditAgentDialogVisibility,
    host: EditAgentDialogsHost,
) {
    ActionSheet(
        show = visibility.showActionSheet,
        onDismiss = { visibility.onShowActionSheetChange(false) },
        title = "Actions",
    ) {
        ActionSheetItem(
            text = stringResource(R.string.action_save_settings),
            icon = LettaIcons.Check,
            onClick = {
                visibility.onShowActionSheetChange(false)
                host.viewModel.saveAgent {
                    host.snackbar.dispatch(host.context.getString(R.string.screen_agent_edit_agent_saved))
                }
            },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_export_agent),
            icon = LettaIcons.Share,
            onClick = {
                visibility.onShowActionSheetChange(false)
                host.viewModel.exportAgent { exportData ->
                    val exported = shareAgentExport(host.context, exportData)
                    host.snackbar.dispatch(
                        host.context.getString(
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
                visibility.onShowActionSheetChange(false)
                visibility.onShowCloneDialogChange(true)
            },
        )
    }
}

@Composable
private fun EditAgentResetDialog(
    visibility: EditAgentDialogVisibility,
    host: EditAgentDialogsHost,
) {
    EditAgentDestructiveConfirmDialog(
        show = visibility.showResetDialog,
        title = stringResource(R.string.screen_settings_reset_messages_title),
        message = stringResource(R.string.screen_settings_reset_messages_confirm),
        confirmText = stringResource(R.string.action_reset_messages),
        onDismiss = { visibility.onShowResetDialogChange(false) },
        onConfirm = {
            host.viewModel.resetMessages {
                host.snackbar.dispatch(host.context.getString(R.string.screen_settings_messages_reset))
            }
        },
    )
}

@Composable
private fun EditAgentDeleteDialog(
    visibility: EditAgentDialogVisibility,
    host: EditAgentDialogsHost,
) {
    EditAgentDestructiveConfirmDialog(
        show = visibility.showDeleteDialog,
        title = stringResource(R.string.screen_agents_dialog_delete_title),
        message = stringResource(R.string.screen_agents_dialog_delete_confirm_permanent),
        confirmText = stringResource(R.string.action_delete),
        onDismiss = { visibility.onShowDeleteDialogChange(false) },
        onConfirm = { host.viewModel.deleteAgent(host.onNavigateBack) },
    )
}

@Composable
private fun EditAgentDestructiveConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        show = show,
        title = title,
        message = message,
        confirmText = confirmText,
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = {
            onDismiss()
            onConfirm()
        },
        onDismiss = onDismiss,
        destructive = true,
    )
}

@Composable
private fun EditAgentCloneDialog(
    visibility: EditAgentDialogVisibility,
    host: EditAgentDialogsHost,
) {
    if (!visibility.showCloneDialog) return

    CloneAgentDialog(
        initialName = host.agentState.name,
        isCloning = host.agentState.isCloning,
        onDismiss = { visibility.onShowCloneDialogChange(false) },
        onClone = { cloneName, overrideExistingTools, stripMessages ->
            visibility.onShowCloneDialogChange(false)
            host.viewModel.cloneAgent(
                cloneName = cloneName,
                overrideExistingTools = overrideExistingTools,
                stripMessages = stripMessages,
            ) { response ->
                host.snackbar.dispatch(
                    host.context.getString(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SectionIndexSheet(
    onDismiss: () -> Unit,
    onSelect: (anchorKey: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        SectionIndexSheetContent(onSelect = onSelect)
    }
}

@Composable
private fun SectionIndexSheetContent(onSelect: (anchorKey: String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.screen_agent_edit_jump_to_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        sectionIndexEntries().forEach { (anchorKey, labelRes) ->
            SectionIndexSheetRow(
                anchorKey = anchorKey,
                labelRes = labelRes,
                onSelect = onSelect,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SectionIndexSheetRow(
    anchorKey: String,
    labelRes: Int,
    onSelect: (anchorKey: String) -> Unit,
) {
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

private fun sectionIndexEntries(): List<Pair<String, Int>> = listOf(
    SectionAnchors.BASICS to R.string.screen_agent_edit_section_basics,
    SectionAnchors.MODELS to R.string.screen_agent_edit_section_models,
    SectionAnchors.MEMORY to R.string.screen_agent_edit_section_memory,
    SectionAnchors.TOOLS to R.string.screen_agent_edit_section_tools,
    SectionAnchors.RUNTIME to R.string.screen_agent_edit_section_runtime,
    SectionAnchors.ADVANCED to R.string.screen_agent_edit_section_advanced,
    SectionAnchors.DANGER to R.string.screen_create_project_danger_zone_title,
)

internal suspend fun androidx.compose.foundation.lazy.LazyListState.animateScrollToKey(
    targetKey: Any,
) {
    indexOfVisibleKey(targetKey)?.let { index ->
        animateScrollToItem(index)
        return
    }
    scrollProgressivelyToKey(targetKey)
}

private fun androidx.compose.foundation.lazy.LazyListState.indexOfVisibleKey(targetKey: Any): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == targetKey }?.index

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollProgressivelyToKey(
    targetKey: Any,
) {
    val total = layoutInfo.totalItemsCount
    if (total == 0) return

    var lastSeenIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    var safety = 0
    while (lastSeenIndex < total - 1 && safety < 16) {
        val nextStart = (lastSeenIndex + 1).coerceAtMost(total - 1)
        scrollToItem(nextStart)
        indexOfVisibleKey(targetKey)?.let { found ->
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
