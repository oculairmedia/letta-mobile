@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSyncTransport
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.storage.InMemoryAssetStore
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.PayLoad
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A board's images move into the asset store when it opens (letta-mobile-w3nb2.2). */
class CanvasImageAssetsUiTest {
    private class Capturing : CanvasSyncTransport {
        val published = CopyOnWriteArrayList<CanvasOp>()
        override suspend fun publish(canvasId: CanvasId, op: CanvasOp) { published += op }
        override fun subscribe(canvasId: CanvasId): Flow<CanvasOp> = emptyFlow()
    }

    @AfterTest
    fun backToTheDefault() {
        DrawingSerializer.inlineImageBytes = false
    }

    private fun png(): ByteArray {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        val random = java.util.Random(3)
        for (y in 0 until 48) for (x in 0 until 64) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun boardWithAnInlineImage(bytes: ByteArray): String = DrawingSerializer.serialize(
        PayLoad(
            bgColor = Color.White,
            elements = listOf(
                Element.Image(
                    id = "legacy", bytes = bytes, intrinsicSize = Size(64f, 48f),
                    points = listOf(Offset(100f, 100f), Offset(164f, 148f)),
                ),
            ),
        ),
    )

    /**
     * While drawings still carry image bytes, an image is only stored, never rewritten: an older app
     * drops the fields it does not know, and a board that re-adopted what it sent back traded the
     * same images with it forever (seen live between the Pixel and desktop). No op may come of it.
     */
    @Test
    fun whileBytesTravelInlineAnImageIsStoredButNotRewritten() = runComposeUiTest {
        DrawingSerializer.inlineImageBytes = true
        val bytes = png()
        val relay = Capturing()
        val session = runBlocking {
            CanvasSession.create(
                InMemoryCanvasDocumentStore(),
                CanvasCreateOptions(title = "Board", initialSceneJson = boardWithAnInlineImage(bytes), syncTransport = relay),
            )
        }
        val assets = InMemoryAssetStore()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller, assets = assets) }
        mainClock.advanceTimeBy(3_000)
        val ref = InMemoryAssetStore().put("image/png", bytes).ref
        waitUntil(timeoutMillis = 5000) { assets.has(ref) }
        mainClock.advanceTimeBy(3_000)
        waitForIdle()

        assertNull(controller.state.value.elements.filterIsInstance<Element.Image>().single().assetRef, "the image is left as it came")
        assertTrue(relay.published.none { it is CanvasOp.UpdateElementOp }, "nothing is sent back: ${relay.published}")
    }

    @Test
    fun onceBytesStopTravellingInlineTheImageGetsItsRef() = runComposeUiTest {
        val bytes = png()
        val session = runBlocking {
            CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "Board", initialSceneJson = boardWithAnInlineImage(bytes)))
        }
        val assets = InMemoryAssetStore()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller, assets = assets) }
        mainClock.advanceTimeBy(3_000)

        fun adopted() = controller.state.value.elements.filterIsInstance<Element.Image>().singleOrNull()?.assetRef
        waitUntil(timeoutMillis = 5000) { adopted() != null }
        assertContentEquals(bytes, assets.get(adopted()!!), "the store has the picture under its ref")
        mainClock.advanceTimeBy(3_000)
        waitUntil(timeoutMillis = 5000) { session.sceneJsonOrEmpty().contains(adopted()!!) }
        assertTrue(!session.sceneJsonOrEmpty().contains("\"imageData\""), "the board's scene carries the ref, not the bytes")
    }
}
