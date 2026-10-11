package com.letta.mobile.data.commands

import com.letta.mobile.ui.chat.session.ChatComposerCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** letta-mobile-zxk2n.5 (G92): client-local commands sit beside the server's and route by name. */
class ClientSlashCommandsTest {
    private val serverCompact = ChatComposerCommand(id = "/compact", label = "/compact", fillsComposer = true)
    private val serverNew = ChatComposerCommand(id = "/new", label = "/new", fillsComposer = true)

    @Test
    fun offeredClientCommandsLeadInTheirOwnOrderAndShadowSameNamedServerCommands() {
        val list = ClientSlashCommands.composerCommands(
            offered = setOf(ClientSlashCommand.Recompile, ClientSlashCommand.New),
            server = listOf(serverCompact, serverNew),
        )

        assertEquals(listOf("new", "recompile", "/compact"), list.map { it.id })
        assertFalse(list.first().fillsComposer, "a client command runs instead of being sent")
    }

    @Test
    fun aChosenEntryResolvesToItsClientCommandOnlyWhenItIsAnAction() {
        assertEquals(ClientSlashCommand.Memory, ClientSlashCommands.resolve(ClientSlashCommands.toComposerCommand(ClientSlashCommand.Memory)))
        assertNull(ClientSlashCommands.resolve(serverCompact))
        assertNull(ClientSlashCommands.resolve(ChatComposerCommand(id = "/memory", label = "/memory", fillsComposer = true)))
    }

    @Test
    fun idsMatchWithOrWithoutTheSlash() {
        assertEquals(ClientSlashCommand.Connect, ClientSlashCommand.fromId("/Connect"))
        assertEquals(ClientSlashCommand.Search, ClientSlashCommand.fromId("search"))
        assertNull(ClientSlashCommand.fromId("compact"))
    }
}
