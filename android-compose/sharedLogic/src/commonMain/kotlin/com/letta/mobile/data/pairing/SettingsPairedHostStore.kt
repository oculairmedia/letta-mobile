package com.letta.mobile.data.pairing

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.ISettingsRepository

/** The slice of the settings repository pairing needs (kept small so it is easy to fake). */
interface BackendConfigSink {
    fun currentConfigs(): List<LettaConfig>

    suspend fun save(config: LettaConfig)

    suspend fun activate(configId: String)
}

/**
 * Persists a newly paired host as an Iroh backend config and activates it
 * through the platform's settings repository — which
 * keeps configs in encrypted storage (Android EncryptedSharedPreferences /
 * Keystore, desktop encrypted settings store).
 *
 * No credential is stored: the invite was one-time and the paired device
 * authenticates by its Iroh NodeId, so `accessToken` is null. Re-pairing the
 * same host updates its existing config instead of adding a duplicate.
 */
class SettingsPairedHostStore(private val sink: BackendConfigSink) : PairedHostStore {
    constructor(settings: ISettingsRepository) : this(
        object : BackendConfigSink {
            override fun currentConfigs(): List<LettaConfig> = settings.configs.value

            override suspend fun save(config: LettaConfig) = settings.saveConfig(config)

            override suspend fun activate(configId: String) = settings.setActiveConfigId(configId)
        },
    )

    override suspend fun saveAndActivate(host: PairedHost) {
        val existing = sink.currentConfigs().firstOrNull { it.serverUrl.contains(host.nodeId, ignoreCase = true) }
        val config = LettaConfig(
            id = existing?.id ?: "paired-${host.nodeId.take(ID_NODE_CHARS)}",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = host.serverUrl,
            accessToken = null,
        )
        sink.save(config)
        sink.activate(config.id)
    }

    private companion object {
        const val ID_NODE_CHARS = 12
    }
}
