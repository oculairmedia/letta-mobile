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

    /** A file the store lists but whose bytes no longer match their hash, as a damaged disk leaves it. */
    private class DamagedStore(private val inner: InMemoryAssetStore = InMemoryAssetStore()) : com.letta.mobile.data.storage.AssetStore by inner {
        val damaged = mutableSetOf<String>()
        override fun get(ref: String): ByteArray? = if (ref in damaged) null else inner.get(ref)
        override fun put(mediaType: String, bytes: ByteArray): com.letta.mobile.data.storage.AssetRef =
            inner.put(mediaType, bytes).also { damaged -= it.ref }
    }

    @Test
    fun thePreviewShownWhileTheAssetIsAwayGivesWayToTheFullImage() {
        val bytes = photo()
        val adopted = CanvasImageAssets.adopt(image(bytes), InMemoryAssetStore())
        val onAnotherDevice = InMemoryAssetStore()
        val showingPreview = CanvasImageAssets.hydrate(adopted.copy(bytes = ByteArray(0)), onAnotherDevice)
        assertTrue(showingPreview.isShowingPreview, "the preview, until the asset is here")

        onAnotherDevice.put("image/png", bytes)
        val full = CanvasImageAssets.hydrate(showingPreview, onAnotherDevice)
        assertContentEquals(bytes, full.bytes)
        assertTrue(!full.isShowingPreview)
    }

    @Test
    fun aPreviewIsNeverWrittenAsTheImageItself() {
        DrawingSerializer.inlineImageBytes = true
        val adopted = CanvasImageAssets.adopt(image(photo()), InMemoryAssetStore())
        val showingPreview = adopted.copy(bytes = adopted.preview!!)
        val json = DrawingSerializer.serialize(PayLoad(bgColor = Color.White, elements = listOf(showingPreview)))
        assertTrue("\"imageData\"" !in json, "the preview went out as the image")
    }

    @Test
    fun aStandaloneExportCarriesTheImageBytes() {
        val bytes = photo()
        val adopted = CanvasImageAssets.adopt(image(bytes), InMemoryAssetStore())
        val payLoad = PayLoad(bgColor = Color.White, elements = listOf(adopted))
        assertTrue("\"imageData\"" !in DrawingSerializer.serialize(payLoad), "a board's own save leaves them to the store")
        val exported = DrawingSerializer.deserialize(DrawingSerializer.serialize(payLoad, inlineImageBytes = true))
        assertContentEquals(bytes, exported.elements.filterIsInstance<Element.Image>().single().bytes)
    }

    @Test
    fun intactBytesRepairADamagedStoredAsset() {
        val bytes = photo()
        val store = DamagedStore()
        val adopted = CanvasImageAssets.adopt(image(bytes), store)
        store.damaged += adopted.assetRef!!
        assertTrue(store.has(adopted.assetRef!!) && store.get(adopted.assetRef!!) == null)

        CanvasImageAssets.keep(adopted, store)
        assertContentEquals(bytes, store.get(adopted.assetRef!!))
    }

    @Test
    fun keepingAnImagesAssetBooksIsNotAStepToUndo() {
        val plain = image(photo())
        val adopted = CanvasImageAssets.adopt(plain, InMemoryAssetStore())
        assertTrue(!CanvasWorkspaceSupport.shouldRecordDrawingStep(listOf(plain), listOf(adopted), isApplyingHistory = false))
        val moved = adopted.copy(points = listOf(Offset(10f, 10f), Offset(410f, 310f)))
        assertTrue(CanvasWorkspaceSupport.shouldRecordDrawingStep(listOf(adopted), listOf(moved), isApplyingHistory = false))
    }

    @Test
    fun anExportCompletesPreviewOnlyImagesAndCountsTheOnesItCannot() = kotlinx.coroutines.test.runTest {
        val bytes = photo()
        val adopted = CanvasImageAssets.adopt(image(bytes, id = "here"), InMemoryAssetStore())
        val fromHost = adopted.copy(id = "fetched", bytes = adopted.preview!!)
        val lost = adopted.copy(id = "lost", assetRef = "sha256:" + "0".repeat(64), bytes = ByteArray(0))
        val stored = InMemoryAssetStore()
        val completion = CanvasImageAssets.completeForExport(listOf(fromHost, lost), stored) { ref ->
            if (ref == adopted.assetRef) bytes else null
        }
        assertEquals(listOf("fetched"), completion.completed.map { it.id })
        assertContentEquals(bytes, completion.completed.single().bytes)
        assertEquals(1, completion.missing, "the image nobody has is counted, not exported as a preview")
    }

    @Test
    fun theUndoChoiceComparesDrawingsTheWayTheUndoStepDoes() {
        val plain = image(photo())
        val adopted = CanvasImageAssets.adopt(plain, InMemoryAssetStore())
        assertTrue(CanvasWorkspaceSupport.sameDrawing(listOf(plain), listOf(adopted)))
        assertTrue(!CanvasWorkspaceSupport.sameDrawing(listOf(plain), listOf(plain.copy(opacity = 0.5f))))
    }
}
