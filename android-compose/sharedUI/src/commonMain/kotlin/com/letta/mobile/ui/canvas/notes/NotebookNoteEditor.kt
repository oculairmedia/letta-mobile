package com.letta.mobile.ui.canvas.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import com.letta.mobile.data.canvas.notes.NotebookNoteSession
import com.letta.mobile.data.canvas.notes.NotebookNoteState
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.notebook_note_markdown
import com.letta.mobile.sharedui.resources.notebook_note_title
import com.letta.mobile.sharedui.resources.notebook_note_save
import com.letta.mobile.sharedui.resources.notebook_note_refresh
import com.letta.mobile.sharedui.resources.notebook_note_discard_reload
import com.letta.mobile.sharedui.resources.notebook_note_retry
import com.letta.mobile.sharedui.resources.notebook_note_saving
import com.letta.mobile.sharedui.resources.notebook_note_loading
import com.letta.mobile.sharedui.resources.notebook_note_pending
import com.letta.mobile.sharedui.resources.notebook_note_dirty
import com.letta.mobile.sharedui.resources.notebook_note_clean
import com.letta.mobile.sharedui.resources.notebook_note_conflict
import com.letta.mobile.sharedui.resources.notebook_note_not_found
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Shared explicit-save Markdown editor over one [session]. The keyed composition cancels UI work
 * when that session is removed; hosts must await [NotebookNoteSession.prepareClose] and retain the
 * component when it returns false. This component supplies no host navigation or remote authority.
 */
@Composable
fun NotebookNoteEditor(session: NotebookNoteSession, modifier: Modifier = Modifier) {
    key(session) {
        val state by session.state.collectAsState()
        val scope = rememberCoroutineScope()
        LaunchedEffect(session) {
            if (session.state.value.baseline == null) session.refresh()
        }
        LettaPageScaffold(
            modifier = modifier.testTag("notebook-note-editor"),
            header = { NoteToolbar(session, state, scope) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(LettaDimens.Space.md)) {
                NoteStatus(state)
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = session::edit,
                    enabled = state.baseline != null,
                    label = { Text(stringResource(Res.string.notebook_note_markdown)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxSize().testTag("notebook-note-markdown"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteToolbar(session: NotebookNoteSession, state: NotebookNoteState, scope: CoroutineScope) {
    Column(Modifier.fillMaxWidth().padding(LettaDimens.Space.sm)) {
        Text(state.baseline?.title ?: stringResource(Res.string.notebook_note_title), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            Button(onClick = { scope.launch { session.save() } }, enabled = state.canSave && state.dirty && !state.pendingDurability) {
                Text(stringResource(Res.string.notebook_note_save))
            }
            TextButton(onClick = { scope.launch { session.refresh() } }, enabled = !state.loading && !state.saving) {
                Text(stringResource(Res.string.notebook_note_refresh))
            }
            TextButton(
                onClick = { scope.launch { session.discardAndReload() } },
                enabled = state.baseline != null && !state.loading && !state.saving,
            ) { Text(stringResource(Res.string.notebook_note_discard_reload)) }
            if (state.pendingDurability) {
                TextButton(onClick = { scope.launch { session.retryPersistence() } }, enabled = !state.saving && !state.loading) {
                    Text(stringResource(Res.string.notebook_note_retry))
                }
            }
        }
    }
}

@Composable
private fun NoteStatus(state: NotebookNoteState) {
    if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
    val status = when {
        state.saving -> Res.string.notebook_note_saving
        state.loading -> Res.string.notebook_note_loading
        state.pendingDurability -> Res.string.notebook_note_pending
        state.dirty -> Res.string.notebook_note_dirty
        state.baseline != null -> Res.string.notebook_note_clean
        else -> null
    }
    status?.let { Text(stringResource(it), style = MaterialTheme.typography.labelMedium) }
    if (state.conflict) {
        Text(stringResource(Res.string.notebook_note_conflict), color = MaterialTheme.colorScheme.error)
    }
    if (state.notFound) Text(stringResource(Res.string.notebook_note_not_found), color = MaterialTheme.colorScheme.error)
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
