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
import com.letta.mobile.ui.shell.pages.workspace.LocalWorkspaceFiles
import com.letta.mobile.ui.shell.pages.workspace.WorkspaceFiles
import kotlinx.coroutines.CoroutineScope

/** letta-mobile-bzvro.26: workspace files on the desktop's direct App Server session. */
internal fun DesktopWorkspaceSources.files(): WorkspaceFileSource =
    AppServerWorkspaceFileSource(client = ::client, requestId = ::requestId)

/**
 * Lets the chat in [content] open the files its tool steps name, in the shared viewer it draws.
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
    val files = remember(viewer, workingDirectory) {
        WorkspaceFiles(WorkspaceFileOpener { viewer.open(it.path, workingDirectory, lines = it.lines) }, viewer.state, viewer)
    }
    CompositionLocalProvider(LocalWorkspaceFiles provides files) { content() }
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
