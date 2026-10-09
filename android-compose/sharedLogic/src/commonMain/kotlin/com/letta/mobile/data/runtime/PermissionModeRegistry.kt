package com.letta.mobile.data.runtime

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/**
 * letta-mobile-bzvro.13 (F13): the persisted DEFAULT permission mode, used by every runtime that has
 * no mode of its own. Replaces reads of [RuntimePermissionDefaults.DEFAULT_MODE] on the seams that take
 * a provider; that constant stays the value of a store that holds nothing, so an install that never
 * touched the setting keeps running Unrestricted.
 *
 * It also owns THE app's one [modes] registry: the registry reads the default and persists the
 * per-conversation choices in the same store, so every gateway built from these settings (a rebuild
 * after a reconnect, a restart) sees the same modes.
 */
class PermissionModeSettings(
    private val store: SecureSettingsStore,
    private val key: String = KEY,
) {
    private val _defaultMode = MutableStateFlow(read())
    val defaultMode: StateFlow<AppServerPermissionMode> = _defaultMode.asStateFlow()

    /** The per-runtime modes over this default; one per app. */
    val modes: PermissionModeRegistry = PermissionModeRegistry(defaultMode, store)

    /**
     * Stores and adopts [mode]. Returns false, leaving the default unchanged, when the store
     * rejected the write: memory never claims a default the next start would not find.
     */
    fun setDefaultMode(mode: AppServerPermissionMode): Boolean {
        val saved = runCatching { store.putString(key, mode.wireValue()) }
            .onFailure { Telemetry.event("PermissionModeSettings", "defaultMode.writeFailed", "error" to it.message, level = Telemetry.Level.WARN) }
            .isSuccess
        if (saved) _defaultMode.value = mode
        return saved
    }

    /** Anything unreadable or unknown reads as the product default rather than failing a runtime start. */
    private fun read(): AppServerPermissionMode =
        runCatching { store.getString(key) }.getOrNull()
            ?.let(AppServerPermissionMode::fromWireValue)
            ?: RuntimePermissionDefaults.DEFAULT_MODE

    companion object {
        const val KEY = "runtime.default_permission_mode"
    }
}

/**
 * The mode shown for one runtime. [mode] is what it runs in (or, where [appliesOnStart], what it
 * will start in); [pending] is a change sent and waiting for the server's `update_device_status`;
 * [unconfirmed] is a requested change whose outcome is unknown (the echo was lost or the send
 * failed): the server may be running either [mode] or [unconfirmed], so neither is claimed.
 */
@Immutable
data class PermissionModeState(
    val mode: AppServerPermissionMode,
    val pending: AppServerPermissionMode? = null,
    val unconfirmed: AppServerPermissionMode? = null,
    /** Chosen before the conversation's runtime exists: carried by its `runtime_start`. */
    val appliesOnStart: Boolean = false,
    /** The choice could not be written to the store: it holds for this session only. */
    val notSaved: Boolean = false,
) {
    /** The last requested change was not confirmed by the server. */
    val failed: Boolean get() = unconfirmed != null
}

/** How a requested mode change ended. */
enum class ModeChangeResult {
    /** The server echoed the mode (`update_device_status`). */
    Confirmed,

    /** No runtime is running, so nothing was sent: the next `runtime_start` carries the mode. */
    AppliesOnStart,

    /** Sent (or not), but no matching echo: the server's mode is unknown. */
    Unconfirmed,
}

/**
 * letta-mobile-bzvro.13 (F13): the permission mode of each `{agent, conversation}` runtime.
 *
 * What it records, and why each rule exists:
 * - The mode a runtime was STARTED with (or the server later reported) is that runtime's own entry
 *   ([observed]). A later change of the default therefore only reaches runtimes that have not
 *   started; one that has keeps showing, and enforcing, what the server actually runs.
 * - A choice is persisted per conversation in [store] and wins over the default, so a rebuilt
 *   gateway or an app restart never relaxes a conversation the person made stricter.
 * - [change] is optimistic in what it SHOWS (a pending request) but pessimistic in what it RECORDS:
 *   the mode only moves once the server echoed it. A change sent for a runtime that does not exist
 *   yet is not confirmed by anything: it waits for the `runtime_start` that carries it.
 * - While a change is pending or unconfirmed, [modeFor] answers the STRICTER of the running and the
 *   requested mode: tightening takes effect at once, loosening only once the server confirms it.
 * - Overlapping changes for one runtime: only the latest settles the entry.
 */
class PermissionModeRegistry(
    private val defaultMode: StateFlow<AppServerPermissionMode>,
    private val store: SecureSettingsStore? = null,
) {
    private data class Entry(
        /** What the runtime runs in; null until a `runtime_start` carried a mode or the server reported one. */
        val mode: AppServerPermissionMode? = null,
        /** A mode asked for and not yet confirmed (for an unstarted runtime: the persisted choice). */
        val requested: AppServerPermissionMode? = null,
        val unconfirmed: Boolean = false,
        val seq: Int = 0,
        val unsaved: Boolean = false,
    )

    private val entries = MutableStateFlow<Map<AppServerRuntimeScope, Entry>>(emptyMap())
    private val seqs = atomic(0)

    private fun entryOf(all: Map<AppServerRuntimeScope, Entry>, runtime: AppServerRuntimeScope): Entry =
        all[runtime] ?: Entry(requested = persistedChoice(runtime))

    /**
     * The mode to start the runtime with, and the one to enforce: never looser than what the server
     * may be running.
     */
    fun modeFor(runtime: AppServerRuntimeScope): AppServerPermissionMode {
        val entry = entryOf(entries.value, runtime)
        val running = entry.mode ?: return entry.requested ?: defaultMode.value
        return entry.requested?.let { stricter(running, it) } ?: running
    }

    fun observe(runtime: AppServerRuntimeScope): Flow<PermissionModeState> =
        combine(entries, defaultMode) { all, default ->
            val entry = entryOf(all, runtime)
            val running = entry.mode
            PermissionModeState(
                mode = running ?: entry.requested ?: default,
                pending = entry.requested.takeIf { running != null && !entry.unconfirmed },
                unconfirmed = entry.requested.takeIf { running != null && entry.unconfirmed },
                appliesOnStart = running == null && entry.requested != null,
                notSaved = entry.unsaved,
            )
        }

    /**
     * Asks for [mode] on the runtime. [apply] performs the change and says how it ended. Returns
     * true only when the server confirmed it.
     */
    suspend fun change(
        runtime: AppServerRuntimeScope,
        mode: AppServerPermissionMode,
        apply: suspend (AppServerPermissionMode) -> ModeChangeResult,
    ): Boolean {
        val seq = seqs.incrementAndGet()
        // Memory follows the store: a choice that could not be written is flagged, not silently kept.
        val saved = persist(runtime, mode)
        update(runtime) { it.copy(requested = mode, unconfirmed = false, seq = seq, unsaved = !saved) }
        val result = try {
            apply(mode)
        } catch (cancelled: CancellationException) {
            settle(runtime, seq, mode, ModeChangeResult.Unconfirmed)
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            ModeChangeResult.Unconfirmed
        }
        settle(runtime, seq, mode, result)
        return result == ModeChangeResult.Confirmed
    }

    private fun settle(runtime: AppServerRuntimeScope, seq: Int, mode: AppServerPermissionMode, result: ModeChangeResult) {
        update(runtime) { entry ->
            when {
                entry.seq != seq -> entry
                result == ModeChangeResult.Confirmed -> entry.copy(mode = mode, requested = null, unconfirmed = false)
                // The runtime is gone: the next start (which reads the request) is what applies it.
                result == ModeChangeResult.AppliesOnStart -> entry.copy(mode = null)
                else -> entry.copy(unconfirmed = true)
            }
        }
    }

    /**
     * The runtime is in [mode]: its `runtime_start` carried it, or the server reported it (this
     * client's change echoed, or another client changed it). An outstanding request is settled by it
     * when it is that mode, or when its outcome was unknown. Returns the choice a runtime that has just
     * started still wants (it was made while the runtime was starting), for the caller to send.
     */
    fun observed(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode): AppServerPermissionMode? {
        var wasStarted = true
        var next = Entry()
        update(runtime) { entry ->
            wasStarted = entry.mode != null
            next = when {
                entry.requested == mode || entry.unconfirmed -> entry.copy(mode = mode, requested = null, unconfirmed = false)
                // A runtime that started without the choice made while it was starting: the choice is
                // still wanted, and its outcome is not known until it is sent.
                !wasStarted && entry.requested != null -> entry.copy(mode = mode, unconfirmed = true)
                else -> entry.copy(mode = mode)
            }
            next
        }
        // Anything stricter than the product default is remembered (a restart must not relax it), but
        // never while a different choice is outstanding: that would overwrite what the person picked.
        if (next.requested == null && worthRemembering(runtime, mode)) persist(runtime, mode)
        return next.requested.takeUnless { wasStarted }
    }

    private fun worthRemembering(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode) =
        mode != RuntimePermissionDefaults.DEFAULT_MODE || persistedChoice(runtime) != null

    /** Drops what is held for [runtime] (its conversation was removed). */
    fun forget(runtime: AppServerRuntimeScope) {
        entries.update { it - runtime }
        runCatching { store?.remove(storeKey(runtime)) }
    }

    private fun update(key: AppServerRuntimeScope, change: (Entry) -> Entry) {
        entries.update { all -> all + (key to change(entryOf(all, key))) }
    }

    /** Length-prefixed, so no pair of ids can spell another pair's key. */
    private fun storeKey(runtime: AppServerRuntimeScope) =
        "$KEY_PREFIX${runtime.agentId.length}:${runtime.agentId}:${runtime.conversationId}"

    /**
     * The stored choice. A value that is present but unreadable (corrupt, or written by a newer
     * version) is read as the stricter of the default and Standard: never looser than the person may
     * have asked for. (The default key itself falls back to the product default, as before.)
     */
    private fun persistedChoice(runtime: AppServerRuntimeScope): AppServerPermissionMode? {
        val raw = runCatching { store?.getString(storeKey(runtime)) }.getOrNull() ?: return null
        return AppServerPermissionMode.fromWireValue(raw) ?: stricter(defaultMode.value, AppServerPermissionMode.Standard)
    }

    /** True when the choice is in the store. */
    private fun persist(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode): Boolean =
        runCatching { store?.putString(storeKey(runtime), mode.wireValue()) }
            .onFailure { Telemetry.event("PermissionModeRegistry", "choice.writeFailed", "error" to it.message, level = Telemetry.Level.WARN) }
            .isSuccess

    private companion object {
        const val KEY_PREFIX = "runtime.permission_mode."
    }
}

/** The more restrictive of two modes: the one that approves less on its own. */
internal fun stricter(a: AppServerPermissionMode, b: AppServerPermissionMode): AppServerPermissionMode =
    if (a.restrictiveness() >= b.restrictiveness()) a else b

private fun AppServerPermissionMode.restrictiveness(): Int = when (this) {
    AppServerPermissionMode.Unrestricted -> 0
    AppServerPermissionMode.AcceptEdits -> 1
    AppServerPermissionMode.Standard -> 2
    AppServerPermissionMode.Strict -> 3
}

internal fun AppServerPermissionMode.wireValue(): String = when (this) {
    AppServerPermissionMode.Standard -> "standard"
    AppServerPermissionMode.AcceptEdits -> "acceptEdits"
    AppServerPermissionMode.Strict -> "strict"
    AppServerPermissionMode.Unrestricted -> "unrestricted"
}

/**
 * bzvro.13: `change_device_state{mode}` confirmed by the echo. A runtime this engine has not started is NOT
 * confirmed by anything: nothing is sent, and its `runtime_start` carries the mode the provider returns.
 */
suspend fun AppServerTurnEngine.setPermissionMode(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode): ModeChangeResult {
    val started = leases.peek(TurnRuntimeKey(runtime.agentId, runtime.conversationId))?.runtimeScope
        ?: return ModeChangeResult.AppliesOnStart
    return if (deviceState.changePermissionMode(started, mode)) ModeChangeResult.Confirmed else ModeChangeResult.Unconfirmed
}
