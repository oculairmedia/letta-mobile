package com.letta.mobile.desktop.data

import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayout
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayoutCodec
import com.letta.mobile.ui.chat.surface.LayoutPrefStorage
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * letta-mobile-zxk2n.29: the minimised chat's layout (mascot size, bar width, reply hold) across
 * launches. Same pattern as [DesktopChatDockGeometryStore]: a non-secret preference in its own
 * atomically written properties file, its keys and ranges the shared [CanvasBubbleLayoutCodec]'s.
 * A missing or damaged file reads as the desktop's defaults.
 */
class DesktopCanvasBubbleLayoutStore(
    private val path: Path = defaultPath(),
) {
    @Synchronized
    fun load(): CanvasBubbleLayout {
        val properties = readProperties() ?: return CanvasBubbleLayout.Desktop
        val values = properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        return CanvasBubbleLayoutCodec.decode(values, CanvasBubbleLayout.Desktop)
    }

    @Synchronized
    fun save(layout: CanvasBubbleLayout) {
        val properties = Properties()
        CanvasBubbleLayoutCodec.encode(layout).forEach { (key, value) -> properties.setProperty(key, value) }
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
        val tmp = Files.createTempFile(parent, "canvas-bubble", ".tmp")
        try {
            Files.newOutputStream(tmp).use<OutputStream, Unit> { output ->
                properties.store(output, "Letta Desktop minimised chat layout")
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

    /** This file as the shared bubble-layout host's storage, written on the IO pool. */
    fun asLayoutPrefStorage(): LayoutPrefStorage<CanvasBubbleLayout> =
        LayoutPrefStorage(load = ::load, save = ::save, io = Dispatchers.IO)

    companion object {
        fun defaultPath(): Path = defaultDesktopStateDirectory().resolve("canvas-bubble-layout.properties")
    }
}
