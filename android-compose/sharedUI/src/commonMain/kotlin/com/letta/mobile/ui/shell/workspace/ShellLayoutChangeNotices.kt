package com.letta.mobile.ui.shell.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.edit.ShellLayoutAgentPort
import com.letta.mobile.data.shell.layout.edit.ShellLayoutAuthor
import com.letta.mobile.data.shell.layout.edit.ShellLayoutChange
import com.letta.mobile.ui.host.LocalOverlayHost
import com.letta.mobile.ui.host.Overlay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onSubscription

/**
 * Tells the person when the shell layout changes through [LocalShellLayoutAgentPort] (an agent's
 * tool call, or Reset layout): a notice naming who changed it, with Undo, which puts back the
 * layout the change replaced. Reading the layout, or checking an edit with dry_run, shows nothing.
 *
 * @param agents the agents the rail knows, id to name, so a notice can name the agent.
 */
@Composable
internal fun ShellLayoutChangeNotices(agents: List<Pair<String, String>>) {
    val port = LocalShellLayoutAgentPort.current
    val overlays = checkNotNull(LocalOverlayHost.current) { "Shell layout notices require an overlay host" }
    val names by rememberUpdatedState(agents)
    LaunchedEffect(port, overlays) {
        port.changes.collect { change -> overlays.show(shellLayoutChangeNotice(change, names, port)) }
    }
}

/** The notice for [change]: Undo when it can be undone, a plain line when it was itself an undo. */
internal fun shellLayoutChangeNotice(change: ShellLayoutChange, agents: List<Pair<String, String>>, port: ShellLayoutAgentPort): Overlay {
    val message = shellLayoutChangeMessage(change, agents)
    return if (change.undoable) {
        Overlay.undo(message, key = SHELL_LAYOUT_NOTICE_KEY, onUndo = { port.undo(change) }, onCommit = {})
    } else {
        Overlay.Snackbar(message, key = SHELL_LAYOUT_NOTICE_KEY)
    }
}

/** What the notice says: who changed the layout (the agent's name when the rail knows it). */
internal fun shellLayoutChangeMessage(change: ShellLayoutChange, agents: List<Pair<String, String>>): String =
    when (val author = change.author) {
        is ShellLayoutAuthor.Agent -> {
            val name = author.agent?.let { id -> agents.firstOrNull { it.first == id.value }?.second } ?: "an agent"
            if (change.undoable) "Layout changed by $name" else "Layout change undone by $name"
        }
        ShellLayoutAuthor.Person -> "Layout reset to default"
    }

/**
 * The Settings page's Reset layout: puts the attached shell back to the default layout (undoable),
 * or null while no shell is attached or its layout already is the default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun rememberShellLayoutReset(): (() -> Unit)? {
    val port = LocalShellLayoutAgentPort.current
    val layouts = remember(port) { port.attached.flatMapLatest { store -> store?.config ?: flowOf(null) } }
    val layout by layouts.collectAsState(port.attached.value?.config?.value)
    val reset = remember(port) { { port.reset(ShellLayoutAuthor.Person); Unit } }
    return reset.takeIf { layout != null && layout != ShellLayoutConfig.Default }
}

private const val SHELL_LAYOUT_NOTICE_KEY = "shell-layout-change"
