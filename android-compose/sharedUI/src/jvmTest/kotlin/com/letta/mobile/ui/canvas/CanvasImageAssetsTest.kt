package com.letta.mobile.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.storage.AssetRefs
import com.letta.mobile.data.storage.InMemoryAssetStore
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.PayLoad
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A board's images live in the asset store; the drawing refers to them (letta-mobile-w3nb2.2). */
class CanvasImageAssetsTest {
    @AfterTest
    fun backToTheDefault() {
        DrawingSerializer.inlineImageBytes = false
    }

    private fun photo(width: Int = 800, height: Int = 600): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        // Noise, as a photo is: flat colour would compress to nothing and prove nothing.
        val random = java.util.Random(11)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun image(bytes: ByteArray, id: String = "img") = Element.Image(
        id = id,
        bytes = bytes,
        intrinsicSize = Size(800f, 600f),
        points = listOf(Offset(0f, 0f), Offset(400f, 300f)),
    )

    @Test
    fun anImageIsAdoptedIntoTheStoreWithARefAndASmallPreview() {
        val store = InMemoryAssetStore()
        val bytes = photo()
        val adopted = CanvasImageAssets.adopt(image(bytes), store)

        val ref = assertNotNull(adopted.assetRef)
        assertTrue(AssetRefs.isValid(ref))
        assertEquals("image/png", adopted.mediaType)
        assertContentEquals(bytes, store.get(ref))
        val preview = assertNotNull(adopted.preview, "a preview to draw until the asset is resolved")
        assertTrue(preview.size <= CanvasImageAssets.MAX_PREVIEW_BYTES, "preview is ${preview.size} bytes")
        assertSame(adopted, CanvasImageAssets.adopt(adopted, store), "an adopted image is left alone")
    }

    @Test
    fun anImageWithoutItsBytesIsFilledFromTheStoreOrElseItsPreview() {
        val store = InMemoryAssetStore()
        val adopted = CanvasImageAssets.adopt(image(photo()), store)
        val bare = adopted.copy(bytes = ByteArray(0))

        assertContentEquals(store.get(adopted.assetRef!!), CanvasImageAssets.hydrate(bare, store).bytes)
        val elsewhere = InMemoryAssetStore()
        assertContentEquals(adopted.preview, CanvasImageAssets.hydrate(bare, elsewhere).bytes, "the preview until the asset arrives")
    }

    @Test
    fun anImageThatArrivesWithItsBytesIsKeptForWhenDrawingsStopCarryingThem() {
        val sender = InMemoryAssetStore()
        val adopted = CanvasImageAssets.adopt(image(photo()), sender)
        val receiver = InMemoryAssetStore()
        CanvasImageAssets.resolve(PayLoad(bgColor = Color.White, elements = listOf(adopted)), receiver)
        assertTrue(receiver.has(adopted.assetRef!!))
    }

    @Test
    fun theDrawingCarriesTheRefAndDropsTheBytesOnlyWhenInlineBytesAreOff() {
        val store = InMemoryAssetStore()
        val adopted = CanvasImageAssets.adopt(image(photo()), store)
        val drawing = PayLoad(bgColor = Color.White, elements = listOf(adopted))

        DrawingSerializer.inlineImageBytes = true
        val inline = DrawingSerializer.serialize(drawing)
        assertTrue(inline.contains(adopted.assetRef!!))
        assertTrue(inline.contains("\"imageData\""), "still inline while readers may lack the asset")

        DrawingSerializer.inlineImageBytes = false
        val byRef = DrawingSerializer.serialize(drawing)
        assertTrue(!byRef.contains("\"imageData\""), "no bytes once assets are served")
        assertTrue(byRef.length < inline.length / 4, "by ref: ${byRef.length} chars, inline: ${inline.length}")

        val read = DrawingSerializer.deserialize(byRef).elements.single() as Element.Image
        assertEquals(adopted.assetRef, read.assetRef)
        assertEquals(0, read.bytes.size)
        assertContentEquals(store.get(adopted.assetRef!!), CanvasImageAssets.hydrate(read, store).bytes)
    }

    @Test
    fun anOldDrawingWithInlineBytesStillReads() {
        val legacy = DrawingSerializer.serialize(PayLoad(bgColor = Color.White, elements = listOf(image(photo()))))
        val read = DrawingSerializer.deserialize(legacy).elements.single() as Element.Image
        assertNull(read.assetRef)
        assertTrue(read.bytes.isNotEmpty())
    }

    @Test
    fun mediaTypesAreReadFromTheBytes() {
        assertEquals("image/png", CanvasImageAssets.sniffMediaType(photo(4, 4)))
        assertEquals("image/jpeg", CanvasImageAssets.sniffMediaType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals("application/octet-stream", CanvasImageAssets.sniffMediaType(byteArrayOf(1, 2, 3)))
    }
}
