package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.letta.mobile.data.chat.recompile.ConversationRecompileGateway
import com.letta.mobile.data.chat.recompile.ConversationRecompileRequest
import com.letta.mobile.data.commands.ClientSlashCommand
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.ui.shell.sidebar.ShellRenameChatDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * letta-mobile-zxk2n.5 (G92): the client-local `/` commands the Android chat can route, and where
 * each goes. [recompileGateway] is null where `/recompile` has no route (Iroh: the host needs a
 * relay), and the chat then says so instead of sending it.
 */
internal data class AndroidClientCommands(
    val offered: Set<ClientSlashCommand>,
    val route: (ClientSlashCommand) -> Unit,
    val recompileGateway: ConversationRecompileGateway?,
)

/** The scaffold's client commands for the shared chat page; null outside a scaffold (tests, previews). */
internal val LocalAndroidClientCommands = compositionLocalOf<AndroidClientCommands?> { null }

/** `/recompile` through the conversation repository (REST `POST /v1/conversations/{id}/recompile`). */
internal class RepositoryRecompileGateway(private val repository: IConversationRepository) : ConversationRecompileGateway {
    override suspend fun recompileConversation(request: ConversationRecompileRequest) {
        repository.recompileConversation(
            ConversationId(request.conversationId),
            dryRun = false,
            agentId = AgentId(request.agentId).takeIf { request.agentId.isNotBlank() },
        )
    }
}

/**
 * The scaffold's routes: search expands the chat search, memory / MCP / connect open their screens,
 * models the model sheet, new a fresh chat, rename a dialog. A command whose screen this host has
 * no route to is not offered.
 */
@Composable
internal fun rememberAndroidClientCommands(state: AgentScaffoldRuntimeState, onRename: () -> Unit): AndroidClientCommands {
    val current by rememberUpdatedState(state)
    val drawer = LocalSharedNavDrawer.current
    val navigation = state.params.navigation
    val offered = remember(navigation) { offeredClientCommands(navigation) }
    val iroh = drawer?.backendIsIroh == true
    return remember(offered, iroh, state.drawerConversationRepo) {
        AndroidClientCommands(
            offered = offered,
            route = { command -> routeClientCommand(current, command, onRename) },
            recompileGateway = if (iroh) null else RepositoryRecompileGateway(state.drawerConversationRepo),
        )
    }
}

private fun offeredClientCommands(navigation: AgentScaffoldNavigationCallbacks): Set<ClientSlashCommand> = buildSet {
    add(ClientSlashCommand.Search)
    add(ClientSlashCommand.Models)
    add(ClientSlashCommand.Rename)
    add(ClientSlashCommand.Recompile)
    if (navigation.onNavigateToMemory != null) add(ClientSlashCommand.Memory)
    if (navigation.onNavigateToTools != null) add(ClientSlashCommand.Mcp)
    if (navigation.onSwitchConversation != null) add(ClientSlashCommand.New)
    if (navigation.onNavigateToProviders != null) add(ClientSlashCommand.Connect)
}

private fun routeClientCommand(state: AgentScaffoldRuntimeState, command: ClientSlashCommand, onRename: () -> Unit) {
    val params = state.params
    val navigation = params.navigation
    val agentId = state.agentIdValue
    when (command) {
        ClientSlashCommand.Search -> params.searchUi.onOpenSearch()
        ClientSlashCommand.Memory -> navigation.onNavigateToMemory?.invoke(agentId)
        // The tools screen is where Android manages tools and MCP servers.
        ClientSlashCommand.Mcp -> navigation.onNavigateToTools?.invoke()
        ClientSlashCommand.Models -> params.sheetVisibility.onShowModelPickerChange(true)
        ClientSlashCommand.New -> navigation.onSwitchConversation?.invoke(agentId, null, state.agentName.takeIf { it.isNotBlank() })
        ClientSlashCommand.Rename -> onRename()
        ClientSlashCommand.Connect -> navigation.onNavigateToProviders?.invoke()
        // The shared page runs /recompile itself (it owns the status); nothing to route.
        ClientSlashCommand.Recompile -> Unit
    }
}

/** `/rename`'s dialog for the open chat, written through the scaffold's conversation repository. */
@Composable
internal fun AndroidRenameChatDialog(state: AgentScaffoldRuntimeState, onDismiss: () -> Unit) {
    val conversationId = state.conversationId
    val conversation = state.drawerConversations.firstOrNull { it.id.value == conversationId }
    if (conversationId == null || conversation == null) {
        onDismiss()
        return
    }
    ShellRenameChatDialog(
        title = conversation.summary.orEmpty(),
        onRename = { title ->
            state.scope.launch {
                try {
                    state.drawerConversationRepo.updateConversation(ConversationId(conversationId), AgentId(state.agentIdValue), title)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The row keeps its old title; the drawer re-reads it on its next refresh.
                }
            }
        },
        onDismiss = onDismiss,
    )
}

/** Whether `/rename`'s dialog is open; the scaffold's chat pane holds it. */
@Composable
internal fun rememberRenameDialogState() = remember { RenameDialogState() }

internal class RenameDialogState {
    var open by mutableStateOf(false)
}
