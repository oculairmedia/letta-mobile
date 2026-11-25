package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.coordination.ChatSlashCommand
import com.letta.mobile.feature.chat.coordination.ChatSlashCommandParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
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
