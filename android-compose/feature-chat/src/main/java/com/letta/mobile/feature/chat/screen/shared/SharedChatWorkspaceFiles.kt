package com.letta.mobile.feature.chat.screen.shared

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import dagger.hilt.internal.GeneratedComponentManager
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.feature.chat.screen.LocalAndroidClientCommands
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.shell.pages.workspace.LocalWorkspaceFiles
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The page's session over [viewModel], in the page's scope (which ends on rotation), with a Hilt host's workspace files
 * (letta-mobile-bzvro.37); previews and tests have none, and show no file links and no `@` file suggestions.
 */
@Composable
internal fun rememberAdminChatPageSession(viewModel: AdminChatViewModel, navigation: ChatScreenNavigationCallbacks): SharedChatPageSession {
    val files: WorkspaceFilesViewModel? = if (LocalContext.current.findActivity() is GeneratedComponentManager<*>) hiltViewModel() else null
    val commands = LocalAndroidClientCommands.current
    val currentNavigation by rememberUpdatedState(navigation)
    val currentCommands by rememberUpdatedState(commands)
    val canOpen = navigation.onOpenConversation != null
    val scope = rememberCoroutineScope()
    val host = remember(viewModel, scope, files) { AdminChatPageBindings.Host(viewModel, scope, files) }
    val port = remember(host, canOpen) { AdminChatPageBindings.sessionPort(host, { currentNavigation }, canOpen) }
    val page = remember(port, commands?.offered) {
        AdminChatPageBindings.create(host, port, { currentCommands }, commands?.offered)
    }
    return page.session(viewModel)
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
    files: ChatWorkspaceFiles?,
    port: ChatSessionPort,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalWorkspaceFiles provides files?.workspace) {
        Box(modifier) {
            content()
            if (files != null) DraftFileSearch(files, port)
        }
    }
}

@Composable
private fun DraftFileSearch(files: ChatWorkspaceFiles, port: ChatSessionPort) {
    LaunchedEffect(files, port) {
        port.composer.map { it.text }.distinctUntilChanged().collect(files.onDraftChanged)
    }
}
