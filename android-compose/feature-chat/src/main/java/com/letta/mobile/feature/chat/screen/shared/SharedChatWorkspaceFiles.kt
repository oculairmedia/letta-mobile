package com.letta.mobile.feature.chat.screen.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.workspace.WorkspaceFileOpener
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.shell.pages.workspace.WorkspaceFileViewer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * letta-mobile-bzvro.37: the chat page's workspace files, or null in a host without Hilt
 * (previews and tests), which then shows no file links and no `@` file suggestions.
 */
@Composable
internal fun rememberSharedChatWorkspaceFiles(): WorkspaceFilesViewModel? {
    val activity = LocalContext.current as? android.app.Activity
    if (activity !is dagger.hilt.internal.GeneratedComponentManager<*>) return null
    return hiltViewModel()
}

/** What the tool cards' file links open: the shared read-only viewer. */
@Composable
internal fun rememberWorkspaceFileOpener(files: WorkspaceFilesViewModel?): WorkspaceFileOpener? =
    remember(files) { files?.let { WorkspaceFileOpener { path -> it.viewer.open(path, cwd = null) } } }

/** Searches the workspace as the composer's draft changes, and shows the viewer over the page. */
@Composable
internal fun SharedChatWorkspaceFiles(files: WorkspaceFilesViewModel?, port: ChatSessionPort) {
    files ?: return
    LaunchedEffect(files, port) {
        port.composer.map { it.text }.distinctUntilChanged().collect(files::onDraftChanged)
    }
    val viewer by files.viewer.state.collectAsStateWithLifecycle()
    WorkspaceFileViewer(state = viewer, actions = files.viewer)
}
