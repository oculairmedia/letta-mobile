package com.letta.mobile.ui.screens.agentlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ca.oculair.meridian.R
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Import an agent from an exported file: the name to give it and two safety switches, then the file.
 * Creating an agent is the shared agent editor's new-agent flow (letta-mobile-ckmub.3.24); this
 * dialog stays Android's until import joins that flow (follow-up on letta-mobile-ckmub.3.24).
 */
@Composable
internal fun ImportAgentDialog(
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onImport: (overrideName: String?, overrideExistingTools: Boolean, stripMessages: Boolean) -> Unit,
) {
    var overrideName by remember { mutableStateOf("") }
    var overrideExistingTools by remember { mutableStateOf(true) }
    var stripMessages by remember { mutableStateOf(false) }

    MultiFieldInputDialog(
        show = true,
        title = stringResource(R.string.screen_agents_import_title),
        confirmText = stringResource(R.string.action_choose_file),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = !isImporting,
        onConfirm = {
            onImport(overrideName.ifBlank { null }, overrideExistingTools, stripMessages)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
            Text(
                text = stringResource(R.string.screen_agents_import_helper),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = overrideName,
                onValueChange = { overrideName = it },
                label = { Text(stringResource(R.string.screen_agents_import_override_name)) },
                placeholder = { Text(stringResource(R.string.screen_agents_import_override_name_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            FormItem(
                label = { Text(stringResource(R.string.screen_agents_import_override_tools_title)) },
                description = {
                    Text(stringResource(R.string.screen_agents_import_override_tools_helper))
                },
                tail = {
                    Switch(checked = overrideExistingTools, onCheckedChange = { overrideExistingTools = it })
                },
            )
            FormItem(
                label = { Text(stringResource(R.string.screen_agents_import_strip_messages_title)) },
                description = {
                    Text(stringResource(R.string.screen_agents_import_strip_messages_helper))
                },
                tail = {
                    Switch(checked = stripMessages, onCheckedChange = { stripMessages = it })
                },
            )
        }
    }
}

/** The import dialog over the list's state; a pick hands the switches to the file launcher. */
@Composable
internal fun AgentListImportDialogHost(
    uiState: AgentListUiState,
    viewModel: AgentListViewModel,
    onImport: () -> Unit,
) {
    if (!uiState.showImportDialog) return
    ImportAgentDialog(
        isImporting = uiState.isImporting,
        onDismiss = { viewModel.hideImportDialog() },
        onImport = { overrideName, overrideExistingTools, stripMessages ->
            viewModel.setPendingImportName(overrideName)
            viewModel.setPendingImportOverrideTools(overrideExistingTools)
            viewModel.setPendingImportStripMessages(stripMessages)
            onImport()
            viewModel.hideImportDialog()
        },
    )
}
