package com.letta.mobile.desktop.data

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Persisted chat font scale for the desktop client.
 *
 * This is the same user-facing preference Android drives through
 * `ISettingsRepository.setChatFontScale` and reads back via
 * `LocalChatFontScale` — 1.0 is the app's own type sizes, and values scale
 * from there. Desktop cannot share Android's path directly: `designsystem`
 * (which owns `LocalChatFontScale`) is not a desktop dependency, and the only
 * `ISettingsRepository` desktop constructs, `ActiveConfigSettingsRepository`,
 * stubs the accessor pair (`flowOf(1f)` / `Unit`). Unifying those is a shared
 * `jvmAndAndroid` change that Android also consumes, so it is tracked
 * separately rather than smuggled in here.
 *
 * Deliberately NOT stored in the secure settings store: a font size is not a
 * secret, and [DesktopShellLayoutStore] already establishes that non-secret
 * preferences get their own atomic properties file. Unlike that store this key
 * is NOT namespaced by backend config id — type size is a property of the
 * person reading the screen, not of the backend they happen to be pointed at.
 */
class DesktopChatFontScaleStore(
    private val path: Path = defaultPath(),
) {
    @Synchronized
    fun load(): Float? = readProperties()
        ?.getProperty(CHAT_FONT_SCALE_KEY)
        ?.toFloatOrNull()

    @Synchronized
    fun save(scale: Float) {
        val properties = readProperties() ?: Properties()
        properties.setProperty(CHAT_FONT_SCALE_KEY, scale.toString())
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
        val tmp = Files.createTempFile(parent, "chat-font-scale", ".tmp")
        try {
            Files.newOutputStream(tmp).use<OutputStream, Unit> { output ->
                properties.store(output, "Letta Desktop chat font scale")
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    companion object {
        private const val CHAT_FONT_SCALE_KEY = "chat.fontScale"

        fun defaultPath(): Path = defaultDesktopStateDirectory().resolve("chat-font-scale.properties")
    }
}
