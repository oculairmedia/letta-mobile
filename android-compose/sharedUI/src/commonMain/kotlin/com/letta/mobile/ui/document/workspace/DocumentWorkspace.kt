package com.letta.mobile.ui.document.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.workspace.DocumentPresentation
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceController
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceProblem
import com.letta.mobile.data.canvas.workspace.DocumentWorkspaceState
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.document_workspace_back
import com.letta.mobile.sharedui.resources.document_workspace_close
import com.letta.mobile.sharedui.resources.document_workspace_denied
import com.letta.mobile.sharedui.resources.document_workspace_empty
import com.letta.mobile.sharedui.resources.document_workspace_identity
import com.letta.mobile.sharedui.resources.document_workspace_note
import com.letta.mobile.sharedui.resources.document_workspace_references
import com.letta.mobile.sharedui.resources.document_workspace_save_failed
import com.letta.mobile.sharedui.resources.document_workspace_title
import com.letta.mobile.sharedui.resources.document_workspace_unavailable
import com.letta.mobile.sharedui.resources.document_workspace_unsupported_references
import com.letta.mobile.ui.canvas.notes.NotebookNoteEditorContent
import com.letta.mobile.ui.canvas.references.NotebookReferencePreview
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaBusyBar
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * One document with Note and References presentations. The caller opens the initial exact identity
 * through [controller] and must await its close acknowledgement before removing this workspace.
 * The required trusted opener supplies authorization and capability; this UI invents neither.
 */
@Composable
fun DocumentWorkspace(controller: DocumentWorkspaceController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val operationScope = rememberCoroutineScope()
    LettaPageScaffold(
        modifier = modifier.testTag("document-workspace"),
        header = { WorkspaceHeader(controller, state) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize().testTag("document-workspace-wide")) {
                    Column(Modifier.width(280.dp).padding(LettaDimens.Space.sm)) {
                        PresentationSelector(controller, state)
                    }
                    WorkspaceContent(controller, state, operationScope, Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize().testTag("document-workspace-compact")) {
                    PresentationSelector(controller, state)
                    WorkspaceContent(controller, state, operationScope, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceHeader(controller: DocumentWorkspaceController, state: DocumentWorkspaceState) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(LettaDimens.Space.sm)) {
        Text(stringResource(Res.string.document_workspace_title), style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            TextButton(onClick = { scope.launch { controller.back() } }, enabled = state.canGoBack) {
                Text(stringResource(Res.string.document_workspace_back))
            }
            TextButton(onClick = { scope.launch { controller.close() } }, enabled = state.current != null) {
                Text(stringResource(Res.string.document_workspace_close))
            }
        }
        if (state.transitioning) LettaBusyBar()
        WorkspaceProblem(state.problem)
    }
}

@Composable
private fun PresentationSelector(controller: DocumentWorkspaceController, state: DocumentWorkspaceState) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(LettaDimens.Space.sm)) {
        DocumentPresentation.entries.forEachIndexed { index, presentation ->
            SegmentedButton(
                selected = state.presentation == presentation,
                onClick = { controller.present(presentation) },
                enabled = state.current != null,
                shape = SegmentedButtonDefaults.itemShape(index, DocumentPresentation.entries.size),
                modifier = Modifier.testTag("document-presentation-${presentation.name.lowercase()}"),
            ) {
                Text(stringResource(if (presentation == DocumentPresentation.NOTE) {
                    Res.string.document_workspace_note
                } else {
                    Res.string.document_workspace_references
                }))
            }
        }
    }
}

@Composable
private fun WorkspaceContent(
    controller: DocumentWorkspaceController,
    state: DocumentWorkspaceState,
    scope: CoroutineScope,
    modifier: Modifier,
) {
    val current = state.current
    val references = current?.references
    Box(modifier) {
        when {
            current == null -> Text(stringResource(Res.string.document_workspace_empty), Modifier.padding(LettaDimens.Space.md))
            state.presentation == DocumentPresentation.NOTE -> NotebookNoteEditorContent(current.note, Modifier.fillMaxSize(), scope)
            references != null -> NotebookReferencePreview(
                references,
                onOpenTarget = { id -> scope.launch { controller.open(id) } },
                modifier = Modifier.fillMaxSize().padding(LettaDimens.Space.sm),
            )
            else -> Text(
                stringResource(Res.string.document_workspace_unsupported_references),
                Modifier.padding(LettaDimens.Space.md),
            )
        }
    }
}

@Composable
private fun WorkspaceProblem(problem: DocumentWorkspaceProblem?) {
    val label = when (problem) {
        DocumentWorkspaceProblem.DENIED -> Res.string.document_workspace_denied
        DocumentWorkspaceProblem.UNAVAILABLE -> Res.string.document_workspace_unavailable
        DocumentWorkspaceProblem.IDENTITY_MISMATCH -> Res.string.document_workspace_identity
        DocumentWorkspaceProblem.SAVE_FAILED -> Res.string.document_workspace_save_failed
        null -> null
    }
    label?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
}
