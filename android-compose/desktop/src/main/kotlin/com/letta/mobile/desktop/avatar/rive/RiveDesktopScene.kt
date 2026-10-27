package com.letta.mobile.desktop.avatar.rive

import androidx.compose.ui.unit.IntSize
import com.letta.mobile.avatar.rive.RiveInputSink
import com.letta.mobile.ui.mascot.frame.RiveFrameScene
import com.letta.mobile.ui.mascot.frame.RiveFrameStep
import com.letta.mobile.ui.mascot.frame.RivePointer
import com.letta.mobile.ui.mascot.frame.RivePointerEvent
import com.letta.mobile.util.Telemetry
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * One native scene over the shared GPU context, a loaded file, and its state machine.
 *
 * The EGL/D3D context and its resources belong to one thread, so every native
 * call is routed through [RiveThread]: writes are posted, reads and renders are called and
 * awaited. Callers may use any thread; the frame loop suspends on [RiveThread.dispatcher] so the
 * readback never stalls the UI.
 */
class RiveDesktopScene internal constructor(
    private val native: RiveBridgeNative,
    private val handle: Pointer,
    val adapterName: String,
    private val advanceActivity: Boolean = RiveBridgeNative.ADVANCE_ACTIVITY,
    private val libraryPath: String? = RiveBridgeNative.PATH,
) : RiveFrameScene(), AutoCloseable {
    /** The bridge's `kind` argument for a pointer event. */
    private val RivePointer.code: Int
        get() = when (this) {
            RivePointer.MOVE -> 0
            RivePointer.DOWN -> 1
            RivePointer.UP -> 2
            RivePointer.EXIT -> 3
        }

    private var buffer: Memory? = null
    private var pixels: ByteArray? = null
    @Volatile private var closed = false

    val artboardWidth: Float get() = RiveThread.call { native.rive_bridge_artboard_width(openHandle()) }
    val artboardHeight: Float get() = RiveThread.call { native.rive_bridge_artboard_height(openHandle()) }

    private fun openHandle(): Pointer {
        check(!closed) { "RiveDesktopScene is closed" }
        return handle
    }

    /** Load a .riv and bind [target] (see [RiveSceneTarget]). */
    fun load(bytes: ByteArray, target: RiveSceneTarget = RiveSceneTarget()) = RiveThread.call {
        val artboard = target.artboard
        val code = if (artboard == null) {
            native.rive_bridge_load(openHandle(), bytes, bytes.size, target.stateMachine)
        } else {
            check(RiveBridgeNative.ARTBOARD_BY_NAME) {
                "this native Rive bridge predates rive_bridge_load_artboard; rebuild it to load '$artboard'"
            }
            native.rive_bridge_load_artboard(openHandle(), bytes, bytes.size, target.stateMachine, artboard)
        }
        check(code == 0) { "rive_bridge_load failed ($code)" }
    }

    override fun advance(step: RiveFrameStep) = motionGeneration().let { generation ->
        RiveThread.post {
            if (closed) return@post
            val handle = openHandle()
            if (advanceActivity) {
                if (native.riveBridgeAdvanceIsActive(handle, step.seconds) == 0) settleIfUnchanged(generation)
            } else native.rive_bridge_advance(handle, step.seconds)
        }
    }

    /**
     * Renders into a reused native buffer and copies it into a reused byte array: premultiplied
     * RGBA, top row first. The array is the scene's and is overwritten by the next render - read it
     * before then (the surface rasterises it into a Skia image at once).
     */
    fun render(width: Int, height: Int, clearArgb: Int = 0): ByteArray = RiveThread.call {
        openHandle()
        val pixelCount = width.toLong() * height
        require(width > 0 && height > 0 && pixelCount <= Int.MAX_VALUE / 4) { "Invalid Rive viewport: ${width}x$height" }
        val size = pixelCount * 4
        val target = buffer?.takeIf { it.size() == size } ?: Memory(size).also {
            buffer?.close()
            buffer = it
        }
        val code = native.rive_bridge_render(openHandle(), width, height, clearArgb, target)
        check(code == 0) { "rive_bridge_render failed ($code)" }
        val out = pixels?.takeIf { it.size.toLong() == size } ?: ByteArray(size.toInt()).also { pixels = it }
        target.read(0, out, 0, out.size)
        if (!rendererActiveReported) {
            rendererActiveReported = true
            Telemetry.event(
                "Rive", "[mascot] Rive native renderer active:",
                "adapter" to adapterName, "lib" to libraryPath,
                level = Telemetry.Level.INFO,
            )
        }
        out
    }

    /**
     * One frame as a Skia image the caller owns, rasterised while the bytes are still this frame's
     * (the scene reuses them): premultiplied RGBA, as the bridge renders it.
     */
    override fun renderImage(size: IntSize): Image = RiveThread.call {
        val info = ImageInfo(size.width, size.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        Image.makeRaster(info, render(size.width, size.height), size.width * 4)
    }

    override fun pointer(event: RivePointerEvent) =
        RiveThread.post { if (!closed) native.rive_bridge_pointer(handle, event.kind.code, event.position.x, event.position.y) }

    fun fireTrigger(name: String): Boolean = RiveThread.call { native.rive_bridge_fire_trigger(openHandle(), name) == 0 }

    fun setNumber(name: String, value: Float): Boolean = RiveThread.call { native.rive_bridge_set_number(openHandle(), name, value) == 0 }

    /**
     * Reads a view-model number back: the probe path of MOTION-PIPELINE section 0, where a two-way
     * data bind mirrors a node property into a number every frame. Null when the property is absent,
     * is not a number, or the loaded DLL has no readback export at all.
     */
    fun getNumber(name: String): Float? {
        if (!RiveBridgeNative.PROBE_READBACK) return null
        val out = FloatArray(1)
        return RiveThread.call { if (native.rive_bridge_vm_get_number(openHandle(), name, out) == 0) out[0] else null }
    }

    /** Every number property on the bound view model, so a caller can discover `telemetry*` probes. */
    fun numberNames(): List<String> {
        if (!RiveBridgeNative.PROBE_READBACK) return emptyList()
        val out = ByteArray(NAME_BUFFER_BYTES)
        if (RiveThread.call { native.rive_bridge_vm_number_names(openHandle(), out, out.size) } <= 0) return emptyList()
        return Native.toString(out).split('\n').filter { it.isNotBlank() }
    }

    /** The view-model path [com.letta.mobile.avatar.rive.RiveAvatarRuntime] writes through. */
    override val inputSink: RiveInputSink = object : RiveInputSink {
        override fun setNumber(input: String, value: Float) = RiveThread.post {
            if (!closed) native.rive_bridge_vm_set_number(handle, input, value)
        }

        override fun setBoolean(input: String, value: Boolean) = RiveThread.post {
            if (!closed) native.rive_bridge_vm_set_boolean(handle, input, if (value) 1 else 0)
        }

        override fun setEnum(input: String, key: String) = RiveThread.post {
            if (!closed) native.rive_bridge_vm_set_enum(handle, input, key)
        }

        override fun setColor(input: String, argb: Int) = RiveThread.post {
            if (!closed) native.rive_bridge_vm_set_color(handle, input, argb)
        }

        override fun fire(input: String) = RiveThread.post {
            if (!closed) native.rive_bridge_vm_fire(handle, input)
        }
    }

    override fun close() = RiveThread.call {
        if (closed) return@call
        closed = true
        // Serialised behind queued work. Both the scene and JNA allocation are gone on return.
        try {
            native.rive_bridge_destroy(handle)
        } finally {
            buffer?.close()
            buffer = null
            pixels = null
        }
    }

    companion object {
        /** Room for a few hundred property names; the bridge refuses rather than truncate. */
        private const val NAME_BUFFER_BYTES = 16 * 1024

        /** Confined to RiveThread; survives scene and native context disposal for the process lifetime. */
        internal var rendererActiveReported = false

        fun create(): RiveDesktopScene = RiveThread.call {
            create(RiveBridgeNative.INSTANCE, checkNotNull(RiveBridgeNative.PATH), RiveBridgeNative.ADVANCE_ACTIVITY)
        }

        internal fun create(native: RiveBridgeNative, libraryPath: String, advanceActivity: Boolean): RiveDesktopScene = RiveThread.call {
            val name = ByteArray(NAME_BUFFER_BYTES)
            val handle = native.rive_bridge_create(name, name.size)
                ?: error("rive_bridge_create failed: ${Native.toString(name)}")
            RiveDesktopScene(native, handle, Native.toString(name), advanceActivity, libraryPath)
        }
    }
}
