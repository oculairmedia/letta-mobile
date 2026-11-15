package com.letta.mobile.ui.shell.pages.memfs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.memory.memfs.MemfsEditor
import com.letta.mobile.data.memory.memfs.MemfsNavigation
import com.letta.mobile.ui.components.LettaInlineError
import com.letta.mobile.ui.components.foundation.LettaButtonIcon
import com.letta.mobile.ui.components.foundation.LettaIconButton
import com.letta.mobile.ui.components.foundation.LettaInlineNotice
import com.letta.mobile.ui.components.foundation.LettaNotice
import com.letta.mobile.ui.components.foundation.LettaNoticeAction
import com.letta.mobile.ui.host.LettaConfirmPane
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.tokens.LettaPanelTone
import com.letta.mobile.ui.theme.tokens.LettaTextStyles
import com.letta.mobile.ui.host.LettaConfirmRequest
import com.letta.mobile.ui.host.LettaConfirmTags
import com.letta.mobile.ui.components.foundation.LettaBusyBar
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/** The open memory file: its toolbar, any conflict or error, and the editor itself. */
@Composable
internal fun MemfsEditorPane(editor: MemfsEditor, page: MemfsPageScope) {
    Column(Modifier.fillMaxSize().testTag(MemfsPageTags.EDITOR)) {
        MemfsEditorToolbar(editor, page)
        if (editor.loading || editor.saving) LettaBusyBar()
        if (editor.conflict) ConflictBanner(page)
        editor.error?.let { message ->
            LettaInlineError(
                message,
                Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
                title = null,
            )
        }
        when {
            editor.isImage -> MemfsDetailHint(IMAGE_PLACEHOLDER)
            editor.loading -> Unit
            else -> OutlinedTextField(
                value = editor.draft,
                onValueChange = page.actions::editDraft,
                readOnly = page.options.readOnly,
                textStyle = LettaTextStyles.monoBody,
                modifier = Modifier.fillMaxSize().padding(LettaDimens.Space.md).testTag(MemfsPageTags.EDITOR_FIELD),
            )
        }
    }
}

@Composable
private fun MemfsEditorToolbar(editor: MemfsEditor, page: MemfsPageScope) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        LettaIconButton(
            icon = LettaButtonIcon(if (page.wide) LettaIcons.Close else LettaIcons.ArrowBack, CLOSE_LABEL),
            onClick = page.actions::closeFile,
            modifier = Modifier.testTag(MemfsPageTags.BACK),
        )
        Column(Modifier.weight(1f)) {
            Text(editor.path, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (editor.dirty) {
                Text(UNSAVED_LABEL, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.customColors.warningTextColor)
            }
        }
        LettaIconButton(
            icon = LettaButtonIcon(Icons.Outlined.History, FILE_HISTORY_LABEL),
            onClick = { page.actions.showHistory(editor.path) },
            modifier = Modifier.testTag(MemfsPageTags.FILE_HISTORY),
        )
        if (!page.options.readOnly && !editor.isImage) {
            TextButton(onClick = page.actions::revertDraft, enabled = editor.dirty, modifier = Modifier.testTag(MemfsPageTags.REVERT)) {
                Text(REVERT_LABEL)
            }
            Button(onClick = page.actions::save, enabled = editor.canSave, modifier = Modifier.testTag(MemfsPageTags.SAVE)) {
                Text(if (editor.saving) SAVING_LABEL else SAVE_LABEL)
            }
        }
    }
}

@Composable
private fun ConflictBanner(page: MemfsPageScope) {
    LettaInlineNotice(
        notice = LettaNotice(CONFLICT_MESSAGE, tone = LettaPanelTone.Warning),
        modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs).testTag(MemfsPageTags.CONFLICT),
        action = LettaNoticeAction(RELOAD_LABEL, page.actions::reloadFromServer, tag = MemfsPageTags.CONFLICT_RELOAD),
        dismiss = LettaNoticeAction(KEEP_LABEL, page.actions::keepDraft, tag = MemfsPageTags.CONFLICT_KEEP),
    )
}

/** Asks before a step that would throw away the open file's unsaved edits. */
@Composable
internal fun DiscardChangesDialog(page: MemfsPageScope) {
    val pending = page.state.pendingNavigation ?: return
    val request = LettaConfirmRequest(
        title = DISCARD_TITLE,
        message = discardMessage(page.state.editor?.path, pending),
        confirmLabel = DISCARD_LABEL,
        dismissLabel = KEEP_EDITING_LABEL,
        tags = LettaConfirmTags(pane = MemfsPageTags.DISCARD_DIALOG, confirm = MemfsPageTags.DISCARD_CONFIRM, dismiss = MemfsPageTags.DISCARD_CANCEL),
    )
    LettaConfirmPane(request, onConfirm = page.actions::confirmDiscard, onDismiss = page.actions::cancelDiscard)
}

private fun discardMessage(path: String?, pending: MemfsNavigation): String {
    val file = path ?: "this file"
    val next = when (pending) {
        is MemfsNavigation.Open -> "opening ${pending.path}"
        MemfsNavigation.Close -> "closing it"
        is MemfsNavigation.SwitchAgent -> "switching agents"
    }
    return "$file has unsaved changes. They will be lost by $next."
}

private const val IMAGE_PLACEHOLDER = "Image memory files can't be shown or edited here."
private const val CLOSE_LABEL = "Close file"
private const val UNSAVED_LABEL = "Unsaved changes"
private const val FILE_HISTORY_LABEL = "History of this file"
private const val REVERT_LABEL = "Revert"
private const val SAVE_LABEL = "Save"
private const val SAVING_LABEL = "Saving…"
private const val CONFLICT_MESSAGE = "The agent changed this file while you were editing it."
private const val RELOAD_LABEL = "Load theirs"
private const val KEEP_LABEL = "Keep mine"
private const val DISCARD_TITLE = "Discard unsaved changes?"
private const val DISCARD_LABEL = "Discard"
private const val KEEP_EDITING_LABEL = "Keep editing"
