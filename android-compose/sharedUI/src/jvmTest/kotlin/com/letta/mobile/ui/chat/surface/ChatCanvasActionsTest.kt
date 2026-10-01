package com.letta.mobile.ui.chat.surface

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-bglj6.1: sharing the board opens the page only when the image was attached. */
class ChatCanvasActionsTest {
    private val actions = RecordingChatActions()
    private val intents = mutableListOf<ChatSurfaceIntent>()
    private val canvas = ChatCanvasActions(actions, { intents += it }, "Could not share the canvas")

    @Test
    fun aSharedBoardIsAttachedAndThePageOpens() {
        canvas.shareToChat(byteArrayOf(1, 2, 3), "image/png")

        assertEquals(1, actions.count("attachImage"))
        assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.Expand), intents)
    }

    @Test
    fun aBoardThatCannotBePreparedStaysOnTheCanvasWithAComposerError() {
        canvas.shareToChat(ByteArray(AttachmentLimits.Default.maxRawBytesPerImage + 1), "image/png")

        assertEquals(0, actions.count("attachImage"))
        assertEquals(1, actions.count("reportComposerError"))
        assertTrue(intents.isEmpty(), "a failed share must not expand the page")
    }
}
