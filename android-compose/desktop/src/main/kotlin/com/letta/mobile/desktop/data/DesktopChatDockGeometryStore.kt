package com.letta.mobile.desktop.data

import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.ChatDockGeometryMath
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * letta-mobile-bglj6.1: where the person left the docked chat panel over the canvas, across
 * launches. Same pattern as [DesktopOpenChatsOnCanvasStore]: a non-secret preference in its own
 * atomically written properties file. A missing or damaged file reads as the default placement.
 */
class DesktopChatDockGeometryStore(
    private val path: Path = defaultPath(),
) {
    @Synchronized
    fun load(): ChatDockGeometry {
        val properties = readProperties() ?: return ChatDockGeometry.Default
        val default = ChatDockGeometry.Default
        return ChatDockGeometryMath.sanitize(
            ChatDockGeometry(
                anchorX = properties.float(KEY_ANCHOR_X) ?: default.anchorX,
                anchorY = properties.float(KEY_ANCHOR_Y) ?: default.anchorY,
                widthDp = properties.float(KEY_WIDTH),
                heightDp = properties.float(KEY_HEIGHT),
                collapsed = properties.getProperty(KEY_COLLAPSED)?.toBooleanStrictOrNull() ?: default.collapsed,
            ),
        )
    }

    @Synchronized
    fun save(geometry: ChatDockGeometry) {
        val properties = Properties()
        properties.setProperty(KEY_ANCHOR_X, geometry.anchorX.toString())
        properties.setProperty(KEY_ANCHOR_Y, geometry.anchorY.toString())
        geometry.widthDp?.let { properties.setProperty(KEY_WIDTH, it.toString()) }
        geometry.heightDp?.let { properties.setProperty(KEY_HEIGHT, it.toString()) }
        properties.setProperty(KEY_COLLAPSED, geometry.collapsed.toString())
        writeAtomically(properties)
    }

    private fun Properties.float(key: String): Float? = getProperty(key)?.toFloatOrNull()

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
        val tmp = Files.createTempFile(parent, "chat-dock", ".tmp")
        try {
            Files.newOutputStream(tmp).use<OutputStream, Unit> { output ->
                properties.store(output, "Letta Desktop docked chat panel placement")
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
        private const val KEY_ANCHOR_X = "chat.dock.anchorX"
        private const val KEY_ANCHOR_Y = "chat.dock.anchorY"
        private const val KEY_WIDTH = "chat.dock.widthDp"
        private const val KEY_HEIGHT = "chat.dock.heightDp"
        private const val KEY_COLLAPSED = "chat.dock.collapsed"

        fun defaultPath(): Path = defaultDesktopStateDirectory().resolve("chat-dock.properties")
    }
}
