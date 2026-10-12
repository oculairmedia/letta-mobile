@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.letta.mobile.desktop.commandPaletteKeyDispatcher
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerCommand
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.ChatSurface
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * letta-mobile-ckmub.3.11 (the hard cut's S11 mitigation): the shared chat page is desktop's only
 * chat, so the keyboard the legacy desktop composer had must hold on it. Enter and Ctrl+Enter send,
 * Shift+Enter is a newline, a typed `/x` action command runs on Enter, Escape on the full-screen page
 * goes back to the canvas, and Cmd/Ctrl+K opens the palette whatever field has focus.
 */
class DesktopSharedChatKeybindingTest {
    /** Records every [ChatActions] call by name; the draft follows updateComposerText. */
    private class RecordingPort(text: String, commands: List<ChatComposerCommand> = emptyList()) : ChatSessionPort {
        val calls = mutableListOf<String>()
        private val composerState = MutableStateFlow(
            ChatComposerUiState(text = text, canSend = text.isNotBlank(), commands = persistentListOf<ChatComposerCommand>().addAll(commands)),
        )
        override val uiState: StateFlow<ChatUiState> = MutableStateFlow(
            ChatUiState(conversationState = ConversationState.Ready("conv-1"), isLoadingMessages = false, agentId = "agent-1"),
        )
        override val composer: StateFlow<ChatComposerUiState> = composerState
        override val actions: ChatActions = Proxy.newProxyInstance(
            ChatActions::class.java.classLoader,
            arrayOf(ChatActions::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> return@newProxyInstance proxy === args?.get(0)
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "toString" -> return@newProxyInstance "RecordingChatActions"
            }
            calls += method.name
            if (method.name == "updateComposerText") {
                val next = args[0] as String
                composerState.value = composerState.value.copy(text = next, canSend = next.isNotBlank())
            }
            null
        } as ChatActions

        fun count(name: String) = calls.count { it == name }
    }

    private fun ComposeUiTest.showPage(port: RecordingPort) {
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 1100.dp, height = 800.dp)) {
                    ChatSurface(
                        port = port,
                        presentation = ChatSurfacePresentation.ChatFirst,
                        onIntent = {},
                        host = ChatSurfaceHost(),
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.press(key: Key, modifier: Key? = null) {
        onNodeWithTag(ComposerTestTags.INPUT).requestFocus().performKeyInput {
            if (modifier == null) pressKey(key) else withKeyDown(modifier) { pressKey(key) }
        }
        waitForIdle()
    }

    @Test
    fun enterAndCtrlEnterSendTheDraft() = runComposeUiTest {
        val port = RecordingPort("Ship it")
        showPage(port)
        press(Key.Enter)
        press(Key.Enter, modifier = Key.CtrlLeft)
        press(Key.NumPadEnter)
        assertEquals(3, port.count("send"))
    }

    @Test
    fun shiftEnterIsANewlineNotASend() = runComposeUiTest {
        val port = RecordingPort("First line")
        showPage(port)
        press(Key.Enter, modifier = Key.ShiftLeft)
        assertEquals(0, port.count("send"))
    }

    @Test
    fun enterOnATypedActionCommandRunsItInsteadOfSending() = runComposeUiTest {
        val canvas = ChatComposerCommand(id = "canvas", label = "canvas", description = "Open canvas workspace")
        val port = RecordingPort("/canvas", commands = listOf(canvas))
        showPage(port)
        press(Key.Enter)
        assertEquals(1, port.count("runComposerCommand"))
        assertEquals(0, port.count("send"))
    }

    @Test
    fun escapeOnTheFullScreenPageGoesBackToTheCanvas() {
        assertTrue(escapeCollapsesToCanvas(ChatSurfaceMode.FullScreen, Key.Escape, KeyEventType.KeyDown))
        assertFalse(escapeCollapsesToCanvas(ChatSurfaceMode.FullScreen, Key.Escape, KeyEventType.KeyUp))
        assertFalse(escapeCollapsesToCanvas(ChatSurfaceMode.FullScreen, Key.Enter, KeyEventType.KeyDown))
    }

    @Test
    fun modKOpensThePaletteFromAnyFocusedField() {
        var opened = 0
        val dispatcher = commandPaletteKeyDispatcher { opened++ }
        val source = Canvas()
        fun key(id: Int, modifiers: Int, code: Int = KeyEvent.VK_K) =
            KeyEvent(source, id, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED)

        assertTrue(dispatcher.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, InputEvent.CTRL_DOWN_MASK)))
        assertTrue(dispatcher.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, InputEvent.META_DOWN_MASK)))
        assertFalse(dispatcher.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, 0)), "a plain K is typed into the prompt")
        assertFalse(dispatcher.dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, InputEvent.CTRL_DOWN_MASK)))
        assertFalse(dispatcher.dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_J)))
        assertEquals(2, opened)
    }
}
