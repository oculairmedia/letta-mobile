package com.letta.mobile.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSlashCommandParserTest {
    @Test
    fun `bug command with project context parses`() {
        assertEquals(
            ChatSlashCommand.Bug,
            ChatSlashCommandParser.parse("/bug", projectContextAvailable = true),
        )
    }

    @Test
    fun `bug command trims whitespace`() {
        assertEquals(
            ChatSlashCommand.Bug,
            ChatSlashCommandParser.parse("  /bug  ", projectContextAvailable = true),
        )
    }

    @Test
    fun `bug command without project context does not parse`() {
        assertNull(ChatSlashCommandParser.parse("/bug", projectContextAvailable = false))
    }

    @Test
    fun `uppercase bug command preserves exact-match behavior`() {
        assertNull(ChatSlashCommandParser.parse("/BUG", projectContextAvailable = true))
    }

    @Test
    fun `normal text does not parse`() {
        assertNull(ChatSlashCommandParser.parse("hello", projectContextAvailable = true))
    }
}
