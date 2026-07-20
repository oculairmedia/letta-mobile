package com.letta.mobile.feature.editagent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.letta.mobile.ui.common.SnackbarDispatcher
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.icons.LettaIcons

internal data class EditAgentDialogToggle(
    val visible: Boolean,
    val onVisibleChange: (Boolean) -> Unit,
)

internal data class EditAgentDialogVisibility(
    val actionSheet: EditAgentDialogToggle,
    val resetDialog: EditAgentDialogToggle,
    val deleteDialog: EditAgentDialogToggle,
    val cloneDialog: EditAgentDialogToggle,
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
    val actionSheet = visibility.actionSheet
    ActionSheet(
        show = actionSheet.visible,
        onDismiss = { actionSheet.onVisibleChange(false) },
        title = "Actions",
    ) {
        ActionSheetItem(
            text = stringResource(R.string.action_save_settings),
            icon = LettaIcons.Check,
            onClick = {
                actionSheet.onVisibleChange(false)
                host.viewModel.saveAgent {
                    host.snackbar.dispatch(host.context.getString(R.string.screen_agent_edit_agent_saved))
                }
            },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_export_agent),
            icon = LettaIcons.Share,
            onClick = {
                actionSheet.onVisibleChange(false)
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
                actionSheet.onVisibleChange(false)
                visibility.cloneDialog.onVisibleChange(true)
            },
        )
    }
}

@Composable
private fun EditAgentResetDialog(
    visibility: EditAgentDialogVisibility,
    host: EditAgentDialogsHost,
) {
    val toggle = visibility.resetDialog
    EditAgentDestructiveConfirmDialog(
        spec = EditAgentDestructiveConfirmSpec(
            toggle = toggle,
            content = EditAgentDestructiveConfirmContent(
                title = stringResource(R.string.screen_settings_reset_messages_title),
                message = stringResource(R.string.screen_settings_reset_messages_confirm),
                confirmText = stringResource(R.string.action_reset_messages),
            ),
            onConfirm = {
                host.viewModel.resetMessages {
                    host.snackbar.dispatch(host.context.getString(R.string.screen_settings_messages_reset))
                }
            },
        ),
    )
}

@Composable
private fun EditAgentDeleteDialog(
    visibility: EditAgentDialogVisibility,
    host: EditAgentDialogsHost,
) {
    val toggle = visibility.deleteDialog
    EditAgentDestructiveConfirmDialog(
        spec = EditAgentDestructiveConfirmSpec(
            toggle = toggle,
            content = EditAgentDestructiveConfirmContent(
                title = stringResource(R.string.screen_agents_dialog_delete_title),
                message = stringResource(R.string.screen_agents_dialog_delete_confirm_permanent),
                confirmText = stringResource(R.string.action_delete),
            ),
            onConfirm = { host.viewModel.deleteAgent(host.onNavigateBack) },
        ),
    )
}

private data class EditAgentDestructiveConfirmContent(
    val title: String,
    val message: String,
    val confirmText: String,
)

private data class EditAgentDestructiveConfirmSpec(
    val toggle: EditAgentDialogToggle,
    val content: EditAgentDestructiveConfirmContent,
    val onConfirm: () -> Unit,
)

@Composable
private fun EditAgentDestructiveConfirmDialog(spec: EditAgentDestructiveConfirmSpec) {
    ConfirmDialog(
        show = spec.toggle.visible,
        title = spec.content.title,
        message = spec.content.message,
        confirmText = spec.content.confirmText,
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = {
            spec.toggle.onVisibleChange(false)
            spec.onConfirm()
        },
        onDismiss = { spec.toggle.onVisibleChange(false) },
        destructive = true,
    )
}

@Composable
private fun EditAgentCloneDialog(
    visibility: EditAgentDialogVisibility,
    host: EditAgentDialogsHost,
) {
    val toggle = visibility.cloneDialog
    if (!toggle.visible) return

    CloneAgentDialog(
        initialName = host.agentState.name,
        isCloning = host.agentState.isCloning,
        onDismiss = { toggle.onVisibleChange(false) },
        onClone = { cloneName, overrideExistingTools, stripMessages ->
            toggle.onVisibleChange(false)
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
