package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.chat.recompile.ConversationRecompileController
import com.letta.mobile.data.chat.recompile.ConversationRecompileRequest
import com.letta.mobile.data.chat.recompile.RecompileStatus
import com.letta.mobile.data.commands.ClientSlashCommand
import com.letta.mobile.data.commands.ClientSlashCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** letta-mobile-zxk2n.5 (G92): client-local commands join an owner's `/` list and route to the host. */
class ClientCommandSessionPortTest {
    private val serverCompact = ChatComposerCommand(id = "/compact", label = "/compact", fillsComposer = true)

    @Test
    fun offeredCommandsLeadTheListAndRouteToTheHost() = runTest {
        val owner = FakeChatSessionPort().apply { setCommands(listOf(serverCompact)) }
        val routed = mutableListOf<ClientSlashCommand>()
        val port = ClientCommandSessionPort(owner, ClientCommandRouting(setOf(ClientSlashCommand.Memory), routed::add), backgroundScope)

        assertEquals(listOf("memory", "/compact"), port.composer.value.commands.map { it.id })

        port.actions.runComposerCommand(ClientSlashCommands.toComposerCommand(ClientSlashCommand.Memory))
        port.actions.runComposerCommand(serverCompact)
        // A client command the host does not offer stays with the owner.
        port.actions.runComposerCommand(ClientSlashCommands.toComposerCommand(ClientSlashCommand.Connect))

        assertEquals(listOf(ClientSlashCommand.Memory), routed)
        assertEquals(listOf("/compact", "connect"), owner.commandsRun.map { it.id })
    }

    @Test
    fun recompileStatusComesFromTheHostsRunner() = runTest {
        val recompile = ConversationRecompileController(backgroundScope, { null }, { "not here" })
        val port = ClientCommandSessionPort(
            FakeChatSessionPort(),
            ClientCommandRouting(setOf(ClientSlashCommand.Recompile), { recompile.recompile(ConversationRecompileRequest("c", "a")) }, recompile),
            backgroundScope,
        )

        port.actions.runComposerCommand(ClientSlashCommands.toComposerCommand(ClientSlashCommand.Recompile))
        assertEquals(RecompileStatus.Unavailable("not here"), port.recompileStatus.value)
        port.actions.dismissRecompileStatus()
        assertEquals(RecompileStatus.Idle, port.recompileStatus.value)
    }
}
