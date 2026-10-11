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
    fun record(path: String) {
        if (path.isBlank() || !DirectoryPaths.isAbsolute(path)) return
        val wanted = DirectoryPaths.normalize(path)
        val next = (listOf(wanted) + load().filterNot { DirectoryPaths.same(it, wanted) }).take(limit)
        if (next == _recent.value) return
        _recent.value = next
        runCatching { store.putString(key, next.joinToString("\n")) }
    }

    /** Drops [path] (a folder that no longer exists). */
    fun forget(path: String) {
        val next = load().filterNot { DirectoryPaths.same(it, path) }
        _recent.value = next
        runCatching { if (next.isEmpty()) store.remove(key) else store.putString(key, next.joinToString("\n")) }
    }

    private fun load(): List<String> =
        runCatching { store.getString(key) }.getOrNull()
            ?.lines()
            ?.map(String::trim)
            ?.filter { it.isNotEmpty() && DirectoryPaths.isAbsolute(it) }
            ?.distinctBy(DirectoryPaths::normalize)
            ?.take(limit)
            .orEmpty()

    companion object {
        const val DEFAULT_KEY: String = "workspace.recent_folders"
        const val DEFAULT_LIMIT: Int = 8
    }
}
