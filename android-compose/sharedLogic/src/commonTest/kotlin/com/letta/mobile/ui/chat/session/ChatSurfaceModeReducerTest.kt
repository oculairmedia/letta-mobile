package com.letta.mobile.ui.chat.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ChatSurfaceModeReducerTest {
    private val docked = ChatSurfacePresentation.CanvasFirst
    private val fullScreen = ChatSurfacePresentation.ChatFirst
    private val floatingAllowed = docked.copy(floatingEnabled = true)

    private fun ChatSurfacePresentation.after(vararg intents: ChatSurfaceIntent): ChatSurfacePresentation =
        intents.fold(this) { state, intent -> ChatSurfaceModeReducer.reduce(state, intent) }

    @Test
    fun canvasIsTheDefaultView() {
        val initial = ChatSurfacePresentation()
        assertEquals(ChatSurfaceMode.Docked, initial.mode)
        assertTrue(initial.canvasVisible)
    }

    @Test
    fun expandingTheDockOpensFullScreenChatAndHidesTheCanvas() {
        val next = docked.after(ChatSurfaceIntent.Expand)
        assertEquals(ChatSurfaceMode.FullScreen, next.mode)
        assertFalse(next.canvasVisible)
    }

    @Test
    fun swipeUpOnTheFullScreenPromptOpensTheCanvasKeepingTheShippedDirection() {
        // #1614: swipe up on the full-screen prompt card goes chat -> canvas, never the reverse.
        assertEquals(ChatSurfaceMode.Docked, fullScreen.after(ChatSurfaceIntent.OpenCanvas).mode)
        assertSame(docked, docked.after(ChatSurfaceIntent.OpenCanvas))
    }

    @Test
    fun collapseIsTheExplicitReturnFromFullScreen() {
        assertEquals(ChatSurfaceMode.Docked, fullScreen.after(ChatSurfaceIntent.Collapse).mode)
    }

    @Test
    fun floatingNeedsTheFlagAndAnExplicitIntent() {
        assertSame(docked, docked.after(ChatSurfaceIntent.OpenFloating))
        assertEquals(ChatSurfaceMode.Floating, floatingAllowed.after(ChatSurfaceIntent.OpenFloating).mode)
        // Never from full screen: floating sits over the canvas.
        val fullAllowed = fullScreen.copy(floatingEnabled = true)
        assertSame(fullAllowed, fullAllowed.after(ChatSurfaceIntent.OpenFloating))
    }

    @Test
    fun dismissingTheFloatingPanelCollapsesToTheDock() {
        val floating = floatingAllowed.after(ChatSurfaceIntent.OpenFloating)
        assertEquals(ChatSurfaceMode.Docked, floating.after(ChatSurfaceIntent.DismissFloating).mode)
        assertEquals(ChatSurfaceMode.FullScreen, floating.after(ChatSurfaceIntent.Expand).mode)
    }

    @Test
    fun inapplicableIntentsReturnTheSameInstance() {
        assertSame(fullScreen, fullScreen.after(ChatSurfaceIntent.Expand))
        assertSame(docked, docked.after(ChatSurfaceIntent.DismissFloating))
        assertSame(docked, docked.after(ChatSurfaceIntent.Collapse))
    }

    @Test
    fun aRoundTripReturnsToTheStartingMode() {
        assertEquals(docked, docked.after(ChatSurfaceIntent.Expand, ChatSurfaceIntent.OpenCanvas))
        assertEquals(fullScreen, fullScreen.after(ChatSurfaceIntent.Collapse, ChatSurfaceIntent.Expand))
    }

    @Test
    fun presentationMatchesItsSerializedFixture() {
        val json = Json { encodeDefaults = true }
        val fixture = """{"mode":"full_screen","floatingEnabled":false}"""
        assertEquals(fixture, json.encodeToString(ChatSurfacePresentation.serializer(), fullScreen))
        assertEquals(fullScreen, json.decodeFromString(ChatSurfacePresentation.serializer(), fixture))
        assertEquals(
            floatingAllowed,
            json.decodeFromString(ChatSurfacePresentation.serializer(), """{"mode":"docked","floatingEnabled":true}"""),
        )
    }

    @Test
    fun intentsMatchTheirSerializedFixtures() {
        val json = Json
        val fixtures = mapOf(
            ChatSurfaceIntent.Expand to """{"type":"expand"}""",
            ChatSurfaceIntent.OpenCanvas to """{"type":"open_canvas"}""",
            ChatSurfaceIntent.Collapse to """{"type":"collapse"}""",
            ChatSurfaceIntent.OpenFloating to """{"type":"float"}""",
            ChatSurfaceIntent.DismissFloating to """{"type":"dismiss_floating"}""",
        )
        fixtures.forEach { (intent, fixture) ->
            assertEquals(fixture, json.encodeToString(ChatSurfaceIntent.serializer(), intent))
            assertEquals(intent, json.decodeFromString(ChatSurfaceIntent.serializer(), fixture))
        }
    }
}
