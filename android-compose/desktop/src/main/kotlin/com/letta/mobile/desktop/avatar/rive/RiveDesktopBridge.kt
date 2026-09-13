package com.letta.mobile.desktop.avatar.rive

import com.letta.mobile.avatar.rive.RiveInputSink
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
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
    fun rive_bridge_artboard_width(bridge: Pointer): Float
    fun rive_bridge_artboard_height(bridge: Pointer): Float
    fun rive_bridge_advance(bridge: Pointer, seconds: Float)
    fun rive_bridge_render(bridge: Pointer, width: Int, height: Int, clearArgb: Int, rgbaOut: Pointer): Int
    fun rive_bridge_pointer(bridge: Pointer, kind: Int, x: Float, y: Float)
    fun rive_bridge_fire_trigger(bridge: Pointer, name: String): Int
    fun rive_bridge_set_number(bridge: Pointer, name: String, value: Float): Int
    fun rive_bridge_vm_set_number(bridge: Pointer, name: String, value: Float): Int
    fun rive_bridge_vm_set_enum(bridge: Pointer, name: String, key: String): Int
    fun rive_bridge_vm_fire(bridge: Pointer, name: String): Int

    companion object {
        /** `-Drive.bridge.path=...\rive_desktop_bridge.dll`; the spike does not package the DLL. */
        val INSTANCE: RiveBridgeNative by lazy {
            val path = System.getProperty("rive.bridge.path")
                ?: error("Set -Drive.bridge.path to rive_desktop_bridge.dll")
            Native.load(path, RiveBridgeNative::class.java)
        }
    }
}

enum class RivePointer(internal val code: Int) { MOVE(0), DOWN(1), UP(2), EXIT(3) }

/**
 * One native scene: a D3D11 device, a loaded file, and its state machine.
 *
 * Not thread-safe by construction - a D3D11 immediate context is single-threaded - so every call
 * must come from the thread that created it. The Compose surface keeps all of them on the UI
 * thread's frame clock.
 */
class RiveDesktopScene private constructor(
    private val native: RiveBridgeNative,
    private val handle: Pointer,
    val adapterName: String,
) : AutoCloseable {
    private var buffer: Memory? = null
    private var closed = false

    val artboardWidth: Float get() = native.rive_bridge_artboard_width(handle)
    val artboardHeight: Float get() = native.rive_bridge_artboard_height(handle)

    fun load(bytes: ByteArray, stateMachine: String? = null) {
        val code = native.rive_bridge_load(handle, bytes, bytes.size, stateMachine)
        check(code == 0) { "rive_bridge_load failed ($code)" }
    }

    fun advance(seconds: Float) = native.rive_bridge_advance(handle, seconds)

    /** Renders into a reused native buffer and returns it: premultiplied RGBA, top row first. */
    fun render(width: Int, height: Int, clearArgb: Int = 0): ByteArray {
        val size = width.toLong() * height * 4
        val target = buffer?.takeIf { it.size() == size } ?: Memory(size).also { buffer = it }
        val code = native.rive_bridge_render(handle, width, height, clearArgb, target)
        check(code == 0) { "rive_bridge_render failed ($code)" }
        return target.getByteArray(0, size.toInt())
    }

    fun pointer(kind: RivePointer, x: Float, y: Float) = native.rive_bridge_pointer(handle, kind.code, x, y)

    fun fireTrigger(name: String): Boolean = native.rive_bridge_fire_trigger(handle, name) == 0

    fun setNumber(name: String, value: Float): Boolean = native.rive_bridge_set_number(handle, name, value) == 0

    /** The view-model path [com.letta.mobile.avatar.rive.RiveAvatarRuntime] writes through. */
    val inputSink: RiveInputSink = object : RiveInputSink {
        override fun setNumber(input: String, value: Float) {
            native.rive_bridge_vm_set_number(handle, input, value)
        }

        // The mascot contract has no booleans; nothing in the bridge writes one yet.
        override fun setBoolean(input: String, value: Boolean) = Unit

        override fun setEnum(input: String, key: String) {
            native.rive_bridge_vm_set_enum(handle, input, key)
        }

        override fun fire(input: String) {
            native.rive_bridge_vm_fire(handle, input)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        native.rive_bridge_destroy(handle)
        buffer = null
    }

    companion object {
        fun create(): RiveDesktopScene {
            val native = RiveBridgeNative.INSTANCE
            val name = ByteArray(256)
            val handle = native.rive_bridge_create(name, name.size)
                ?: error("rive_bridge_create failed: no D3D11 feature level 11.1 device")
            return RiveDesktopScene(native, handle, Native.toString(name))
        }
    }
}
