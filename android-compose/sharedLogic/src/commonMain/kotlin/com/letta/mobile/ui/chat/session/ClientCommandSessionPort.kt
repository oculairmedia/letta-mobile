package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.chat.recompile.ConversationRecompileController
import com.letta.mobile.data.chat.recompile.RecompileStatus
import com.letta.mobile.data.commands.ClientSlashCommand
import com.letta.mobile.data.commands.ClientSlashCommands
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**  (G92): which client-local commands a host offers, and where each one goes. */
class ClientCommandRouting(
    val offered: Set<ClientSlashCommand>,
    val run: (ClientSlashCommand) -> Unit,
    /** The host's `/recompile` runner, whose status the page shows; null when it has none. */
    val recompile: ConversationRecompileController? = null,
)

/**
 *  (G92): adds the client-local `/` commands to an owner's [ChatSessionPort]
 * without changing the owner: the composer lists them before the owner's own commands
 * ([ClientSlashCommands.composerCommands]), choosing one runs the host's route instead of the
 * owner's, and `/recompile`'s status comes from [ClientCommandRouting.recompile].
 */
class ClientCommandSessionPort(
    private val delegate: ChatSessionPort,
    private val routing: ClientCommandRouting,
    scope: CoroutineScope,
) : ChatSessionPort by delegate {
    override val composer: StateFlow<ChatComposerUiState> = delegate.composer
        .map(::withClientCommands)
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), withClientCommands(delegate.composer.value))

    override val recompileStatus: StateFlow<RecompileStatus> = routing.recompile?.status ?: delegate.recompileStatus

    override val actions: ChatActions = ClientCommandActions(delegate.actions, routing)

    private fun withClientCommands(composer: ChatComposerUiState): ChatComposerUiState =
        composer.copy(commands = ClientSlashCommands.composerCommands(routing.offered, composer.commands).toImmutableList())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The owner's actions, with an offered client command routed to the host instead. */
private class ClientCommandActions(
    private val delegate: ChatActions,
    private val routing: ClientCommandRouting,
) : ChatActions by delegate {
    override fun runComposerCommand(command: ChatComposerCommand) {
        val client = ClientSlashCommands.resolve(command)?.takeIf { it in routing.offered }
        if (client != null) routing.run(client) else delegate.runComposerCommand(command)
    }

    override fun dismissRecompileStatus() {
        routing.recompile?.dismiss() ?: delegate.dismissRecompileStatus()
    }
}
