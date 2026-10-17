@file:OptIn(ExperimentalWasmJsInterop::class)

package com.letta.mobile.web.mascot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.avatar.rive.RiveAvatarContract
import com.letta.mobile.avatar.rive.RiveInputSink
import com.letta.mobile.ui.mascot.MascotStillStore
import com.letta.mobile.ui.mascot.frame.RiveFrameRenderer
import com.letta.mobile.ui.mascot.frame.RiveFrameScene
import com.letta.mobile.ui.mascot.frame.RivePointer
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
internal class RiveCanvasRenderer(
    private val wasmUrl: String = RIVE_WASM_PATH,
    private val mascotUrl: String = MASCOT_PATH,
) : RiveFrameRenderer {
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
            val rive = riveInitRuntime(wasmUrl).await<JsAny>()
            val bytes = riveFetchBytes(mascotUrl).await<JsAny>()
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

    override fun encodePng(image: Image): ByteArray? = image.encodeToData(EncodedImageFormat.PNG)?.bytes

    companion object {
        /** Staged into the distribution by web/build.gradle.kts from the pinned npm package. */
        const val RIVE_WASM_PATH = "rive/rive.wasm"

        /** :avatar:renderer-rive's shipped mascot, served beside the page. */
        const val MASCOT_PATH = "mascot/mascot.riv"
    }
}

/** One scene in its OffscreenCanvas. Single-threaded like the page; nothing is called after [close]. */
private class RiveCanvasScene(private val js: JsAny) : RiveFrameScene() {
    private var closed = false
    private var bitmap: Bitmap? = null
    private var bitmapAddress = 0

    override val inputSink: RiveInputSink = object : RiveInputSink {
        override fun setNumber(input: String, value: Float) {
            if (!closed) riveSetNumber(js, input, value)
        }

        override fun setBoolean(input: String, value: Boolean) {
            if (!closed) riveSetBoolean(js, input, value)
        }

        override fun setEnum(input: String, key: String) {
            if (!closed) riveSetEnum(js, input, key)
        }

        override fun setColor(input: String, argb: Int) {
            if (!closed) riveSetColor(js, input, argb)
        }

        override fun fire(input: String) {
            if (!closed) riveFire(js, input)
        }
    }

    override fun advance(seconds: Float) {
        if (!closed) riveAdvance(js, seconds)
    }

    /**
     * Draws into the canvas, reads it back (straight RGBA) and copies it into this scene's bitmap
     * with one typed-array copy into Skia's memory; the image is a copy the caller owns.
     */
    override fun renderImage(width: Int, height: Int): Image {
        check(!closed) { "scene is closed" }
        val pixels = riveRender(js, width, height)
        val target = bitmapOf(width, height)
        if (bitmapAddress == 0 || !riveCopyIntoHeap(loadedWasm, pixels, bitmapAddress)) {
            // Skia's memory is not reachable: the slow, element-by-element path.
            val bytes = ByteArray(width * height * BYTES_PER_PIXEL) { rivePixelByte(pixels, it).toByte() }
            target.installPixels(target.imageInfo, bytes, width * BYTES_PER_PIXEL)
        }
        target.notifyPixelsChanged()
        return Image.makeFromBitmap(target)
    }

    private fun bitmapOf(width: Int, height: Int): Bitmap {
        bitmap?.takeIf { it.width == width && it.height == height }?.let { return it }
        bitmap?.close()
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        return Bitmap().also { created ->
            check(created.allocPixels(info)) { "could not allocate a ${width}x$height mascot frame" }
            bitmap = created
            // Rows packed edge to edge, as the canvas reads them back; otherwise use the slow path.
            val pixmap = created.peekPixels()
            bitmapAddress = if (pixmap != null && created.rowBytes == width * BYTES_PER_PIXEL) pixmap.addr else 0
            pixmap?.close()
        }
    }

    override fun pointer(kind: RivePointer, x: Float, y: Float) {
        if (!closed) rivePointer(js, kind.ordinal, x, y)
    }

    override fun close() {
        if (closed) return
        closed = true
        riveClose(js)
        bitmap?.close()
        bitmap = null
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}
