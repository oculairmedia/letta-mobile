package com.letta.mobile.ui.shell.pages.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.secrets.AgentSecretDraft
import com.letta.mobile.data.secrets.SecretKey
import com.letta.mobile.ui.theme.LettaDimens

/** One key: its name, its masked (or revealed) value, and the reveal, replace and delete actions. */
@Composable
internal fun SecretRow(key: String, page: AgentVaultPageScope) {
    val revealed = key in page.state.revealed
    val actionModifier = if (page.options.touch) Modifier.minimumInteractiveComponentSize() else Modifier
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().testTag(AgentVaultPageTags.row(key)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
                Text(key, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace))
                Text(
                    page.state.displayValue(key),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(AgentVaultPageTags.value(key)),
                )
            }
            IconButton(onClick = { page.actions.toggleReveal(SecretKey(key)) }, modifier = actionModifier.testTag(AgentVaultPageTags.reveal(key))) {
                Icon(
                    if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (revealed) "Hide $key" else "Reveal $key",
                )
            }
            IconButton(onClick = { page.actions.startEditing(SecretKey(key)) }, modifier = actionModifier.testTag(AgentVaultPageTags.edit(key))) {
                Icon(Icons.Outlined.Edit, contentDescription = "Replace $key")
            }
            IconButton(onClick = { page.actions.requestDelete(SecretKey(key)) }, modifier = actionModifier.testTag(AgentVaultPageTags.delete(key))) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete $key", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Adds a secret or replaces one's value. The value field is a password field: masked unless the
 * user shows it, and kept out of keyboard suggestions.
 */
@Composable
internal fun SecretEditor(draft: AgentSecretDraft, page: AgentVaultPageScope) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.lg).testTag(AgentVaultPageTags.EDITOR),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Text(if (draft.isNew) NEW_TITLE else "Replace ${draft.key}", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = draft.key,
            onValueChange = page.actions::updateDraftKey,
            label = { Text(KEY_LABEL) },
            enabled = draft.isNew,
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag(AgentVaultPageTags.EDITOR_KEY),
        )
        OutlinedTextField(
            value = draft.value.reveal(),
            onValueChange = page.actions::updateDraftValue,
            label = { Text(VALUE_LABEL) },
            singleLine = true,
            visualTransformation = if (draft.showValue) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = page.actions::toggleDraftValueVisible, modifier = Modifier.testTag(AgentVaultPageTags.EDITOR_SHOW_VALUE)) {
                    Icon(
                        if (draft.showValue) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (draft.showValue) HIDE_VALUE else SHOW_VALUE,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().testTag(AgentVaultPageTags.EDITOR_VALUE),
        )
        page.state.draftProblem?.takeIf { draft.key.isNotEmpty() || !draft.value.isBlank }?.let { problem ->
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), modifier = Modifier.align(Alignment.End)) {
            TextButton(onClick = page.actions::cancelDraft, modifier = Modifier.testTag(AgentVaultPageTags.EDITOR_CANCEL)) {
                Text(CANCEL_LABEL)
            }
            Button(onClick = page.actions::saveDraft, enabled = page.state.canSaveDraft, modifier = Modifier.testTag(AgentVaultPageTags.EDITOR_SAVE)) {
                Text(if (page.state.saving) SAVING_LABEL else SAVE_LABEL)
            }
        }
    }
}

private const val NEW_TITLE = "New secret"
private const val KEY_LABEL = "Name"
private const val VALUE_LABEL = "Value"
private const val SHOW_VALUE = "Show value"
private const val HIDE_VALUE = "Hide value"
private const val CANCEL_LABEL = "Cancel"
private const val SAVE_LABEL = "Save"
private const val SAVING_LABEL = "Saving…"
