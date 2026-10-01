package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.model.SlashCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.Json

class ChatSessionPortContractTest {
    @Test
    fun theDraftSurvivesEveryModeTransitionBecauseItLivesWithThePort() {
        val port = FakeChatSessionPort()
        var presentation = ChatSurfacePresentation.CanvasFirst

        port.actions.updateComposerText("draft from the dock")
        presentation = ChatSurfaceModeReducer.reduce(presentation, ChatSurfaceIntent.Expand)
        assertEquals("draft from the dock", port.composer.value.text)

        port.actions.updateComposerText("edited full screen")
        presentation = ChatSurfaceModeReducer.reduce(presentation, ChatSurfaceIntent.OpenCanvas)
        assertEquals(ChatSurfaceMode.Docked, presentation.mode)
        assertEquals("edited full screen", port.composer.value.text)
    }

    @Test
    fun sendingDoesNotChangeTheMode() {
        val port = FakeChatSessionPort()
        val presentation = ChatSurfacePresentation.CanvasFirst.copy(floatingEnabled = true)
        port.actions.updateComposerText("hello")
        port.actions.send()
        // No intent exists for a send: the presentation is untouched and the draft is consumed.
        assertEquals(ChatSurfaceMode.Docked, presentation.mode)
        assertEquals(listOf("hello"), port.sent)
        assertEquals("", port.composer.value.text)
    }

    @Test
    fun payloadIsTextOrImages() {
        assertFalse(ChatComposerUiState(text = "   ").hasPayload)
        assertTrue(ChatComposerUiState(text = "hi").hasPayload)
    }

    @Test
    fun serverSlashCommandsFillTheComposer() {
        val command = ChatComposerCommand.fromSlashCommand(
            Json.decodeFromString(SlashCommand.serializer(), SLASH_COMMAND_FIXTURE),
        )
        assertEquals(
            ChatComposerCommand(
                id = "/review",
                label = "/review",
                description = "Review the diff",
                fillsComposer = true,
                removable = true,
            ),
            command,
        )
    }

    @Test
    fun defaultCapabilitiesHideHostSpecificControls() {
        val caps = FakeChatSessionPort().capabilities.value
        assertFalse(caps.workingDirectory)
        assertFalse(caps.search)
        assertTrue(caps.attachImages)
    }

    @Test
    fun composerStateCarriesCommandsInOrder() {
        val commands = persistentListOf(
            ChatComposerCommand(id = "canvas", label = "/canvas"),
            ChatComposerCommand(id = "/review", label = "/review", fillsComposer = true),
        )
        val state = ChatComposerUiState(commands = commands)
        assertEquals(listOf("canvas", "/review"), state.commands.map { it.id })
    }

    private companion object {
        const val SLASH_COMMAND_FIXTURE =
            """{"name":"review","command":"/review","description":"Review the diff","source":"skill","installed":true}"""
    }
}
