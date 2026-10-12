package com.letta.mobile.data.workspace.directory

import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * letta-mobile-zxk2n.6 (G32): the working directories the person picked most recently, newest
 * first, so the picker offers them before browsing. Kept in the app's own settings store (the
 * platform binds which one), one path per line.
 *
 * The per-conversation map itself is the App Server's: letta-code persists each conversation's
 * cwd (`cwd_map`, read with `get_cwd_map`) and applies a change from the next turn, so the client
 * keeps only this list and never a second copy of the map.
 */
class RecentFolders(
    private val store: SecureSettingsStore,
    private val key: String = DEFAULT_KEY,
    private val limit: Int = DEFAULT_LIMIT,
) {
    private val _recent = MutableStateFlow(load())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    /** Moves [path] to the front. A blank or relative path is ignored. */
    fun record(path: DevicePath) {
        if (path.value.isBlank() || !path.isAbsolute) return
        val wanted = path.normalized
        val next = (listOf(wanted.value) + load().filterNot { wanted.sameAs(DevicePath(it)) }).take(limit)
        if (next == _recent.value) return
        _recent.value = next
        runCatching { store.putString(key, next.joinToString(SEPARATOR)) }
    }

    /** Drops [path] (a folder that no longer exists). */
    fun forget(path: DevicePath) {
        val next = load().filterNot { path.sameAs(DevicePath(it)) }
        _recent.value = next
        runCatching { if (next.isEmpty()) store.remove(key) else store.putString(key, next.joinToString(SEPARATOR)) }
    }

    private fun load(): List<String> =
        runCatching { store.getString(key) }.getOrNull()
            ?.lines()
            ?.map(String::trim)
            ?.filter { it.isNotEmpty() && DevicePath(it).isAbsolute }
            ?.map { DevicePath(it).normalized.value }
            ?.distinct()
            ?.take(limit)
            .orEmpty()

    companion object {
        const val DEFAULT_KEY: String = "workspace.recent_folders"
        const val DEFAULT_LIMIT: Int = 8
        private const val SEPARATOR = "\n"
    }
}
