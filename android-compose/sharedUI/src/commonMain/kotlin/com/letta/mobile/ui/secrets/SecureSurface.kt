package com.letta.mobile.ui.secrets

import androidx.compose.runtime.Composable
import com.letta.mobile.data.secrets.client.SecureSurfaceCapabilities

/**
 * A platform's protection of the window that shows the secure form, applied while the form is on
 * screen: [enter] when it appears, [exit] when it goes. Every [enter] is paired with one [exit], and
 * an implementation restores whatever the window had before the first [enter] once the last
 * [exit] runs, so two secure surfaces on one window never undo each other.
 */
interface SecureSurface {
    /** Applies the protections; answers which of them are really in place. */
    fun enter(): SecureSurfaceCapabilities

    /** Lifts what [enter] applied, unless another secure surface on the same window still needs it. */
    fun exit()
}

/**
 * Protects the window hosting the calling composition for as long as the caller is composed (see
 * each platform's actual for what that guarantees) and answers which protections are in place.
 * The secure form calls it once; it draws nothing.
 */
@Composable
expect fun SecureSurfaceEffect(): SecureSurfaceCapabilities

/**
 * Hosts a secret text field: where the platform lets an app reach the keyboard's options, the
 * keyboard inside [content] is asked not to learn from or suggest what is typed. Elsewhere it only
 * draws [content].
 */
@Composable
expect fun SecureTextInput(content: @Composable () -> Unit)

/** What a [RestoreLedger.release] tells its caller. */
sealed interface LedgerRelease<out T> {
    /** Another holder still needs the setting: change nothing. */
    data object StillHeld : LedgerRelease<Nothing>

    /** The last holder left: put the setting back to [previous]. */
    data class Restore<out T>(
        /** What the setting was before the first holder changed it. */
        val previous: T,
    ) : LedgerRelease<T>

    /** Nothing was held for that key (an unpaired release): change nothing. */
    data object NotHeld : LedgerRelease<Nothing>
}

/**
 * Reference-counts a setting per [K] (a window or a view) shared by several secure surfaces: the
 * first holder records the setting's value before changing it, and only the last holder to leave
 * restores it, to that recorded value. Pure logic, so the counting is tested without a device; not
 * thread safe (the UI thread owns it).
 */
class RestoreLedger<K : Any, T> {
    private val holders = HashMap<K, Held<T>>()

    /** How many keys are held now. */
    val size: Int get() = holders.size

    /** Records a holder of [key] whose setting is [current] now; answers true for the first holder, who applies the setting. */
    fun acquire(key: K, current: T): Boolean {
        val held = holders[key]
        if (held != null) {
            holders[key] = held.copy(count = held.count + 1)
            return false
        }
        holders[key] = Held(current, 1)
        return true
    }

    /** Drops a holder of [key]; see [LedgerRelease] for what the caller does. */
    fun release(key: K): LedgerRelease<T> {
        val held = holders[key] ?: return LedgerRelease.NotHeld
        if (held.count > 1) {
            holders[key] = held.copy(count = held.count - 1)
            return LedgerRelease.StillHeld
        }
        holders.remove(key)
        return LedgerRelease.Restore(held.previous)
    }

    private data class Held<T>(val previous: T, val count: Int)
}
