package com.letta.mobile.data.repository

import com.letta.mobile.data.model.LettaConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object LettaConfigPersistence {
    private val json = Json { ignoreUnknownKeys = true }

    fun decodeList(configsJson: String): List<LettaConfig> =
        try {
            json.decodeFromString<List<LettaConfigData>>(configsJson).map { it.toLettaConfig() }
        } catch (_: Exception) {
            emptyList()
        }

    fun encodeList(configs: List<LettaConfig>): String {
        val configData = configs.map { LettaConfigData.fromLettaConfig(it) }
        return json.encodeToString(configData)
    }

    @Serializable
    private data class LettaConfigData(
        val id: String,
        val mode: String,
        val serverUrl: String,
        val accessToken: String? = null,
        val localModelPath: String? = null,
        val localModelHandle: String? = null,
        val localModelRuntime: String? = null,
        val localModelAccelerator: String? = null,
        val localModelMaxTokens: Int? = null,
        val localProviderBaseUrl: String? = null,
        val localProviderApiKey: String? = null,
        val localProviderModel: String? = null,
    ) {
        fun toLettaConfig() = LettaConfig(
            id = id,
            mode = LettaConfig.Mode.valueOf(mode),
            serverUrl = serverUrl,
            accessToken = accessToken,
            localModelPath = localModelPath,
            localModelHandle = localModelHandle,
            localModelRuntime = localModelRuntime,
            localModelAccelerator = localModelAccelerator,
            localModelMaxTokens = localModelMaxTokens,
            localProviderBaseUrl = localProviderBaseUrl,
            localProviderApiKey = localProviderApiKey,
            localProviderModel = localProviderModel,
        )

        companion object {
            fun fromLettaConfig(config: LettaConfig) = LettaConfigData(
                id = config.id,
                mode = config.mode.name,
                serverUrl = config.serverUrl,
                accessToken = config.accessToken,
                localModelPath = config.localModelPath,
                localModelHandle = config.localModelHandle,
                localModelRuntime = config.localModelRuntime,
                localModelAccelerator = config.localModelAccelerator,
                localModelMaxTokens = config.localModelMaxTokens,
                localProviderBaseUrl = config.localProviderBaseUrl,
                localProviderApiKey = config.localProviderApiKey,
                localProviderModel = config.localProviderModel,
            )
        }
    }
}
