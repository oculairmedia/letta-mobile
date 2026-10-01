package com.letta.mobile.desktop.data

import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * letta-mobile-bglj6.1: the persisted "Open conversations on the canvas" preference.
 *
 * The canvas is the default view; turning this off opens conversations in the traditional
 * full-screen chat instead. A non-secret
 * preference in its own atomically written properties file, not namespaced by backend.
 * Missing or unreadable reads as on (the default).
 */
class DesktopOpenChatsOnCanvasStore(
    private val path: Path = defaultPath(),
) {
    @Synchronized
    fun load(): Boolean = readProperties()
        ?.getProperty(OPEN_ON_CANVAS_KEY)
        ?.toBooleanStrictOrNull()
        ?: true

    /** Blocking file write; callers keep it off the UI thread. */
    @Synchronized
    fun save(enabled: Boolean) {
        val properties = readProperties() ?: Properties()
        properties.setProperty(OPEN_ON_CANVAS_KEY, enabled.toString())
        writeAtomically(properties)
    }

    private fun readProperties(): Properties? {
        if (!Files.exists(path)) return null
        return try {
            Properties().also { properties -> Files.newInputStream(path).use(properties::load) }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun writeAtomically(properties: Properties) {
        val target = path.toAbsolutePath()
        val parent = requireNotNull(target.parent)
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, "open-chats-on-canvas", ".tmp")
        try {
            Files.newOutputStream(tmp).use<OutputStream, Unit> { output ->
                properties.store(output, "Letta Desktop open conversations on the canvas")
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    companion object {
        internal const val OPEN_ON_CANVAS_KEY = "chat.openOnCanvas"

        fun defaultPath(): Path = defaultDesktopStateDirectory().resolve("open-chats-on-canvas.properties")
    }
}
