package com.letta.mobile.desktop.input

import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The native pen bridge (native/tablet_input).
 *
 * AWT never reports a stylus — Compose Desktop's runtime has no stylus path, so a tablet arrives
 * as an ordinary mouse at best and on some machines not at all — so the pen is read from the OS
 * itself, through Windows Ink, and handed back here.
 *
 * Events arrive flattened, four floats each: kind, x, y, pressure. One poll carries a whole frame
 * of pen motion, which is why this is a poll rather than a callback per event.
 */
internal object TabletBridge {

    /** kind values, matching the Rust side. */
    const val KIND_DOWN = 0
    const val KIND_UP = 1
    const val KIND_MOVE = 2
    const val KIND_IN = 3
    const val KIND_OUT = 4

    /** Floats per event. */
    const val STRIDE = 4

    /** Reported when the tool has no pressure axis. */
    const val NO_PRESSURE = -1f

    private val libraryName: String = when {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "letta_tablet_input.dll"
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "libletta_tablet_input.dylib"
        else -> "libletta_tablet_input.so"
    }

    /** True once the native library is in memory. False means no pen support, not a failure. */
    val available: Boolean by lazy { runCatching { load() }.isSuccess }

    private fun load() {
        val suffix = libraryName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream(libraryName)) {
            "$libraryName is not on the classpath"
        }
        val extracted = Files.createTempFile("letta-tablet-", suffix)
        resource.use { Files.copy(it, extracted, StandardCopyOption.REPLACE_EXISTING) }
        extracted.toFile().deleteOnExit()
        System.load(extracted.toAbsolutePath().toString())
    }

    /** Opens a bridge for a window; 0 when the machine has no tablet service to talk to. */
    @JvmStatic external fun nativeOpen(hwnd: Long): Long

    /** Everything since the last poll, flattened. */
    @JvmStatic external fun nativePoll(handle: Long): FloatArray

    @JvmStatic external fun nativeClose(handle: Long)
}
