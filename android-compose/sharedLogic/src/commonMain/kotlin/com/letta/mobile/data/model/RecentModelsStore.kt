package com.letta.mobile.data.model

import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the recent-models list lives (most recent first). */
interface RecentModelsPersistence {
    fun load(): List<String>

    fun save(models: List<String>)
}

/**
 * letta-mobile-bzvro.18 (F18): the models the user switched to most recently, newest first, so the
 * picker can offer them before the full catalogue. [record] runs on a successful `update_model`.
 *
 * The persisted list keeps up to [STORED_LIMIT] entries (the letta-code TUI's own cap, since desktop
 * shares its `~/.letta/settings.json`); [recent] shows the first [visibleLimit].
 */
class RecentModelsStore(
    private val persistence: RecentModelsPersistence,
    private val visibleLimit: Int = DEFAULT_VISIBLE,
) {
    private var stored: List<String> = loadSafely()
    private val _recent = MutableStateFlow(stored.take(visibleLimit))
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    /** Moves [handle] to the front. A blank handle is ignored. */
    fun record(handle: String) {
        val wanted = handle.trim().takeIf { it.isNotEmpty() } ?: return
        // Re-read first: another client (the TUI) may have added entries since we loaded.
        val next = mostRecentFirst(wanted, loadSafely())
        if (next == stored) return
        stored = next
        _recent.value = next.take(visibleLimit)
        runCatching { persistence.save(next) }
    }

    /** Re-reads the persisted list (e.g. when the picker opens). */
    fun reload() {
        stored = loadSafely()
        _recent.value = stored.take(visibleLimit)
    }

    private fun loadSafely(): List<String> =
        runCatching { persistence.load() }.getOrDefault(emptyList()).filter { it.isNotBlank() }.distinct()

    companion object {
        const val DEFAULT_VISIBLE = 8
        const val STORED_LIMIT = 10

        fun mostRecentFirst(handle: String, current: List<String>): List<String> =
            (listOf(handle) + current.filter { it != handle }).take(STORED_LIMIT)
    }
}

/** The recent models in the app's own settings store, one per line (Android, and desktop without a TUI). */
class SettingsStoreRecentModels(
    private val store: SecureSettingsStore,
    private val key: String = DEFAULT_KEY,
) : RecentModelsPersistence {
    override fun load(): List<String> =
        store.getString(key)?.lines()?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

    override fun save(models: List<String>) {
        if (models.isEmpty()) store.remove(key) else store.putString(key, models.joinToString("\n"))
    }

    companion object {
        const val DEFAULT_KEY = "models.recent"
    }
}
