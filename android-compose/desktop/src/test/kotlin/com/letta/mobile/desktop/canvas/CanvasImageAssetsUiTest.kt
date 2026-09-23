@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.storage.InMemoryAssetStore
import com.letta.mobile.ui.canvas.CanvasWorkspace
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.PayLoad
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** A board made before images were assets moves its pictures into the store when it opens. */
class CanvasImageAssetsUiTest {
    private fun png(): ByteArray {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        val random = java.util.Random(3)
        for (y in 0 until 48) for (x in 0 until 64) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun anInlineImageIsAdoptedIntoTheAssetStoreOnOpen() = runComposeUiTest {
        val bytes = png()
        val legacy = Element.Image(
            id = "legacy", bytes = bytes, intrinsicSize = Size(64f, 48f),
            points = listOf(Offset(100f, 100f), Offset(164f, 148f)),
        )
        val scene = DrawingSerializer.serialize(PayLoad(bgColor = Color.White, elements = listOf(legacy)))
        val session = runBlocking {
            CanvasSession.create(InMemoryCanvasDocumentStore(), CanvasCreateOptions(title = "Board", initialSceneJson = scene))
        }
        val assets = InMemoryAssetStore()
        val controller = DrawBoxController(Reducer(UseCase()))
        setContent { CanvasWorkspace(session = session, controller = controller, assets = assets) }
        mainClock.advanceTimeBy(3_000)

        fun adopted() = controller.state.value.elements.filterIsInstance<Element.Image>().singleOrNull()?.assetRef
        waitUntil(timeoutMillis = 5000) { adopted() != null }
        assertContentEquals(bytes, assets.get(adopted()!!), "the store has the picture under its ref")
        // And the board's scene records the ref, so the next open (or another app) knows it.
        mainClock.advanceTimeBy(3_000)
        waitUntil(timeoutMillis = 5000) { session.sceneJsonOrEmpty().contains(adopted()!!) }
    }
}
