package com.letta.mobile.ui.shell.pages.workspace

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
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
