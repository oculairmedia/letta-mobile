package com.letta.mobile.data.shell.layout

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer

/**
 * Where a [ShellLayoutConfig] is kept between launches: one encoded config under [key] in a
 * [SecureSettingsStore], the small settings port every platform binds (Android's shared
 * preferences, the desktop's properties file, the browser's localStorage). It is read once, when
 * the store is made, so the shell's first frame already draws the saved layout.
 *
 * Nothing stored, or a stored value that is not a readable config, reads as
 * [ShellLayoutConfig.Default]; a readable one is [ShellLayoutConfig.upgraded] on the way in.
 * [config] follows the saves made through this store.
 *
 * Beside the config, under the sibling key `<key>.history`, the store keeps the layouts that
 * [commit] replaced, newest last and at most [HISTORY_LIMIT] of them, so [undo] can step back
 * through changes after a restart. A plain [save] keeps no history.
 *
 * @param settings the settings port the config is kept in.
 * @param key the settings key of this config; [forBackend] gives one per backend config.
 */
class ShellLayoutConfigStore(private val settings: SecureSettingsStore, private val key: String) {
    private val lock = SynchronizedObject()
    private val current = MutableStateFlow(read())
    private val historyKey = key + HISTORY_SUFFIX
    private var past: List<ShellLayoutConfig> = readHistory()

    /** The layout the shell draws: the stored config, upgraded, or the default. */
    val config: StateFlow<ShellLayoutConfig> = current.asStateFlow()

    /** The layouts [commit] replaced, oldest first: what [undo] steps back through. */
    val history: List<ShellLayoutConfig>
        get() = synchronized(lock) { past }

    /** Keeps [config] for later launches and publishes it, upgraded, to [ShellLayoutConfigStore.config]. */
    fun save(config: ShellLayoutConfig) {
        val upgraded = config.upgraded()
        settings.putString(key, upgraded.encode())
        current.value = upgraded
    }

    /**
     * Makes [config] the layout and keeps the one it replaces in [history], dropping the oldest
     * entry past [HISTORY_LIMIT]. With [expected], nothing happens unless the layout is still
     * [expected] (a change computed from it is not applied over a newer one).
     *
     * @return the layout replaced, or null when nothing changed: [config] already is the layout,
     *   or the layout is no longer [expected].
     */
    fun commit(config: ShellLayoutConfig, expected: ShellLayoutConfig? = null): ShellLayoutConfig? = synchronized(lock) {
        val before = current.value
        val next = config.upgraded()
        if (next == before || (expected != null && expected != before)) return null
        keepHistory((past + before).takeLast(HISTORY_LIMIT))
        save(next)
        before
    }

    /**
     * Restores the newest layout in [history] and drops it from there. With [ifCurrent], nothing
     * happens unless the layout is still [ifCurrent] (undoing one change never undoes a later one).
     *
     * @return the layout restored, or null when there is none or the layout moved on.
     */
    fun undo(ifCurrent: ShellLayoutConfig? = null): ShellLayoutConfig? = synchronized(lock) {
        val previous = past.lastOrNull() ?: return null
        if (ifCurrent != null && ifCurrent != current.value) return null
        keepHistory(past.dropLast(1))
        save(previous)
        previous
    }

    /** [commit] of [ShellLayoutConfig.Default]: undoable like any other change. */
    fun resetToDefault(): ShellLayoutConfig? = commit(ShellLayoutConfig.Default)

    private fun keepHistory(configs: List<ShellLayoutConfig>) {
        past = configs
        settings.putString(historyKey, LettaJson.persisted.encodeToString(historySerializer, configs))
    }

    private fun read(): ShellLayoutConfig = settings.getString(key)?.let(ShellLayoutConfig::decode) ?: ShellLayoutConfig.Default

    private fun readHistory(): List<ShellLayoutConfig> = settings.getString(historyKey)?.let { text ->
        runCatching { LettaJson.persisted.decodeFromString(historySerializer, text) }.getOrNull()
    }.orEmpty().map(ShellLayoutConfig::upgraded).takeLast(HISTORY_LIMIT)

    /** The per-backend store and the history's bounds. */
    companion object {
        /** How many replaced layouts the history keeps. */
        const val HISTORY_LIMIT = 20

        /** What the history's settings key adds to the config's key. */
        const val HISTORY_SUFFIX = ".history"

        private val historySerializer = ListSerializer(ShellLayoutConfig.serializer())

        /**
         * The store for the backend config [backend] (null before one is configured), beside that
         * backend's sidebar layout: switching backends never draws another backend's lanes.
         */
        fun forBackend(settings: SecureSettingsStore, backend: LettaConfig?): ShellLayoutConfigStore =
            ShellLayoutConfigStore(settings, key = "shell.${backend?.id.orEmpty()}.lanes")
    }
}
