package com.letta.mobile.feature.chat.screen.messageactions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import kotlin.io.path.exists

class MessageInteractionContractTest {

    private val source = String(
        Files.readAllBytes(messageBubbleSource()),
        StandardCharsets.UTF_8,
    )

    @Test
    fun `message bubble exposes semantic and keyboard long click paths`() {
        assertTrue(source.contains(".semantics(mergeDescendants = false)"))
        assertTrue(source.contains("onLongClick(label = accessibilityLabel)"))
        assertTrue(source.contains("KEYCODE_MENU"))
        assertTrue(source.contains("KEYCODE_F10"))
        assertTrue(source.contains(".focusable()"))
    }

    @Test
    fun `message bubble preserves short child taps without combined clickable`() {
        assertTrue(source.contains("awaitFirstDown(requireUnconsumed = false)"))
        assertTrue(source.contains("PointerEventPass.Final"))
        assertTrue(source.contains("change.isConsumed && positionChanged"))
        assertTrue(source.contains("viewConfiguration.touchSlop"))
        assertFalse(source.contains(".combinedClickable("))
    }

    private fun messageBubbleSource(): Path {
        val userDir = Path.of(System.getProperty("user.dir"))
        return listOf(
            userDir.resolve("src/main/java/com/letta/mobile/feature/chat/screen/ChatMessageBubble.kt"),
            userDir.resolve("feature-chat/src/main/java/com/letta/mobile/feature/chat/screen/ChatMessageBubble.kt"),
        ).first { it.exists() }
    }
}
