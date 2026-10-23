package com.letta.mobile.feature.chat.screen.shared

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.letta.mobile.data.workspace.WorkspaceFileOpener
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.shell.pages.workspace.LocalWorkspaceFiles
import com.letta.mobile.ui.shell.pages.workspace.WorkspaceFiles
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * letta-mobile-bzvro.37: the chat page's workspace files, or null in a host without Hilt
 * (previews and tests), which then shows no file links and no `@` file suggestions.
 */
@Composable
internal fun rememberSharedChatWorkspaceFiles(): WorkspaceFilesViewModel? {
    val activity = LocalContext.current.findActivity()
    if (activity !is dagger.hilt.internal.GeneratedComponentManager<*>) return null
    return hiltViewModel()
}

/** The Activity under a ContextWrapper chain (themed, dialog and lifecycle wrappers hide it). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The page's box with workspace files wired in: tool steps' file chips and paths open the shared
 * read-only viewer the chat draws, and the composer's draft drives the `@` file search.
 */
@Composable
internal fun SharedChatWorkspaceFilesBox(
    files: WorkspaceFilesViewModel?,
    port: ChatSessionPort,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // The phone knows no working directory: a relative path cannot be opened (a memory tool's
    // entries open in the Memory view instead).
    val workspace = remember(files) {
        files?.let {
            val opener = WorkspaceFileOpener { request ->
                it.viewer.open(request.path, cwd = null, lines = request.lines)
            }
            WorkspaceFiles(opener, it.viewer.state, it.viewer)
        }
    }
    CompositionLocalProvider(LocalWorkspaceFiles provides workspace) {
        Box(modifier) {
            content()
            if (files != null) DraftFileSearch(files, port)
        }
    }
}

@Composable
private fun DraftFileSearch(files: WorkspaceFilesViewModel, port: ChatSessionPort) {
    LaunchedEffect(files, port) {
        port.composer.map { it.text }.distinctUntilChanged().collect(files::onDraftChanged)
    }
}
