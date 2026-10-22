package com.letta.mobile.ui.shell.pages.workspace

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.workspace.WorkspaceFileOpener
import com.letta.mobile.data.workspace.WorkspacePaths

/**
 * How a host opens workspace files from inside the timeline. Null, the
 * default, means the host cannot read files, and tool cards show no file link.
 */
val LocalWorkspaceFileOpener = staticCompositionLocalOf<WorkspaceFileOpener?> { null }

/** Test tags for the tool card's file link. */
object WorkspaceFileLinkTags {
    const val LINK = "workspace_file_link"
}

/**
 * What a tool step's chip opens, as a chip; nothing when the host provides no
 * [LocalWorkspaceFileOpener] or the target is not a file or memory entry it can show.
 */
@Composable
fun ToolCallFileLink(target: ToolPreviewTarget, modifier: Modifier = Modifier) {
    val opener = LocalWorkspaceFileOpener.current ?: return
    val (path, memory) = when (target) {
        is ToolPreviewTarget.File -> target.path to false
        is ToolPreviewTarget.Memory -> target.path to true
        is ToolPreviewTarget.Subagent, is ToolPreviewTarget.TaskOutput -> return
    }
    AssistChip(
        onClick = { opener.open(path, memoryTool = memory) },
        label = { Text(WorkspacePaths.fileName(path), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
        },
        modifier = modifier.testTag(WorkspaceFileLinkTags.LINK),
    )
}
