package com.letta.mobile.ui.shell.pages.workspace

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.links.OutputLinks
import com.letta.mobile.data.workspace.WorkspaceFileOpener
import com.letta.mobile.data.workspace.WorkspaceFileRequest
import com.letta.mobile.data.workspace.WorkspaceFileViewerActions
import com.letta.mobile.data.workspace.WorkspaceFileViewerState
import com.letta.mobile.data.workspace.WorkspacePaths
import kotlinx.coroutines.flow.StateFlow

/**
 * What a host binds for workspace files: how a path opens ([opener], resolving it against the
 * conversation's working directory or the agent's memory), and the viewer that shows it. The
 * shared chat draws the viewer itself, where the window keeps its detail panes, so the host never
 * places a viewer of its own.
 */
@Immutable
class WorkspaceFiles(
    val opener: WorkspaceFileOpener,
    val viewer: StateFlow<WorkspaceFileViewerState>,
    val actions: WorkspaceFileViewerActions,
)

/** The host's [WorkspaceFiles]; null where it cannot read files, and no file chip or path link shows. */
val LocalWorkspaceFiles = staticCompositionLocalOf<WorkspaceFiles?> { null }

/** The host's viewer, open on what its chips and links asked for; nothing where the host reads no files. */
@Composable
fun WorkspaceFilePreview() {
    val files = LocalWorkspaceFiles.current ?: return
    val state by files.viewer.collectAsState()
    WorkspaceFileViewer(state = state, actions = files.actions)
}

/** Test tags for a step's file chip. */
object WorkspaceFileLinkTags {
    const val LINK = "workspace_file_link"
}

/**
 * A file a tool step touched, as a chip ("Main.kt #L3-4") that opens it in the viewer, scrolled to
 * the lines it names; nothing when the host reads no files.
 */
@Composable
fun WorkspaceFileChip(request: WorkspaceFileRequest, modifier: Modifier = Modifier) {
    val files = LocalWorkspaceFiles.current ?: return
    AssistChip(
        onClick = { files.opener.open(request) },
        label = { Text(chipLabel(request), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
        },
        modifier = modifier.testTag(WorkspaceFileLinkTags.LINK),
    )
}

/** "Main.kt", or with the lines it names "Main.kt #L3" / "Main.kt #L3-4". */
internal fun chipLabel(request: WorkspaceFileRequest): String {
    val name = WorkspacePaths.fileName(request.path)
    val lines = request.lines ?: return name
    val span = if (lines.first == lines.last) "L${lines.first}" else "L${lines.first}-${lines.last}"
    return "$name #$span"
}

/**
 * [text] with its URLs and file paths as links: a URL opens in the browser, a path (with an
 * optional `:line`) in the file preview. Only a step's kept window is linked, never a whole output.
 */
@Composable
fun rememberWorkspaceLinkedText(text: String): AnnotatedString {
    val files = LocalWorkspaceFiles.current
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, files, linkColor) {
        val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            var at = 0
            for (match in OutputLinks.find(text)) {
                append(text, at, match.range.first)
                val link = outputLink(match, files?.opener, styles)
                if (link == null) append(match.value) else withLink(link) { append(match.value) }
                at = match.range.last + 1
            }
            append(text, at, text.length)
        }
    }
}

private fun outputLink(
    match: OutputLinks.Match,
    opener: WorkspaceFileOpener?,
    styles: TextLinkStyles,
): LinkAnnotation? = when (match) {
    is OutputLinks.Match.Url -> LinkAnnotation.Url(match.value, styles)
    is OutputLinks.Match.Path -> opener?.let { files ->
        LinkAnnotation.Clickable(match.value, styles) { files.open(WorkspaceFileRequest(match.path, lines = match.line?.let { it..it })) }
    }
}

/** A tool step's file or memory preview target as its chip; nothing for any other target. */
@Composable
fun ToolPreviewFileChip(target: ToolPreviewTarget) {
    when (target) {
        is ToolPreviewTarget.File -> WorkspaceFileChip(WorkspaceFileRequest(target.path, lines = target.lines))
        is ToolPreviewTarget.Memory -> WorkspaceFileChip(WorkspaceFileRequest(target.path, memoryTool = true))
        is ToolPreviewTarget.Subagent, is ToolPreviewTarget.TaskOutput -> Unit
    }
}
