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
import kotlinx.coroutines.CancellationException
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
 * scene into its own OffscreenCanvas, the frame read back and copied into a Skia image the shared
 * [com.letta.mobile.ui.mascot.frame.RiveFrameSurface] draws. Nothing is put in the DOM.
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
            val rive = initRiveCanvas(riveOptions(wasmUrl)).await<JsAny>()
            val bytes = fetchBytes(mascotUrl).await<JsAny>()
            val file = loadRiveFile(rive, bytes).await<JsAny>()
            loaded = Loaded(rive, file, fingerprint(bytes))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            println("[mascot] Rive runtime unavailable, drawing fallbacks: ${failure.message}")
        }
    }

    override fun createScene(): RiveFrameScene {
        val runtime = checkNotNull(loaded) { "the Rive runtime is not loaded" }
        return RiveCanvasScene(createScene(runtime.rive, runtime.file, RiveAvatarContract.STATE_MACHINE))
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
            if (!closed) vmSetNumber(js, input, value)
        }

        override fun setBoolean(input: String, value: Boolean) {
            if (!closed) vmSetBoolean(js, input, value)
        }

        override fun setEnum(input: String, key: String) {
            if (!closed) vmSetEnum(js, input, key)
        }

        override fun setColor(input: String, argb: Int) {
            if (!closed) vmSetColor(js, input, argb)
        }

        override fun fire(input: String) {
            if (!closed) vmFire(js, input)
        }
    }

    override fun advance(seconds: Float) {
        if (!closed) sceneAdvance(js, seconds)
    }

    /**
     * Draws into the canvas, reads it back (straight RGBA) and copies it into this scene's bitmap
     * with one typed-array copy into Skia's memory; the image is a copy the caller owns.
     */
    override fun renderImage(width: Int, height: Int): Image {
        check(!closed) { "scene is closed" }
        val pixels = sceneRender(js, width, height)
        val target = bitmapOf(width, height)
        if (bitmapAddress == 0 || !copyIntoSkiaHeap(loadedWasm, pixels, bitmapAddress)) {
            // Skia's memory is not reachable: the slow, element-by-element path.
            target.installPixels(target.imageInfo, ByteArray(width * height * 4) { pixelByte(pixels, it).toByte() }, width * 4)
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
            bitmapAddress = if (pixmap != null && created.rowBytes == width * 4) pixmap.addr else 0
            pixmap?.close()
        }
    }

    override fun pointer(kind: RivePointer, x: Float, y: Float) {
        if (!closed) scenePointer(js, kind.ordinal, x, y)
    }

    override fun close() {
        if (closed) return
        closed = true
        sceneClose(js)
        bitmap?.close()
        bitmap = null
    }
}

private fun riveOptions(wasmUrl: String): JsAny =
    js("({ locateFile: function () { return wasmUrl; } })")

private fun fetchBytes(url: String): kotlin.js.Promise<JsAny> =
    js(
        """fetch(url).then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status + ' for ' + url);
            return r.arrayBuffer();
        }).then(function (b) { return new Uint8Array(b); })""",
    )

/** No asset loader and no Rive CDN: the mascot embeds everything it draws. */
private fun loadRiveFile(rive: JsAny, bytes: JsAny): kotlin.js.Promise<JsAny> =
    js("rive.load(bytes, undefined, false)")

/** FNV-1a over the file plus its length: a new asset means new stills. */
private fun fingerprint(bytes: JsAny): String =
    js(
        """(function () {
            let h = 0x811c9dc5;
            for (let i = 0; i < bytes.length; i++) { h ^= bytes[i]; h = Math.imul(h, 0x01000193); }
            return (h >>> 0).toString(16).padStart(8, '0') + bytes.length.toString(16);
        })()""",
    )

/**
 * The file's default artboard with the named state machine (else its first) and the artboard's
 * view model as its authored default instance, bound to both - the same binding the desktop bridge
 * makes - drawn by a Canvas2D renderer into a reusable-readback OffscreenCanvas.
 */
private fun createScene(rive: JsAny, file: JsAny, stateMachine: String): JsAny =
    js(
        """(function () {
            const artboard = file.defaultArtboard();
            let definition = null;
            try { definition = artboard.stateMachineByName(stateMachine); } catch (e) { definition = null; }
            if (!definition && artboard.stateMachineCount() > 0) definition = artboard.stateMachineByIndex(0);
            const machine = definition ? new rive.StateMachineInstance(definition, artboard) : null;
            let vmi = null;
            try {
                const vm = file.defaultArtboardViewModel(artboard);
                vmi = vm ? (vm.defaultInstance() || vm.instance()) : null;
            } catch (e) { vmi = null; }
            if (vmi) {
                artboard.bindViewModelInstance(vmi);
                if (machine) machine.bindViewModelInstance(vmi);
            }
            if (machine) machine.advanceAndApply(0); else artboard.advance(0);
            const canvas = new OffscreenCanvas(1, 1);
            const ctx = canvas.getContext('2d', { willReadFrequently: true });
            const renderer = rive.makeRenderer(canvas);
            return { rive, artboard, machine, vmi, canvas, ctx, renderer, inverse: null, w: 0, h: 0 };
        })()""",
    )

/** One frame at w x h, as the canvas reads it back: straight (unpremultiplied) RGBA, top row first. */
private fun sceneRender(s: JsAny, w: Int, h: Int): JsAny =
    js(
        """(function () {
            const rive = s.rive;
            const frame = { minX: 0, minY: 0, maxX: w, maxY: h };
            if (s.w !== w || s.h !== h) {
                s.canvas.width = w; s.canvas.height = h; s.w = w; s.h = h;
                const view = rive.computeAlignment(rive.Fit.contain, rive.Alignment.center, frame, s.artboard.bounds);
                if (s.inverse) s.inverse.delete();
                const inverse = new rive.Mat2D();
                if (view.invert(inverse)) { s.inverse = inverse; } else { inverse.delete(); s.inverse = null; }
                view.delete();
            }
            const r = s.renderer;
            if (r.beginFrame) r.beginFrame(true); else r.clear();
            r.save();
            r.align(rive.Fit.contain, rive.Alignment.center, frame, s.artboard.bounds);
            s.artboard.draw(r);
            r.restore();
            r.flush();
            rive.resolveAnimationFrame();
            return s.ctx.getImageData(0, 0, w, h).data;
        })()""",
    )

/** One typed-array copy into Skia's wasm memory at [address]; false when that memory is not reachable. */
private fun copyIntoSkiaHeap(skiko: JsAny, pixels: JsAny, address: Int): Boolean =
    js(
        """(function () {
            const memory = skiko && skiko._ && skiko._.memory;
            if (!memory) return false;
            new Uint8Array(memory.buffer, address, pixels.length).set(pixels);
            return true;
        })()""",
    )

private fun pixelByte(pixels: JsAny, index: Int): Int = js("pixels[index]")

private fun sceneAdvance(s: JsAny, seconds: Float): Unit =
    js("{ if (s.machine) s.machine.advanceAndApply(seconds); else s.artboard.advance(seconds); }")

/** Render pixels to artboard space through the inverse of the last frame's alignment, then hit-test. */
private fun scenePointer(s: JsAny, kind: Int, x: Float, y: Float): Unit =
    js(
        """{
            if (!s.machine) return;
            let px = x, py = y;
            if (s.inverse) {
                const p = new s.rive.Vec2D(x, y);
                const v = s.rive.mapXY(s.inverse, p);
                px = v.x(); py = v.y();
                p.delete(); v.delete();
            }
            if (kind === 0) s.machine.pointerMove(px, py, 0);
            else if (kind === 1) s.machine.pointerDown(px, py, 0);
            else if (kind === 2) s.machine.pointerUp(px, py, 0);
            else s.machine.pointerExit(px, py, 0);
        }""",
    )

private fun sceneClose(s: JsAny): Unit =
    js(
        """{
            try {
                if (s.inverse) s.inverse.delete();
                if (s.machine) s.machine.delete();
                if (s.vmi) s.vmi.delete();
                s.artboard.delete();
                s.renderer.delete();
            } catch (e) { }
        }""",
    )

// View-model writes. A property the file does not carry is skipped, as the desktop bridge skips it.

private fun vmSetNumber(s: JsAny, name: String, value: Float): Unit =
    js("{ try { const p = s.vmi && s.vmi.number(name); if (p) p.value = value; } catch (e) { } }")

private fun vmSetBoolean(s: JsAny, name: String, value: Boolean): Unit =
    js("{ try { const p = s.vmi && s.vmi.boolean(name); if (p) p.value = value; } catch (e) { } }")

private fun vmSetEnum(s: JsAny, name: String, key: String): Unit =
    js("{ try { const p = s.vmi && s.vmi.enum(name); if (p) p.value = key; } catch (e) { } }")

private fun vmSetColor(s: JsAny, name: String, argb: Int): Unit =
    js(
        """{
            try {
                const p = s.vmi && s.vmi.color(name);
                if (p) p.argb((argb >>> 24) & 255, (argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255);
            } catch (e) { }
        }""",
    )

private fun vmFire(s: JsAny, name: String): Unit =
    js("{ try { const p = s.vmi && s.vmi.trigger(name); if (p) p.trigger(); } catch (e) { } }")
