package com.letta.mobile.desktop.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.workspace.AppServerWorkspaceFileSource
import com.letta.mobile.data.workspace.FileMentionController
import com.letta.mobile.data.workspace.MentionDraft
import com.letta.mobile.data.workspace.WorkspaceFileOpener
import com.letta.mobile.data.workspace.WorkspaceFileSource
import com.letta.mobile.data.workspace.WorkspaceFileViewerController
import com.letta.mobile.ui.shell.pages.workspace.LocalWorkspaceFileOpener
import com.letta.mobile.ui.shell.pages.workspace.WorkspaceFileViewer
import kotlinx.coroutines.CoroutineScope

/** letta-mobile-bzvro.26: workspace files on the desktop's direct App Server session. */
internal fun DesktopWorkspaceSources.files(): WorkspaceFileSource =
    AppServerWorkspaceFileSource(client = ::client, requestId = ::requestId)

/**
 * Lets tool cards in [content] open the files they name, and shows the read-only viewer over it.
 * Relative paths resolve against [workingDirectory], the selected conversation's cwd.
 */
@Composable
internal fun DesktopWorkspaceFileViewerHost(
    scope: CoroutineScope,
    workingDirectory: String?,
    content: @Composable () -> Unit,
) {
    val viewer = remember(scope) { WorkspaceFileViewerController(DesktopWorkspaceSources().files(), scope) }
    DisposableEffect(viewer) { onDispose { viewer.close() } }
    val opener = remember(viewer, workingDirectory) { WorkspaceFileOpener { path, _ -> viewer.open(path, workingDirectory) } }
    CompositionLocalProvider(LocalWorkspaceFileOpener provides opener) { content() }
    val state by viewer.state.collectAsState()
    WorkspaceFileViewer(state = state, actions = viewer)
}

/**
 * The `@` file suggestions for the composer's [draft], searched within [workingDirectory]; the
 * shell merges them into the composer's mentionables, so the composer itself is unchanged.
 */
@Composable
internal fun rememberDesktopFileMentions(scope: CoroutineScope, draft: String, workingDirectory: String?): List<Mentionable> {
    val mentions = remember(scope) { FileMentionController(DesktopWorkspaceSources().files(), scope) }
    DisposableEffect(mentions) { onDispose { mentions.close() } }
    LaunchedEffect(mentions, draft, workingDirectory) { mentions.onDraftChanged(MentionDraft(draft, workingDirectory)) }
    val state by mentions.state.collectAsState()
    return state.results
}
