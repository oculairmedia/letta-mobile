package com.letta.mobile.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.shell.ShellQuitGuard
import kotlinx.coroutines.launch

/**
 * The host's Quit action, guarded by [ShellQuitGuard]: it saves what quitting would lose through
 * [prepare], then [exit]s, or calls [onRefused] (show the window) when something cannot be saved yet.
 * A second Quit after a refusal exits anyway.
 */
@Composable
fun rememberShellQuit(prepare: suspend () -> Boolean, exit: () -> Unit, onRefused: () -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentPrepare = rememberUpdatedState(prepare)
    val guard = remember { ShellQuitGuard { currentPrepare.value() } }
    val currentExit = rememberUpdatedState(exit)
    val currentRefused = rememberUpdatedState(onRefused)
    return remember(guard) {
        { scope.launch { if (guard.requestQuit()) currentExit.value() else currentRefused.value() } }
    }
}
