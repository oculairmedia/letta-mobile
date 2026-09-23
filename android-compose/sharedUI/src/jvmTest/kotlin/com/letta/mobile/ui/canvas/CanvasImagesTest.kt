package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Images are made small enough to live in the drawing before they are placed on the board. */
class CanvasImagesTest {

    private fun encoded(width: Int, height: Int, alpha: Boolean, format: String): ByteArray {
        val image = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        // Noise, as a photo is: flat colour would compress to nothing and prove nothing.
        val random = java.util.Random(7)
        for (y in 0 until height) for (x in 0 until width) {
            val a = if (alpha) 128 else 255
            image.setRGB(x, y, (a shl 24) or (random.nextInt(0xFFFFFF)))
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    @Test
    fun aLargePhotoIsScaledToTheCapAndKeepsItsShape() {
        val photo = encoded(4000, 3000, alpha = false, format = "png")
        val ready = assertNotNull(prepareCanvasImage(photo))
        assertEquals(2048, ready.width)
        assertEquals(1536, ready.height)
        val back = ImageIO.read(ByteArrayInputStream(ready.bytes))
        assertEquals(2048, back.width, "the bytes are the scaled image")
        assertTrue(ready.bytes.size < photo.size, "and smaller than what came in")
        // No transparency: stored as JPEG.
        assertEquals(0xFF.toByte(), ready.bytes[0])
        assertEquals(0xD8.toByte(), ready.bytes[1])
    }

    @Test
    fun transparencySurvives() {
        val ready = assertNotNull(prepareCanvasImage(encoded(300, 200, alpha = true, format = "png")))
        assertEquals(300, ready.width, "a small image is not enlarged")
        assertEquals(0x89.toByte(), ready.bytes[0], "kept as PNG")
        assertTrue(ImageIO.read(ByteArrayInputStream(ready.bytes)).colorModel.hasAlpha())
    }

    @Test
    fun notAnImageIsNothing() {
        assertNull(prepareCanvasImage("not an image".encodeToByteArray()))
    }

    @Test
    fun severalImagesAreFannedOutFromThePoint() {
        val controller = DrawBoxController(Reducer(UseCase()))
        val one = assertNotNull(prepareCanvasImage(encoded(200, 100, alpha = false, format = "png")))
        CanvasImages.insert(controller, listOf(one, one, one), Offset(500f, 400f))
        val images = controller.state.value.elements.filterIsInstance<Element.Image>()
        assertEquals(3, images.size)
        assertEquals(Offset(500f, 400f), images[0].bounds().center)
        assertEquals(Offset(500f + CanvasImages.STAGGER * 2, 400f + CanvasImages.STAGGER * 2), images[2].bounds().center)
    }
}
