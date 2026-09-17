package com.letta.mobile.desktop.avatar.rive

import com.letta.mobile.avatar.rive.RiveInputSink
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import kotlinx.coroutines.asCoroutineDispatcher
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * letta-mobile-0s5bi spike: the C ABI of `rive_desktop_bridge.dll`
 * (`avatar/renderer-rive/native/desktop`), which drives rive-runtime through the Rive Renderer's
 * D3D11 backend and reads frames back into memory.
 *
 * JNA rather than JNI because the bridge is plain C: no generated headers, and the desktop module
 * already ships JNA for its Win32 calls.
 */
internal interface RiveBridgeNative : Library {
    fun rive_bridge_create(adapterNameOut: ByteArray, adapterNameCap: Int): Pointer?
    fun rive_bridge_destroy(bridge: Pointer)
    fun rive_bridge_load(bridge: Pointer, bytes: ByteArray, length: Int, stateMachine: String?): Int
    fun rive_bridge_load_artboard(
        bridge: Pointer,
        bytes: ByteArray,
        length: Int,
        stateMachine: String?,
        artboard: String?,
    ): Int

    fun rive_bridge_artboard_width(bridge: Pointer): Float
    fun rive_bridge_artboard_height(bridge: Pointer): Float
    fun rive_bridge_advance(bridge: Pointer, seconds: Float)
    fun rive_bridge_render(bridge: Pointer, width: Int, height: Int, clearArgb: Int, rgbaOut: Pointer): Int
    fun rive_bridge_pointer(bridge: Pointer, kind: Int, x: Float, y: Float)
    fun rive_bridge_fire_trigger(bridge: Pointer, name: String): Int
    fun rive_bridge_set_number(bridge: Pointer, name: String, value: Float): Int
    fun rive_bridge_vm_set_number(bridge: Pointer, name: String, value: Float): Int
    fun rive_bridge_vm_get_number(bridge: Pointer, name: String, out: FloatArray): Int
    fun rive_bridge_vm_number_names(bridge: Pointer, out: ByteArray, cap: Int): Int
    fun rive_bridge_vm_set_enum(bridge: Pointer, name: String, key: String): Int
    fun rive_bridge_vm_fire(bridge: Pointer, name: String): Int
    fun rive_bridge_vm_set_color(bridge: Pointer, name: String, argb: Int): Int
    fun rive_bridge_vm_set_boolean(bridge: Pointer, name: String, value: Int): Int

    companion object {
        /**
         * Where the bridge DLL is: `-Drive.bridge.path`, else `rive_desktop_bridge.dll` in the
         * packaged app's resources dir, else next to the working directory. Null when none exists,
         * which is how surfaces decide to fall back to the gradient orb.
         */
        val PATH: String? by lazy {
            val candidates = listOfNotNull(
                System.getProperty("rive.bridge.path"),
                System.getProperty("compose.application.resources.dir")?.let { "$it/rive_desktop_bridge.dll" },
                "rive_desktop_bridge.dll",
            )
            candidates.firstOrNull { java.io.File(it).isFile }
        }

        /** True when a bridge DLL is present and this OS can host it (Windows / D3D11 only today). */
        val AVAILABLE: Boolean by lazy {
            PATH != null && System.getProperty("os.name").orEmpty().startsWith("Windows")
        }

        val INSTANCE: RiveBridgeNative by lazy {
            Native.load(PATH ?: error("Set -Drive.bridge.path to rive_desktop_bridge.dll"), RiveBridgeNative::class.java)
        }

        /**
         * A symbol lookup that tolerates a missing export, so a bench built against a newer header
         * still runs against a DLL someone built before the probe exports existed: JNA only fails
         * when the method is actually called, and these are the calls the bench guards.
         */
        private fun exports(export: BridgeExport): Boolean = PATH != null &&
            runCatching { NativeLibrary.getInstance(PATH).getFunction(export.symbol) }.isSuccess

        /** `rive_bridge_vm_get_number` + `rive_bridge_vm_number_names`: the telemetry readback. */
        val PROBE_READBACK: Boolean by lazy {
            exports(BridgeExport.VM_GET_NUMBER) && exports(BridgeExport.VM_NUMBER_NAMES)
        }

        /** `rive_bridge_load_artboard`: loading an artboard other than the file's default. */
        val ARTBOARD_BY_NAME: Boolean by lazy { exports(BridgeExport.LOAD_ARTBOARD) }
    }
}

/** The bridge exports newer than the original entry points, which an older DLL may not carry. */
internal enum class BridgeExport(val symbol: String) {
    LOAD_ARTBOARD("rive_bridge_load_artboard"),
    VM_GET_NUMBER("rive_bridge_vm_get_number"),
    VM_NUMBER_NAMES("rive_bridge_vm_number_names"),
}

/**
 * What [RiveDesktopScene.load] binds: a state machine by name (null for the artboard's default) on an
 * artboard by name. [artboard] null takes the file's default artboard - the only thing the bridge
 * could do before `rive_bridge_load_artboard` existed, and still the production path. A name is how
 * the bench asks for `Harness`; an old DLL cannot honour it, and says so rather than showing the
 * default.
 */
data class RiveSceneTarget(val stateMachine: String? = null, val artboard: String? = null)

enum class RivePointer(internal val code: Int) { MOVE(0), DOWN(1), UP(2), EXIT(3) }

/**
 * The one thread every native scene is driven from. A D3D11 immediate context is single-threaded,
 * and the render is a GPU round trip with a blocking readback - so it runs here, off the UI
 * thread, and the input writes queue behind it in order rather than racing it. One thread for
 * every scene: the scenes are cheap and serialising them keeps the readbacks from contending.
 */
internal object RiveThread {
    private val thread = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rive-render").apply { isDaemon = true }.also { thread.set(it) }
    }

    /** For the frame loop: suspend on the UI thread while the scene advances and renders here. */
    val dispatcher: kotlinx.coroutines.CoroutineDispatcher = executor.asCoroutineDispatcher()

    private val onThread: Boolean get() = Thread.currentThread() === thread.get()

    /** Queues [block] behind whatever the thread is doing; input writes go this way. */
    fun post(block: () -> Unit) {
        if (onThread) block() else executor.execute(block)
    }

    /** Runs [block] on the thread and waits for it; creation, loading, stills and the bench go this way. */
    fun <T> call(block: () -> T): T = if (onThread) block() else executor.submit(block).get()
}

/**
 * One native scene: a D3D11 device, a loaded file, and its state machine.
 *
 * Not thread-safe by construction - a D3D11 immediate context is single-threaded - so every native
 * call is routed through [RiveThread]: writes are posted, reads and renders are called and
 * awaited. Callers may use any thread; the frame loop suspends on [RiveThread.dispatcher] so the
 * readback never stalls the UI.
 */
class RiveDesktopScene private constructor(
    private val native: RiveBridgeNative,
    private val handle: Pointer,
    val adapterName: String,
) : AutoCloseable {
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
                "this rive_desktop_bridge.dll predates rive_bridge_load_artboard; rebuild it to load '$artboard'"
            }
            native.rive_bridge_load_artboard(openHandle(), bytes, bytes.size, target.stateMachine, artboard)
        }
        check(code == 0) { "rive_bridge_load failed ($code)" }
    }

    fun advance(seconds: Float) = RiveThread.post { native.rive_bridge_advance(openHandle(), seconds) }

    private var lastFrameNanos = 0L

    /**
     * Advances to the frame clock's [nowNanos] once per frame: a scene shared by several surfaces
     * (the sidebar and the hero draw the same agent) would otherwise be advanced by each of them
     * and run at a multiple of real time.
     */
    fun advanceTo(nowNanos: Long) {
        if (nowNanos == lastFrameNanos) return
        val dt = if (lastFrameNanos == 0L) 0f else ((nowNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = nowNanos
        if (dt > 0f) advance(dt)
    }

    /**
     * Renders into a reused native buffer and copies it into a reused byte array: premultiplied
     * RGBA, top row first. The array is the scene's and is overwritten by the next render - read it
     * before then (the surface rasterises it into a Skia image at once).
     */
    fun render(width: Int, height: Int, clearArgb: Int = 0): ByteArray = RiveThread.call {
        val size = width.toLong() * height * 4
        val target = buffer?.takeIf { it.size() == size } ?: Memory(size).also { buffer = it }
        val code = native.rive_bridge_render(openHandle(), width, height, clearArgb, target)
        check(code == 0) { "rive_bridge_render failed ($code)" }
        val out = pixels?.takeIf { it.size.toLong() == size } ?: ByteArray(size.toInt()).also { pixels = it }
        target.read(0, out, 0, out.size)
        out
    }

    fun pointer(kind: RivePointer, x: Float, y: Float) = RiveThread.post { native.rive_bridge_pointer(openHandle(), kind.code, x, y) }

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
    val inputSink: RiveInputSink = object : RiveInputSink {
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

    override fun close() {
        if (closed) return
        closed = true
        // Behind every queued write, so nothing touches a destroyed handle.
        RiveThread.post {
            native.rive_bridge_destroy(handle)
            buffer = null
            pixels = null
        }
    }

    companion object {
        /** Room for a few hundred property names; the bridge refuses rather than truncate. */
        private const val NAME_BUFFER_BYTES = 16 * 1024

        fun create(): RiveDesktopScene = RiveThread.call {
            val native = RiveBridgeNative.INSTANCE
            val name = ByteArray(256)
            val handle = native.rive_bridge_create(name, name.size)
                ?: error("rive_bridge_create failed: no D3D11 feature level 11.1 device")
            RiveDesktopScene(native, handle, Native.toString(name))
        }
    }
}
