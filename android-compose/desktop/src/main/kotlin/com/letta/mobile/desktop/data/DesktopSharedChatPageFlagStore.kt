package com.letta.mobile.desktop.data

import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * letta-mobile-bglj6.1: the persisted half of the "Shared chat page (preview)" toggle.
 *
 * Desktop has no persisted `ISettingsRepository` (it runs on `ActiveConfigSettingsRepository`), so
 * this follows [DesktopChatFontScaleStore]'s pattern: a non-secret preference in its own atomically
 * written properties file. Not namespaced by backend: which chat page a person prefers is a property
 * of the person, not of the backend they happen to be pointed at.
 */
class DesktopSharedChatPageFlagStore(
    private val path: Path = defaultPath(),
) {
    @Synchronized
    fun load(): Boolean = readProperties()
        ?.getProperty(SHARED_CHAT_PAGE_KEY)
        ?.toBooleanStrictOrNull()
        ?: false

    @Synchronized
    fun save(enabled: Boolean) {
        val properties = readProperties() ?: Properties()
        properties.setProperty(SHARED_CHAT_PAGE_KEY, enabled.toString())
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
        val tmp = Files.createTempFile(parent, "shared-chat-page", ".tmp")
        try {
            Files.newOutputStream(tmp).use<OutputStream, Unit> { output ->
                properties.store(output, "Letta Desktop shared chat page preview")
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
        private const val SHARED_CHAT_PAGE_KEY = "chat.sharedPage.enabled"

        fun defaultPath(): Path = defaultDesktopStateDirectory().resolve("shared-chat-page.properties")
    }
}
