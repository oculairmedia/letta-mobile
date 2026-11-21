package com.letta.mobile.data.shellstate

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The person's appearance (theme, colour preset, dynamic colour), kept on the host in the shell state document so
 * every paired device shows the same one. The settings repository depends on this port; the implementation over the
 * shared shell state client (`SharedShellAppearance`) is bound by the app, so the repository never imports the
 * transport.
 *
 * An edit is a proposal to the host: it is refused while the host is out of reach, does not serve shell state, or
 * the entry changed under it ([ShellStateEditOutcome]); nothing is queued, and what is shown changes only when the
 * host's answer or a push does.
 */
interface HostAppearance {
    /** The appearance to show now: the host's, else what this device last saw of it, else a pre-move local choice, else none. */
    val values: Flow<AppearanceValues>

    /** Records the theme named [name] on the host. */
    suspend fun setTheme(name: String): ShellStateEditOutcome

    /** Records the colour preset named [name] on the host. */
    suspend fun setThemePreset(name: String): ShellStateEditOutcome

    /** Records [enabled] on the host. */
    suspend fun setDynamicColor(enabled: Boolean): ShellStateEditOutcome

    /** Follows the host's shell state and migrates the device-local choices, until cancelled. */
    suspend fun run()

    /** Wipes everything this device kept: the mirror of the host's document, and any device-local choice not yet migrated. */
    suspend fun clearLocal()

    companion object {
        /** No host behind it (tests, previews): shows no choice and refuses every edit. */
        val None: HostAppearance = object : HostAppearance {
            override val values: Flow<AppearanceValues> = flowOf(AppearanceValues.NONE)
            override suspend fun setTheme(name: String) = ShellStateEditOutcome.OFFLINE
            override suspend fun setThemePreset(name: String) = ShellStateEditOutcome.OFFLINE
            override suspend fun setDynamicColor(enabled: Boolean) = ShellStateEditOutcome.OFFLINE
            override suspend fun run() = Unit
            override suspend fun clearLocal() = Unit
        }
    }
}

/**
 * What this device recorded about appearance before it moved onto the host's shell state document: the old
 * device-local theme choices. They are read to be proposed to a host that has none yet, and then removed.
 */
interface LegacyAppearance {
    /** The device-local choices as they stand; none once migrated, or when this device never chose. */
    val values: Flow<AppearanceValues>

    /** Forgets the device-local choices. */
    suspend fun clear()

    companion object {
        /** A device with nothing to migrate. */
        val None: LegacyAppearance = object : LegacyAppearance {
            override val values: Flow<AppearanceValues> = flowOf(AppearanceValues.NONE)
            override suspend fun clear() = Unit
        }
    }
}
