package com.letta.mobile.data.shellstate.appearance

import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.shellstate.shared.ShellStateEditOutcome
import com.letta.mobile.data.shellstate.shared.SharedShellState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

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

/**
 * The person's appearance (theme, colour preset, dynamic colour), kept on the host in the shell state document so
 * every paired device shows the same one, and edited through the same [SharedShellState] every other area uses.
 *
 * Shown, per field: the host's entry in the document [SharedShellState] holds (a push from another device
 * replaces it; while the host is out of reach it is the last document read, restored from the device's disposable
 * cache, never an edit); else a device-local choice made before the move, until it is migrated; else nothing, and
 * the platform default applies.
 *
 * An edit is a proposal to the host: it is refused while the host is out of reach, does not serve shell state, or
 * the entry changed under it ([ShellStateEditOutcome]); nothing is queued, and what is shown changes only when the
 * host's answer or a push does.
 *
 * [run] migrates the old device-local choices: whenever the host's document has been read, it proposes the choices
 * the host has no entry for yet, each as a compare-and-set against "absent", so a choice another device already made
 * is never overwritten (the host's wins); then it forgets the device-local ones. It proposes at most until the host
 * holds an answer for every one, so a choice is proposed once.
 */
class HostAppearance(
    private val shell: SharedShellState,
    private val legacy: LegacyAppearance = LegacyAppearance.None,
) {
    /** The appearance to show now; changes whenever any source above does. */
    val values: Flow<AppearanceValues> = combine(shell.state, legacy.values) { state, old ->
        AppearanceValues.of(state.snapshot?.entries).orElse(old)
    }.distinctUntilChanged()

    /** Records [theme] on the host. */
    suspend fun setTheme(theme: AppTheme): ShellStateEditOutcome =
        shell.set(AppearanceKeys.THEME, JsonPrimitive(theme.name))

    /** Records [preset] on the host. */
    suspend fun setThemePreset(preset: ThemePreset): ShellStateEditOutcome =
        shell.set(AppearanceKeys.THEME_PRESET, JsonPrimitive(preset.name))

    /** Records [enabled] on the host. */
    suspend fun setDynamicColor(enabled: Boolean): ShellStateEditOutcome =
        shell.set(AppearanceKeys.DYNAMIC_COLOR, JsonPrimitive(enabled))

    /** Follows the host's shell state and migrates the device-local choices, until cancelled. */
    suspend fun run() = coroutineScope {
        launch { shell.run() }
        migrate()
    }

    /** Wipes the copy of the host's document this device kept for offline display. */
    fun clearCache() = shell.clearCache()

    /** Wipes everything this device kept: that copy, and any device-local choice not yet migrated. */
    suspend fun clearLocal() {
        shell.clearCache()
        legacy.clear()
    }

    private suspend fun migrate() {
        shell.state.map { it.editable }.distinctUntilChanged().filter { it }.collect { migrateUntilSettled() }
    }

    /** Proposes what is left, again after a conflict (which refreshed the document), up to [MAX_ATTEMPTS] times. */
    private suspend fun migrateUntilSettled() {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS && migrateOnce() == Migration.CONFLICT) attempt += 1
    }

    private suspend fun migrateOnce(): Migration {
        val old = legacy.values.first()
        if (old.isEmpty) return Migration.DONE
        val edits = old.editsExcept(shell.state.value.entries.keys)
        val outcome = if (edits.isEmpty()) ShellStateEditOutcome.APPLIED else shell.propose(edits)
        return when (outcome) {
            ShellStateEditOutcome.APPLIED -> Migration.DONE.also { legacy.clear() }
            ShellStateEditOutcome.CONFLICT -> Migration.CONFLICT
            ShellStateEditOutcome.OFFLINE, ShellStateEditOutcome.UNSUPPORTED, ShellStateEditOutcome.REFUSED -> Migration.LATER
        }
    }

    /** How one migration attempt ended. */
    private enum class Migration {
        /** Nothing is left on this device. */
        DONE,

        /** Another device wrote an entry first; the document was read again. */
        CONFLICT,

        /** The host did not take it now (out of reach, refused); the next time the document is read it is tried again. */
        LATER,
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}
