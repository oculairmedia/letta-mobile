package com.letta.mobile.ui.chat.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

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
    fun payloadIsTextOrImages() {
        assertFalse(ChatComposerUiState(text = "   ").hasPayload)
        assertTrue(ChatComposerUiState(text = "hi").hasPayload)
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
}
