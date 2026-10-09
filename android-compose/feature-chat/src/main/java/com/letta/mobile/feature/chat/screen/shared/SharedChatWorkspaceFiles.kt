package com.letta.mobile.feature.chat.screen.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.workspace.WorkspaceFileOpener
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.shell.pages.workspace.LocalWorkspaceFileOpener
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

/**
 * The page's box with workspace files wired in: tool cards' file links open the shared read-only
 * viewer drawn over [content], and the composer's draft drives the `@` file search.
 */
@Composable
internal fun SharedChatWorkspaceFilesBox(
    files: WorkspaceFilesViewModel?,
    port: ChatSessionPort,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val opener = remember(files) { files?.let { WorkspaceFileOpener { path -> it.viewer.open(path, cwd = null) } } }
    CompositionLocalProvider(LocalWorkspaceFileOpener provides opener) {
        Box(modifier) {
            content()
            if (files != null) WorkspaceFiles(files, port)
        }
    }
}

@Composable
private fun WorkspaceFiles(files: WorkspaceFilesViewModel, port: ChatSessionPort) {
    LaunchedEffect(files, port) {
        port.composer.map { it.text }.distinctUntilChanged().collect(files::onDraftChanged)
    }
    val viewer by files.viewer.state.collectAsStateWithLifecycle()
    WorkspaceFileViewer(state = viewer, actions = files.viewer)
}
