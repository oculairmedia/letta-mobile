package com.letta.mobile.data.model

/**
 * letta-mobile-ckmub.9: reads a stored backend mode on every platform (Android DataStore JSON,
 * desktop properties, web local storage, the automation bootstrap).
 *
 * Letta Cloud is not offered for now, so a config saved with the retired `"CLOUD"` mode becomes a
 * Remote host ([LettaConfig.Mode.SELF_HOSTED]) config instead of failing to load. When it still
 * points at Letta Cloud itself, its address and API key are dropped (that key belongs to Letta
 * Cloud and must never be sent to another host), so the user sets up a remote App Server; a custom
 * address is kept. Either way the config carries [CLOUD_NOTICE] until it is saved again.
 */
object LettaConfigMigration {
    /** The persisted name of the retired Letta Cloud mode. */
    const val RETIRED_CLOUD_MODE = "CLOUD"

    const val CLOUD_NOTICE =
        "Letta Cloud is no longer offered. This backend is now a Remote host: enter your App Server's " +
            "address (iroh://… or ws://…) and save."

    private val LETTA_CLOUD_HOSTS = setOf("api.letta.com", "app.letta.com", "letta.com", "www.letta.com")

    /** The mode a stored name stands for; the retired Cloud mode reads as Remote host. Null when unknown. */
    fun modeOf(stored: String?): LettaConfig.Mode? {
        val name = stored?.trim()?.uppercase() ?: return null
        if (name == RETIRED_CLOUD_MODE) return LettaConfig.Mode.SELF_HOSTED
        return LettaConfig.Mode.entries.firstOrNull { it.name == name }
    }

    /** True when [stored] is the retired Cloud mode, so [migrate] has work to do. */
    fun isRetiredCloud(stored: String?): Boolean = stored?.trim()?.uppercase() == RETIRED_CLOUD_MODE

    /**
     * [config] as read from storage whose mode was stored as [storedMode]. Unchanged unless that
     * mode was the retired Cloud mode.
     */
    fun migrate(storedMode: String?, config: LettaConfig): LettaConfig {
        if (!isRetiredCloud(storedMode)) return config
        val cloudAddress = isLettaCloudUrl(config.serverUrl)
        return config.copy(
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = if (cloudAddress) "" else config.serverUrl,
            accessToken = if (cloudAddress) null else config.accessToken,
            migrationNotice = CLOUD_NOTICE,
        )
    }

    /** True for an address on Letta Cloud's own hosts (any scheme, path or port). */
    fun isLettaCloudUrl(url: String?): Boolean {
        val host = url?.trim()
            ?.substringAfter("://", missingDelimiterValue = url.trim())
            ?.substringBefore('/')
            ?.substringBefore('?')
            ?.substringAfter('@')
            ?.substringBefore(':')
            ?.lowercase()
            ?: return false
        return host in LETTA_CLOUD_HOSTS
    }
}
