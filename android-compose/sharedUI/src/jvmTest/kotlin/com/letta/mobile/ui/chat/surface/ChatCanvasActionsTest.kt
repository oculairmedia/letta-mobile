package com.letta.mobile.ui.chat.surface

import androidx.compose.ui.geometry.Rect
import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.canvas.compose.ComposeBounds
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipt
import com.letta.mobile.data.chat.projection.CanvasArtifactStatus
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    private fun receipt(bounds: ComposeBounds?) = CanvasArtifactReceipt(
        artifactId = "weekend-plan", canvasId = "canvas-conversation-conv-1", revision = 42,
        status = CanvasArtifactStatus.Published, title = "Weekend plan", kinds = emptyList(), itemCount = 0, bounds = bounds,
    )

    @Test
    fun showingAnArtifactFramesItsBoundsAndOpensTheCanvas() {
        canvas.showArtifact(receipt(ComposeBounds(10f, 20f, 100f, 50f)))

        assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.OpenCanvas), intents)
        val target = canvas.camera.target!!
        assertEquals(Rect(10f, 20f, 110f, 70f), target.bounds)
        assertEquals("canvas-conversation-conv-1", target.canvasId)
        // The board consumes it once framed; a newer request is never cleared by an older one.
        canvas.showArtifact(receipt(ComposeBounds(0f, 0f, 1f, 1f)))
        canvas.camera.consume(target)
        assertEquals(Rect(0f, 0f, 1f, 1f), canvas.camera.target!!.bounds)
        canvas.camera.consume(canvas.camera.target!!)
        assertNull(canvas.camera.target)
    }

    @Test
    fun anArtifactWithoutBoundsStillOpensTheCanvas() {
        canvas.showArtifact(receipt(bounds = null))

        assertEquals(listOf<ChatSurfaceIntent>(ChatSurfaceIntent.OpenCanvas), intents)
        assertNull(canvas.camera.target)
    }
}
