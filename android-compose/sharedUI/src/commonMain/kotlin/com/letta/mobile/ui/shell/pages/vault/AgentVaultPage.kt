package com.letta.mobile.ui.shell.pages.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.letta.mobile.data.secrets.AgentVaultActions
import com.letta.mobile.data.secrets.AgentVaultLoad
import com.letta.mobile.data.secrets.AgentVaultState
import com.letta.mobile.ui.theme.LettaDimens

/**
 * An agent's secrets vault, shared by desktop and Android (letta-mobile-bzvro.25): the keys its
 * tools can read, each value masked until the user reveals it, with add, replace and delete.
 * Values are kept by the App Server only; the page never stores them, and it masks everything
 * again when it leaves the screen.
 *
 * At or above [LettaDimens.Pane.wideBreakpoint] the editor docks beside the list; below it the
 * editor rises as a bottom sheet.
 */
@Composable
fun AgentVaultPage(
    state: AgentVaultState,
    actions: AgentVaultActions,
    modifier: Modifier = Modifier,
    options: AgentVaultPageOptions = AgentVaultPageOptions(),
) {
    DisposableEffect(actions) { onDispose { actions.hideAll() } }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(AgentVaultPageTags.PAGE)) {
        val page = AgentVaultPageScope(state, actions, options, wide = maxWidth >= LettaDimens.Pane.wideBreakpoint)
        if (page.wide) WideVaultLayout(page) else CompactVaultLayout(page)
        state.pendingDelete?.let { key -> DeleteSecretDialog(key, page) }
    }
}

/** Host presentation choices: [showTitle] false when the host already titles the screen; [touch] grows controls. */
@Immutable
data class AgentVaultPageOptions(
    val showTitle: Boolean = true,
    val touch: Boolean = false,
)

/** Test tags for the shared vault page. */
object AgentVaultPageTags {
    const val PAGE = "vault_page"
    const val ADD = "vault_add"
    const val REFRESH = "vault_refresh"
    const val EMPTY = "vault_empty"
    const val ERROR = "vault_error"
    const val LIST = "vault_list"
    const val EDITOR = "vault_editor"
    const val EDITOR_KEY = "vault_editor_key"
    const val EDITOR_VALUE = "vault_editor_value"
    const val EDITOR_SHOW_VALUE = "vault_editor_show_value"
    const val EDITOR_SAVE = "vault_editor_save"
    const val EDITOR_CANCEL = "vault_editor_cancel"
    const val DELETE_CONFIRM = "vault_delete_confirm"

    fun row(key: String): String = "vault_row_$key"

    fun value(key: String): String = "vault_value_$key"

    fun reveal(key: String): String = "vault_reveal_$key"

    fun edit(key: String): String = "vault_edit_$key"

    fun delete(key: String): String = "vault_delete_$key"
}

internal class AgentVaultPageScope(
    val state: AgentVaultState,
    val actions: AgentVaultActions,
    val options: AgentVaultPageOptions,
    val wide: Boolean,
)

@Composable
private fun WideVaultLayout(page: AgentVaultPageScope) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            VaultHeader(page)
            VaultBody(page)
        }
        page.state.draft?.let { draft ->
            VerticalDivider()
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(LettaDimens.Pane.sidePanelWidth).fillMaxHeight()) {
                SecretEditor(draft, page)
            }
        }
    }
}

@Composable
private fun CompactVaultLayout(page: AgentVaultPageScope) {
    Column(Modifier.fillMaxSize()) {
        VaultHeader(page)
        VaultBody(page)
    }
    page.state.draft?.let { draft ->
        ModalBottomSheet(onDismissRequest = page.actions::cancelDraft) { SecretEditor(draft, page) }
    }
}

@Composable
private fun VaultHeader(page: AgentVaultPageScope) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (page.options.showTitle) Text(PAGE_TITLE, style = MaterialTheme.typography.headlineSmall)
            }
            IconButton(onClick = page.actions::refresh, modifier = Modifier.testTag(AgentVaultPageTags.REFRESH)) {
                Icon(Icons.Outlined.Refresh, contentDescription = REFRESH_LABEL)
            }
            Button(
                onClick = page.actions::startAdding,
                enabled = page.state.agentId != null && !page.state.saving,
                modifier = Modifier.testTag(AgentVaultPageTags.ADD),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text(ADD_LABEL, modifier = Modifier.padding(start = LettaDimens.Space.xs))
            }
        }
        Text(STORAGE_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (page.state.load == AgentVaultLoad.Loading || page.state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        page.state.error?.let { message -> VaultError(message, page) }
    }
}

@Composable
private fun VaultError(message: String, page: AgentVaultPageScope) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(AgentVaultPageTags.ERROR)) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = page.actions::dismissError) { Text(DISMISS_LABEL) }
    }
}

@Composable
private fun VaultBody(page: AgentVaultPageScope) {
    val state = page.state
    val load = state.load
    val empty = state.emptyMessage
    when {
        load is AgentVaultLoad.Failed -> VaultMessage(load.message, Modifier.testTag(AgentVaultPageTags.ERROR)) {
            OutlinedButton(onClick = page.actions::refresh) { Text(RETRY_LABEL) }
        }
        empty != null -> VaultMessage(empty, Modifier.testTag(AgentVaultPageTags.EMPTY))
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(AgentVaultPageTags.LIST),
            contentPadding = ListPadding,
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            items(state.secrets, key = { it.key }) { secret -> SecretRow(secret.key, page) }
        }
    }
}

@Composable
private fun VaultMessage(message: String, modifier: Modifier, actions: @Composable () -> Unit = {}) {
    Column(
        modifier = modifier.fillMaxWidth().padding(LettaDimens.Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        actions()
    }
}

@Composable
private fun DeleteSecretDialog(key: String, page: AgentVaultPageScope) {
    AlertDialog(
        onDismissRequest = page.actions::cancelDelete,
        title = { Text("Delete $key?") },
        text = { Text(DELETE_MESSAGE) },
        confirmButton = {
            TextButton(onClick = page.actions::confirmDelete, modifier = Modifier.testTag(AgentVaultPageTags.DELETE_CONFIRM)) {
                Text(DELETE_LABEL, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = page.actions::cancelDelete) { Text(CANCEL_LABEL) } },
    )
}

private const val PAGE_TITLE = "Secrets"
private const val REFRESH_LABEL = "Refresh"
private const val ADD_LABEL = "Add secret"
private const val DISMISS_LABEL = "Dismiss"
private const val RETRY_LABEL = "Try again"
private const val DELETE_LABEL = "Delete"
private const val CANCEL_LABEL = "Cancel"
private const val STORAGE_NOTE =
    "Keys this agent's tools can read. Values are stored on the App Server and never saved on this device."
private const val DELETE_MESSAGE = "Tools that read this secret will stop finding it. This cannot be undone."
private val ListPadding = PaddingValues(
    start = LettaDimens.Space.lg,
    end = LettaDimens.Space.lg,
    bottom = LettaDimens.Space.xl,
)
