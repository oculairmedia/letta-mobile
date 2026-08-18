package com.letta.mobile.desktop.data

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Persisted state for [com.letta.mobile.data.desktopshell.ShellLayoutState]
 * (letta-mobile-o5m90): whether the user explicitly collapsed the capability/
 * history sidebar, and their preferred expanded width.
 *
 * `null` fields mean "no explicit preference recorded" — the caller should
 * fall back to [com.letta.mobile.data.desktopshell.ShellLayoutReducer]'s
 * width-based default rather than treating an absent record as "expanded".
 */
data class PersistedShellLayout(
    val collapsedPreference: Boolean,
    val sidebarWidthDp: Float?,
)

/**
 * Small dedicated store for desktop shell layout, kept beside (not merged
 * into) [DesktopFileSecureSettingsStore]: layout preferences are not secrets
 * and don't belong in that store's generic key/value blob.
 *
 * Two properties distinguish this from [DesktopFileSecureSettingsStore]:
 *  - Writes are atomic (temp file + rename) so a crash mid-write cannot
 *    corrupt the file or leave a half-written properties blob.
 *  - Every key is namespaced by backend config id, so switching backends
 *    (self-hosted URL, iroh identity, …) never restores another backend's
 *    sidebar state.
 */
class DesktopShellLayoutStore(
    private val path: Path = defaultPath(),
) {
    @Synchronized
    fun load(backendConfigId: String): PersistedShellLayout? {
        val properties = readProperties() ?: return null
        val collapsedRaw = properties.getProperty(collapsedKey(backendConfigId)) ?: return null
        val collapsed = collapsedRaw.toBooleanStrictOrNull() ?: return null
        val width = properties.getProperty(widthKey(backendConfigId))?.toFloatOrNull()
        return PersistedShellLayout(collapsedPreference = collapsed, sidebarWidthDp = width)
    }

    @Synchronized
    fun save(backendConfigId: String, layout: PersistedShellLayout) {
        val properties = readProperties() ?: Properties()
        properties.setProperty(collapsedKey(backendConfigId), layout.collapsedPreference.toString())
        if (layout.sidebarWidthDp != null) {
            properties.setProperty(widthKey(backendConfigId), layout.sidebarWidthDp.toString())
        } else {
            properties.remove(widthKey(backendConfigId))
        }
        writeAtomically(properties)
    }

    private fun collapsedKey(backendConfigId: String) = "shell.$backendConfigId.sidebar.collapsed"
    private fun widthKey(backendConfigId: String) = "shell.$backendConfigId.sidebar.widthDp"

    private fun readProperties(): Properties? {
        if (!Files.exists(path)) return null
        return try {
            Properties().also { properties ->
                Files.newInputStream(path).use(properties::load)
            }
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
        val tmp = Files.createTempFile(parent, "shell-layout", ".tmp")
        try {
            Files.newOutputStream(tmp).use<OutputStream, Unit> { output ->
                properties.store(output, "Letta Desktop shell layout")
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Some filesystems (notably certain network/cross-volume
                // setups) don't support ATOMIC_MOVE; fall back to a plain
                // replace rather than failing the write outright.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    companion object {
        fun defaultPath(): Path = defaultDesktopStateDirectory().resolve("shell-layout.properties")
    }
}
