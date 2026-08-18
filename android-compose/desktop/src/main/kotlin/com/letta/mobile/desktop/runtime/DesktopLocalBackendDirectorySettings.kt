package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.runtime.LocalBackendDirectoryValidation
import com.letta.mobile.data.storage.SecureSettingsStore
import java.io.File

/**
 * Live override for [DesktopLocalRuntimeHost.backendDirectory] — the folder
 * the BUNDLED local letta-code runtime stores its agents, conversations, and
 * provider credentials under (`LETTA_LOCAL_BACKEND_DIR`).
 *
 * Plain mutable holder rather than plumbing the value through every call
 * site: [DesktopLocalRuntimeHost] is a singleton `object` whose
 * `backendDirectory()` is called from spawn code that doesn't have easy
 * access to Compose state or the settings store. Set once at startup (from
 * the persisted setting) and again whenever the user changes it in Settings.
 */
internal object DesktopLocalBackendDirectoryPreference {
    @Volatile
    var override: File? = null
}

/**
 * Persistence + validation for the "Local backend data directory" desktop
 * setting. Stored in the same [SecureSettingsStore] as the other desktop
 * settings (backend config, avatar style, etc.) — this value isn't a secret,
 * but reuses the existing store so it survives the same way.
 */
internal object DesktopLocalBackendDirectorySettings {
    private const val KEY = "letta.desktop.localBackendDirectory"

    /** The persisted path, or null when unset (the default applies). */
    fun readStoredPath(store: SecureSettingsStore): String? =
        store.getString(KEY)?.trim()?.takeIf { it.isNotBlank() }

    /** The directory that will actually be used — the stored override, or the default. */
    fun readEffectiveDirectory(store: SecureSettingsStore): File =
        readStoredPath(store)?.let(::File) ?: DesktopLocalRuntimeHost.defaultBackendDirectory()

    /**
     * Applies the persisted setting (if any) to [DesktopLocalBackendDirectoryPreference]
     * so [DesktopLocalRuntimeHost.backendDirectory] reflects it from the very
     * first runtime spawn. Call once, early in app bootstrap, before any code
     * path could start the local runtime.
     */
    fun applyStoredOverride(store: SecureSettingsStore) {
        DesktopLocalBackendDirectoryPreference.override = readStoredPath(store)?.let(::File)
    }

    /**
     * Validates and persists [path], then updates the live override so the
     * NEXT local runtime spawn picks it up. Does not itself restart the
     * runtime — callers should follow up the way the local runtime provider
     * settings card does (`chatController.retryConnection()` when the active
     * backend is the local runtime).
     */
    fun save(store: SecureSettingsStore, path: String): LocalBackendDirectoryValidation.Result {
        val trimmed = path.trim()
        val result = LocalBackendDirectoryValidation.validate(trimmed)
        if (result is LocalBackendDirectoryValidation.Result.Valid) {
            store.putString(KEY, trimmed)
            DesktopLocalBackendDirectoryPreference.override = File(trimmed)
        }
        return result
    }

    /** Clears the override, reverting to the default `~/.letta-mobile/local-backend`. */
    fun resetToDefault(store: SecureSettingsStore) {
        store.remove(KEY)
        DesktopLocalBackendDirectoryPreference.override = null
    }
}
