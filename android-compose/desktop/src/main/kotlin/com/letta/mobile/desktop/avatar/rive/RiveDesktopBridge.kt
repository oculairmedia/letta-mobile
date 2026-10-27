package com.letta.mobile.desktop.avatar.rive

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.FunctionMapper
import kotlinx.coroutines.asCoroutineDispatcher
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/** JNA declarations for the C ABI in `avatar/renderer-rive/native/desktop`; it drives Rive's native GPU renderer. */
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
    fun riveBridgeAdvanceIsActive(bridge: Pointer, seconds: Float): Int
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
        /** The platform's existing C ABI library; macOS has no native backend yet. */
        internal fun libraryName(osName: String): String? = when {
            osName.startsWith("Windows") -> "rive_desktop_bridge.dll"
            osName.startsWith("Linux") -> "librive_desktop_bridge.so"
            else -> null
        }

        /** Explicit overrides are authoritative: a typo must never load a different local bridge. */
        internal fun findPath(osName: String, explicit: String?, resources: String?, workingDir: String): String? {
            val name = libraryName(osName) ?: return null
            if (explicit != null) {
                check(java.io.File(explicit).isFile) { "Rive bridge not found: $explicit" }
                return java.io.File(explicit).absolutePath
            }
            // A packaged app must use its payload; a developer's working directory cannot mask it.
            val candidate = java.io.File(resources ?: workingDir, name)
            return candidate.takeIf { it.isFile }?.absolutePath
        }

        /** Platform library from an override, packaged resources, or the developer's working directory. */
        val PATH: String? by lazy {
            findPath(
                System.getProperty("os.name").orEmpty(),
                System.getProperty("rive.bridge.path"),
                System.getProperty("compose.application.resources.dir"),
                System.getProperty("user.dir"),
            )
        }

        /** Missing renderers are reported explicitly; distribution inspection also requires the native payload. */
        val AVAILABLE: Boolean by lazy {
            val name = libraryName(System.getProperty("os.name").orEmpty())
            (name != null && PATH != null).also { available ->
                if (name != null && !available) System.err.println("ERROR: Rive renderer missing: $name; set -Drive.bridge.path")
            }
        }

        val INSTANCE: RiveBridgeNative by lazy {
            Native.load(
                PATH ?: error("Native Rive renderer missing; set -Drive.bridge.path"),
                RiveBridgeNative::class.java,
                mapOf(Library.OPTION_FUNCTION_MAPPER to ADVANCE_ACTIVITY_MAPPER),
            )
        }

        private val ADVANCE_ACTIVITY_MAPPER = FunctionMapper { _, method ->
            if (method.name == "riveBridgeAdvanceIsActive") BridgeExport.ADVANCE_ACTIVITY.symbol else method.name
        }

        /** Checks whether [export] exists, so newer Kotlin can run against an older bridge DLL. */
        private fun exports(export: BridgeExport): Boolean = PATH != null &&
            runCatching { NativeLibrary.getInstance(PATH).getFunction(export.symbol) }.isSuccess

        /** `rive_bridge_vm_get_number` + `rive_bridge_vm_number_names`: the telemetry readback. */
        val PROBE_READBACK: Boolean by lazy {
            exports(BridgeExport.VM_GET_NUMBER) && exports(BridgeExport.VM_NUMBER_NAMES)
        }

        /** `rive_bridge_load_artboard`: loading an artboard other than the file's default. */
        val ARTBOARD_BY_NAME: Boolean by lazy { exports(BridgeExport.LOAD_ARTBOARD) }

        val ADVANCE_ACTIVITY: Boolean by lazy { exports(BridgeExport.ADVANCE_ACTIVITY) }
    }
}

/** The bridge exports newer than the original entry points, which an older DLL may not carry. */
internal enum class BridgeExport(val symbol: String) {
    LOAD_ARTBOARD("rive_bridge_load_artboard"),
    VM_GET_NUMBER("rive_bridge_vm_get_number"),
    VM_NUMBER_NAMES("rive_bridge_vm_number_names"),
    ADVANCE_ACTIVITY("rive_bridge_advance_is_active"),
}

/** Names the state machine and optional artboard loaded by [RiveDesktopScene.load]; old DLLs require the default artboard. */
data class RiveSceneTarget(val stateMachine: String? = null, val artboard: String? = null)

/**
 * The one thread every native scene is driven from. The native GPU context belongs to this thread,
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
