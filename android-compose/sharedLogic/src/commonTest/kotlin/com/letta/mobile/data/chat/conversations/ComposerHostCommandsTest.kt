package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.ui.chat.session.ChatHostCommand

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-ckmub.17.15: the composer `/` palette every host builds. */
class ComposerHostCommandsTest {
    @Test
    fun aHostOffersOnlyTheClientCommandsItRoutesThenForkPagesAndServerCommands() {
        val ran = mutableListOf<String>()
        val filled = mutableListOf<String>()
        val commands = composerHostCommands(
            ComposerHostCommandSources(
                client = ClientCommandRoutes(search = { ran += "search" }, recompile = { ran += "recompile" }),
                fillComposer = { filled += it },
                canFork = true,
                pages = listOf(ChatHostCommand("settings", "Open settings") { ran += "settings" }),
                server = listOf(
                    AgentSlashCommand(rawCommand = "/goal", description = "Goal mode"),
                    AgentSlashCommand(rawCommand = "/review", skillName = "review"),
                    // Named like a client command: it would shadow /search, so it is left out.
                    AgentSlashCommand(rawCommand = "/search", description = "Server search"),
                    // So would one named like a page command or /fork.
                    AgentSlashCommand(rawCommand = "/settings", skillName = "settings"),
                    AgentSlashCommand(rawCommand = "/fork"),
                ),
            ),
        )

        assertEquals(listOf("search", "recompile", "fork", "settings", "goal", "review"), commands.map { it.label })
        assertEquals("Skill: review", commands.single { it.label == "review" }.description)
        commands.single { it.label == "recompile" }.run()
        commands.single { it.label == "fork" }.run()
        commands.single { it.label == "goal" }.run()
        assertEquals(listOf("recompile"), ran)
        assertEquals(listOf("/fork ", "/goal "), filled)
        assertTrue(commands.single { it.label == "goal" }.fillsComposer)
    }

    @Test
    fun noForkWhereTheBackendCannotBranch() {
        val commands = composerHostCommands(ComposerHostCommandSources(client = ClientCommandRoutes(), fillComposer = {}))

        assertTrue(commands.isEmpty())
    }
}
