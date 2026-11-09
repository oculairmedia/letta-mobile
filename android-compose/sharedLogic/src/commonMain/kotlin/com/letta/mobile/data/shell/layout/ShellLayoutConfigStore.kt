package com.letta.mobile.data.shell.layout

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * @param settings the settings port the config is kept in.
 * @param key the settings key of this config; [forBackend] gives one per backend config.
 */
class ShellLayoutConfigStore(private val settings: SecureSettingsStore, private val key: String) {
    private val current = MutableStateFlow(read())

    /** The layout the shell draws: the stored config, upgraded, or the default. */
    val config: StateFlow<ShellLayoutConfig> = current.asStateFlow()

    /** Keeps [config] for later launches and publishes it, upgraded, to [ShellLayoutConfigStore.config]. */
    fun save(config: ShellLayoutConfig) {
        val upgraded = config.upgraded()
        settings.putString(key, upgraded.encode())
        current.value = upgraded
    }

    private fun read(): ShellLayoutConfig = settings.getString(key)?.let(ShellLayoutConfig::decode) ?: ShellLayoutConfig.Default

    /** The per-backend store. */
    companion object {
        /**
         * The store for the backend config [backend] (null before one is configured), beside that
         * backend's sidebar layout: switching backends never draws another backend's lanes.
         */
        fun forBackend(settings: SecureSettingsStore, backend: LettaConfig?): ShellLayoutConfigStore =
            ShellLayoutConfigStore(settings, key = "shell.${backend?.id.orEmpty()}.lanes")
    }
}
