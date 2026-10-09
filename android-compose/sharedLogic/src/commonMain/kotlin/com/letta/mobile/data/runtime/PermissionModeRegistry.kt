package com.letta.mobile.data.runtime

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
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
 */
class PermissionModeSettings(
    private val store: SecureSettingsStore,
    private val key: String = KEY,
) {
    private val _defaultMode = MutableStateFlow(read())
    val defaultMode: StateFlow<AppServerPermissionMode> = _defaultMode.asStateFlow()

    fun setDefaultMode(mode: AppServerPermissionMode) {
        runCatching { store.putString(key, mode.wireValue()) }
        _defaultMode.value = mode
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

/** The mode shown for one runtime: the confirmed [mode], and a requested [pending] one not yet echoed. */
@Immutable
data class PermissionModeState(
    val mode: AppServerPermissionMode,
    val pending: AppServerPermissionMode? = null,
    /** The last requested change was not confirmed by the server. */
    val failed: Boolean = false,
)

/**
 * letta-mobile-bzvro.13 (F13): the permission mode of each `{agent, conversation}` runtime.
 *
 * A runtime with no choice of its own follows [defaultMode]. [change] is optimistic in what it
 * SHOWS (a [PermissionModeState.pending] request) but pessimistic in what it RECORDS: the confirmed
 * mode only moves once [change]'s `apply` reports the server echoed it (`update_device_status`, as
 * [DeviceStateChanger] waits for). [modeFor] is what a turn and a `runtime_start` read.
 */
class PermissionModeRegistry(private val defaultMode: StateFlow<AppServerPermissionMode>) {
    private data class Entry(
        val mode: AppServerPermissionMode? = null,
        val pending: AppServerPermissionMode? = null,
        val failed: Boolean = false,
    )

    private val entries = MutableStateFlow<Map<AppServerRuntimeScope, Entry>>(emptyMap())

    /** The mode in force for the runtime, confirmed or inherited; never a pending request. */
    fun modeFor(runtime: AppServerRuntimeScope): AppServerPermissionMode =
        entries.value[runtime]?.mode ?: defaultMode.value

    fun observe(runtime: AppServerRuntimeScope): Flow<PermissionModeState> =
        combine(entries, defaultMode) { all, default ->
            val entry = all[runtime]
            PermissionModeState(entry?.mode ?: default, entry?.pending, entry?.failed == true)
        }

    /**
     * Asks for [mode] on the runtime. [apply] performs the change and returns true when it was
     * confirmed. A runtime that has not started yet needs no wire change (its `runtime_start`
     * reads [modeFor]), so its `apply` just returns true.
     */
    suspend fun change(
        runtime: AppServerRuntimeScope,
        mode: AppServerPermissionMode,
        apply: suspend (AppServerPermissionMode) -> Boolean,
    ): Boolean {
        val key = runtime
        update(key) { it.copy(pending = mode, failed = false) }
        val confirmed = try {
            apply(mode)
        } catch (cancelled: CancellationException) {
            update(key) { it.copy(pending = null) }
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            false
        }
        update(key) { if (confirmed) Entry(mode = mode) else it.copy(pending = null, failed = true) }
        return confirmed
    }

    /** The server reported [mode] for the runtime on its own (another client changed it). */
    fun observed(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode) {
        update(runtime) { it.copy(mode = mode, failed = false) }
    }

    private fun update(key: AppServerRuntimeScope, change: (Entry) -> Entry) {
        entries.update { all -> all + (key to change(all[key] ?: Entry())) }
    }
}

internal fun AppServerPermissionMode.wireValue(): String = when (this) {
    AppServerPermissionMode.Standard -> "standard"
    AppServerPermissionMode.AcceptEdits -> "acceptEdits"
    AppServerPermissionMode.Strict -> "strict"
    AppServerPermissionMode.Unrestricted -> "unrestricted"
}
