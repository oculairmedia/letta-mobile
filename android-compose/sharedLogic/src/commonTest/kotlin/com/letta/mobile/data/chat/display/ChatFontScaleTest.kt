package com.letta.mobile.data.chat.display

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class ChatFontScaleTest {
    @Test
    fun scrollUpScalesInAndScrollDownScalesOut() {
        assertTrue(ChatFontScale.next(ChatFontScale.DEFAULT, -1f) > ChatFontScale.DEFAULT)
        assertTrue(ChatFontScale.next(ChatFontScale.DEFAULT, 1f) < ChatFontScale.DEFAULT)
        assertEquals(1.23f, ChatFontScale.next(1.23f, 0f))
    }

    @Test
    fun theScaleIsClampedAtBothEnds() {
        var out = ChatFontScale.DEFAULT
        repeat(50) { out = ChatFontScale.next(out, 1f) }
        assertEquals(ChatFontScale.MIN, out)

        var into = ChatFontScale.DEFAULT
        repeat(50) { into = ChatFontScale.next(into, -1f) }
        assertEquals(ChatFontScale.MAX, into)
    }

    /** In and back out must land exactly on 1.0, not 0.9999998. */
    @Test
    fun aRoundTripReturnsToTheNeutralScale() {
        assertEquals(ChatFontScale.DEFAULT, ChatFontScale.next(ChatFontScale.next(ChatFontScale.DEFAULT, -1f), 1f))
    }

    @Test
    fun anyCandidateIsClampedAndSnapped() {
        assertEquals(ChatFontScale.MAX, ChatFontScale.snap(99f))
        assertEquals(ChatFontScale.MIN, ChatFontScale.snap(-4f))
        assertEquals(1.23f, ChatFontScale.snap(1.2345f))
        assertEquals(ChatFontScale.DEFAULT, ChatFontScale.snap(Float.NaN))
        assertEquals(ChatFontScale.DEFAULT, ChatFontScale.snap(Float.POSITIVE_INFINITY))
    }

    @Test
    fun theStoreRoundTripsAndReadsDamageAsTheDefault() {
        val settings = InMemorySecureSettingsStore()
        assertEquals(ChatFontScale.DEFAULT, ChatFontScaleStore(settings).load())

        ChatFontScaleStore(settings).save(1.4f)
        assertEquals(1.4f, ChatFontScaleStore(settings).load())

        settings.putString("chat.fontScale", "not-a-number")
        assertEquals(ChatFontScale.DEFAULT, ChatFontScaleStore(settings).load())
        settings.putString("chat.fontScale", "9")
        assertEquals(ChatFontScale.MAX, ChatFontScaleStore(settings).load())
    }

    @Test
    fun aFreshStoreOpensTheFullScreenChat() {
        val preference = OpenChatsOnCanvas(InMemorySecureSettingsStore())

        assertFalse(preference.enabled.value)
        assertEquals(
            ChatSurfacePresentation.ChatFirst,
            ChatSurfacePresentation.initial(openOnCanvas = preference.enabled.value, hasCanvas = true),
        )
    }

    @Test
    fun turningOpenOnTheCanvasOnPersistsAndOpensTheCanvas() = runTest {
        val settings = InMemorySecureSettingsStore()
        val preference = OpenChatsOnCanvas(settings, StandardTestDispatcher(testScheduler))

        preference.setEnabled(true)

        assertTrue(preference.enabled.value)
        val reloaded = OpenChatsOnCanvas(settings).enabled.value
        assertTrue(reloaded)
        assertEquals(
            ChatSurfacePresentation.CanvasFirst,
            ChatSurfacePresentation.initial(openOnCanvas = reloaded, hasCanvas = true),
        )
    }

    @Test
    fun aDamagedOpenOnCanvasValueReadsAsOff() {
        assertFalse(OpenChatsOnCanvas(InMemorySecureSettingsStore(mapOf("chat.openOnCanvas" to "maybe"))).enabled.value)
    }
}
