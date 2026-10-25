package com.letta.mobile.ui.canvas.references

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.references.NotebookReferenceContent
import com.letta.mobile.data.canvas.references.NotebookReferenceNode
import com.letta.mobile.data.canvas.references.NotebookReferencePhase
import com.letta.mobile.data.canvas.references.NotebookReferenceSession
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.notebook_reference_cancel
import com.letta.mobile.sharedui.resources.notebook_reference_cancelled
import com.letta.mobile.sharedui.resources.notebook_reference_cycle
import com.letta.mobile.sharedui.resources.notebook_reference_denied
import com.letta.mobile.sharedui.resources.notebook_reference_depth
import com.letta.mobile.sharedui.resources.notebook_reference_failed
import com.letta.mobile.sharedui.resources.notebook_reference_invalid
import com.letta.mobile.sharedui.resources.notebook_reference_loading
import com.letta.mobile.sharedui.resources.notebook_reference_missing
import com.letta.mobile.sharedui.resources.notebook_reference_nodes
import com.letta.mobile.sharedui.resources.notebook_reference_open
import com.letta.mobile.sharedui.resources.notebook_reference_refresh
import com.letta.mobile.sharedui.resources.notebook_reference_unavailable
import com.letta.mobile.sharedui.resources.notebook_reference_unsupported
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Read-only Markdown tree from one bounded [session]. Only the root starts a refresh; nodes paint
 * immutable data without polling or launching effects. [onOpenTarget] carries the same stored ID
 * as the preview and must reauthorize access in the trusted host before opening any editor.
 * This dormant component has no platform wiring and establishes no ownership or edit authority.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotebookReferencePreview(
    session: NotebookReferenceSession,
    onOpenTarget: (NotebookDocumentId) -> Unit,
    modifier: Modifier = Modifier,
) {
    key(session) {
        val state by session.state.collectAsState()
        val scope = rememberCoroutineScope()
        LaunchedEffect(session) {
            if (session.state.value.phase == NotebookReferencePhase.IDLE) session.refresh()
        }
        Column(
            modifier.testTag("notebook-reference-preview").verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                TextButton(
                    onClick = { scope.launch { session.refresh() } },
                    enabled = state.phase != NotebookReferencePhase.LOADING,
                ) { Text(stringResource(Res.string.notebook_reference_refresh)) }
                if (state.phase == NotebookReferencePhase.LOADING) {
                    TextButton(onClick = session::cancel) { Text(stringResource(Res.string.notebook_reference_cancel)) }
                }
            }
            ReferenceStatus(state.phase)
            state.root?.let { ReferenceBranch(it, onOpenTarget) }
        }
    }
}

@Composable
private fun ReferenceStatus(phase: NotebookReferencePhase) {
    val label = when (phase) {
        NotebookReferencePhase.LOADING -> Res.string.notebook_reference_loading
        NotebookReferencePhase.CANCELLED -> Res.string.notebook_reference_cancelled
        NotebookReferencePhase.FAILED -> Res.string.notebook_reference_failed
        else -> null
    }
    if (phase == NotebookReferencePhase.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
    label?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun ReferenceBranch(node: NotebookReferenceNode, onOpenTarget: (NotebookDocumentId) -> Unit) {
    // Cap visual indentation independently of traversal depth so compact previews remain readable.
    val indent = (node.path.size - 1).coerceIn(0, 3)
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        OutlinedCard(Modifier.fillMaxWidth().padding(start = LettaDimens.Space.sm * indent)) {
            Column(
                Modifier.padding(LettaDimens.Space.sm),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            ) {
                when (val content = node.content) {
                    is NotebookReferenceContent.Markdown -> ReferenceMarkdown(node.targetId, content, onOpenTarget)
                    is NotebookReferenceContent.Placeholder -> ReferencePlaceholder(content)
                }
            }
        }
        node.children.forEachIndexed { index, child ->
            key(index, child.item?.id) { ReferenceBranch(child, onOpenTarget) }
        }
    }
}

@Composable
private fun ReferenceMarkdown(
    targetId: NotebookDocumentId?,
    content: NotebookReferenceContent.Markdown,
    onOpenTarget: (NotebookDocumentId) -> Unit,
) {
    Text(content.title, style = MaterialTheme.typography.titleMedium)
    SharedMarkdownText(content.body)
    targetId?.let { target ->
        TextButton(onClick = { onOpenTarget(target) }) {
            Text(stringResource(Res.string.notebook_reference_open))
        }
    }
}

@Composable
private fun ReferencePlaceholder(reason: NotebookReferenceContent.Placeholder) {
    val label = when (reason) {
        NotebookReferenceContent.Placeholder.CYCLE -> Res.string.notebook_reference_cycle
        NotebookReferenceContent.Placeholder.DEPTH_LIMIT -> Res.string.notebook_reference_depth
        NotebookReferenceContent.Placeholder.NODE_LIMIT -> Res.string.notebook_reference_nodes
        NotebookReferenceContent.Placeholder.DENIED -> Res.string.notebook_reference_denied
        NotebookReferenceContent.Placeholder.MISSING -> Res.string.notebook_reference_missing
        NotebookReferenceContent.Placeholder.UNAVAILABLE -> Res.string.notebook_reference_unavailable
        NotebookReferenceContent.Placeholder.UNSUPPORTED_ITEM -> Res.string.notebook_reference_unsupported
        NotebookReferenceContent.Placeholder.INVALID_REFERENCE -> Res.string.notebook_reference_invalid
    }
    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
}
