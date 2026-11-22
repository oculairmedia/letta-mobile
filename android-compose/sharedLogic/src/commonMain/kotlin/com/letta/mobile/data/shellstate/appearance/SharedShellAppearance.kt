package com.letta.mobile.data.shellstate.appearance

import com.letta.mobile.data.shellstate.AppearanceKeys
import com.letta.mobile.data.shellstate.AppearanceValues
import com.letta.mobile.data.shellstate.HostAppearance
import com.letta.mobile.data.shellstate.LegacyAppearance
import com.letta.mobile.data.shellstate.ShellStateEditOutcome
import com.letta.mobile.data.shellstate.ShellStateSnapshotCache
import com.letta.mobile.data.shellstate.shared.SharedShellState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * [HostAppearance] over the shared shell state client ([SharedShellState]), the same one every other area uses.
 * [mirror] is the cache [shell] restores from and mirrors into; it is held here only so that wiping this device's
 * local state wipes it too.
 *
 * Shown, per field: the host's entry in the document [SharedShellState] holds (a push from another device replaces it;
 * while the host is out of reach it is the last document read, restored from the device's disposable cache, never an
 * edit); else a device-local choice made before the move, until it is migrated; else nothing.
 *
 * [run] migrates the old device-local choices: whenever the host's document has been read, it proposes the choices the
 * host has no entry for yet, each as a compare-and-set against "absent", so a choice another device already made is
 * never overwritten (the host's wins); then it forgets the device-local ones, so a choice is proposed once.
 */
class SharedShellAppearance(
    private val shell: SharedShellState,
    private val mirror: ShellStateSnapshotCache = ShellStateSnapshotCache.None,
    private val legacy: LegacyAppearance = LegacyAppearance.None,
) : HostAppearance {
    /** The appearance to show now; changes whenever any source above does. */
    override val values: Flow<AppearanceValues> = combine(shell.state, legacy.values) { state, old ->
        AppearanceValues.of(state.snapshot?.entries).orElse(old)
    }.distinctUntilChanged()

    /** The host's last document as it stands, without waiting for [values]. */
    override val current: AppearanceValues get() = AppearanceValues.of(shell.state.value.snapshot?.entries)

    /** Records [theme] on the host. */
    override suspend fun setTheme(name: String): ShellStateEditOutcome =
        shell.set(AppearanceKeys.THEME, JsonPrimitive(name))

    /** Records [preset] on the host. */
    override suspend fun setThemePreset(name: String): ShellStateEditOutcome =
        shell.set(AppearanceKeys.THEME_PRESET, JsonPrimitive(name))

    /** Records [enabled] on the host. */
    override suspend fun setDynamicColor(enabled: Boolean): ShellStateEditOutcome =
        shell.set(AppearanceKeys.DYNAMIC_COLOR, JsonPrimitive(enabled))

    /** Records [name] and, when [dynamicColor] is given, dynamic colour, as one proposal. */
    override suspend fun selectThemePreset(name: String, dynamicColor: Boolean?): ShellStateEditOutcome {
        val edits = listOfNotNull(
            SharedShellState.Edit(AppearanceKeys.THEME_PRESET, JsonPrimitive(name)),
            dynamicColor?.let { SharedShellState.Edit(AppearanceKeys.DYNAMIC_COLOR, JsonPrimitive(it)) },
        )
        return shell.propose(edits)
    }

    /** Follows the host's shell state and migrates the device-local choices, until cancelled. */
    override suspend fun run() = coroutineScope {
        launch { shell.run() }
        migrate()
    }

    /** Wipes everything this device kept: the mirror of the host's document, and any device-local choice not yet migrated. */
    override suspend fun clearLocal() {
        mirror.clear()
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
        val proposal = edits.map { SharedShellState.Edit(it.key, it.value) }
        val outcome = if (proposal.isEmpty()) ShellStateEditOutcome.APPLIED else shell.propose(proposal)
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
