@file:OptIn(ExperimentalWasmJsInterop::class)

package com.letta.mobile.web.mascot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.avatar.rive.RiveAvatarContract
import com.letta.mobile.avatar.rive.RiveInputSink
import com.letta.mobile.avatar.rive.RiveWrite
import com.letta.mobile.avatar.rive.RiveWriteSink
import com.letta.mobile.ui.mascot.MascotStillStore
import com.letta.mobile.ui.mascot.frame.RiveFrameRenderer
import com.letta.mobile.ui.mascot.frame.RiveFrameScene
import com.letta.mobile.ui.mascot.frame.RiveFrameStep
import com.letta.mobile.ui.mascot.frame.RivePointerEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * The browser's renderer for the shared frame pipeline: `@rive-app/canvas-advanced` drawing each
 * scene into its own OffscreenCanvas (the JS binding in `js/rive-frames`), the frame read back and
 * copied into a Skia image the shared [com.letta.mobile.ui.mascot.frame.RiveFrameSurface] draws.
 * Nothing is put in the DOM.
 *
 * Unavailable until [prepare] has the runtime and the mascot file; if either fails, it stays
 * unavailable and every mascot keeps drawing its fallback (one console line, no retry loop).
 */
internal class RiveCanvasRenderer : RiveFrameRenderer {
    private class Loaded(val rive: JsAny, val file: JsAny, val version: String)

    private var loaded: Loaded? by mutableStateOf(null)

    override val available: Boolean get() = loaded != null

    override val assetVersion: String get() = checkNotNull(loaded).version

    /** The page's one thread: a scene call runs in place, inside the frame that asked for it. */
    override val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined

    // TODO(letta-mobile-ckmub.17.7): keep stills in the browser store W7 adds; memory only until then.
    override val stillStore: MascotStillStore? = null

    /** Brings up the runtime and the shipped mascot; safe to call again (it returns once loaded). */
    override suspend fun prepare() {
        if (loaded != null) return
        try {
            val rive = riveInitRuntime(RIVE_WASM_PATH).await<JsAny>()
            val bytes = riveFetchBytes(MASCOT_PATH).await<JsAny>()
            val file = riveLoadFile(rive, bytes).await<JsAny>()
            loaded = Loaded(rive, file, riveFingerprint(bytes))
        } catch (failure: JsException) {
            println("[mascot] Rive runtime unavailable, drawing fallbacks: ${failure.message}")
        }
    }

    override fun createScene(): RiveFrameScene {
        val runtime = checkNotNull(loaded) { "the Rive runtime is not loaded" }
        return RiveCanvasScene(riveCreateScene(runtime.rive, runtime.file, RiveAvatarContract.STATE_MACHINE))
    }

    override fun encodePng(image: Image): ByteArray? = image.encodeToData(
        format = EncodedImageFormat.PNG,
        pngCompressionLevel = PNG_COMPRESSION_LEVEL,
    )?.bytes

    companion object {
        private const val PNG_COMPRESSION_LEVEL = 6

        /** Staged into the distribution by web/build.gradle.kts from the pinned npm package. */
        const val RIVE_WASM_PATH = "rive/rive.wasm"

        /** :avatar:renderer-rive's shipped mascot, served beside the page. */
        const val MASCOT_PATH = "mascot/mascot.riv"
    }
}

/** One scene in its OffscreenCanvas. Single-threaded like the page; nothing is called after [close]. */
private class RiveCanvasScene(private val js: JsAny) : RiveFrameScene() {
    private var closed = false
    private var frame: RiveCanvasFrame? = null

    override val inputSink: RiveInputSink = RiveWriteSink(::write)

    private fun write(write: RiveWrite) {
        if (closed) return
        when (write) {
            is RiveWrite.Number -> riveSetNumber(js, write.input, write.value)
            is RiveWrite.Bool -> riveSetBoolean(js, write.input, write.value)
            is RiveWrite.Enum -> riveSetEnum(js, write.input, write.key)
            is RiveWrite.Color -> riveSetColor(js, write.input, write.argb)
            is RiveWrite.Fire -> riveFire(js, write.input)
        }
    }

    override fun advance(step: RiveFrameStep) {
        if (!closed) riveAdvance(js, step.seconds)
    }

    /** Draws into the canvas, reads it back and copies it into this scene's frame; the image is a copy the caller owns. */
    override fun renderImage(size: IntSize): Image {
        check(!closed) { "scene is closed" }
        val target = frameOf(size)
        target.copy(riveRender(js, size.width, size.height))
        return target.snapshot()
    }

    private fun frameOf(size: IntSize): RiveCanvasFrame {
        frame?.takeIf { it.size == size }?.let { return it }
        frame?.close()
        return RiveCanvasFrame(size).also { frame = it }
    }

    override fun pointer(event: RivePointerEvent) {
        if (!closed) rivePointer(js, event.kind.ordinal, event.position.x, event.position.y)
    }

    override fun close() {
        if (closed) return
        closed = true
        riveClose(js)
        frame?.close()
        frame = null
    }
}

/**
 * One frame's pixels in Skia: a bitmap of [size] in straight RGBA, written from the canvas readback
 * with one typed-array copy into skiko's wasm memory - or element by element when that memory is
 * not reachable, or the bitmap's rows are padded.
 */
private class RiveCanvasFrame(val size: IntSize) {
    private val bitmap = Bitmap()
    private val address: Int

    init {
        val info = ImageInfo(size.width, size.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        check(bitmap.allocPixels(info)) { "could not allocate a $size mascot frame" }
        val pixmap = bitmap.peekPixels()
        address = if (pixmap != null && bitmap.rowBytes == rowBytes) pixmap.addr else 0
        pixmap?.close()
    }

    private val rowBytes: Int get() = size.width * BYTES_PER_PIXEL

    private val fastCopyAvailable: Boolean get() = address != 0

    fun copy(pixels: JsAny) {
        val copiedInPlace = fastCopyAvailable && riveCopyIntoHeap(loadedWasm, pixels, address)
        if (!copiedInPlace) {
            val bytes = ByteArray(rowBytes * size.height) { rivePixelByte(pixels, it).toByte() }
            bitmap.installPixels(bitmap.imageInfo, bytes, rowBytes)
        }
        bitmap.notifyPixelsChanged()
    }

    fun snapshot(): Image = Image.makeFromBitmap(bitmap)

    fun close() = bitmap.close()

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}

