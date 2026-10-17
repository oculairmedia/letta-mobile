package com.letta.mobile.runtime.local.modelcatalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val catalogJson = Json { ignoreUnknownKeys = true }

@Serializable
data class EmbeddedModelCatalogEntry(
    val name: String,
    val modelId: String,
    val modelFile: String,
    val sizeInBytes: Long,
    val estimatedPeakMemoryInBytes: Long,
    val defaultConfig: EmbeddedModelDefaultConfig,
    val taskTypes: List<String>,
    val downloadUrl: String? = null,
    val checksumSha256: String? = null,
    val requiresAuth: Boolean = false,
    @SerialName("supported")
    private val supportedFlag: Boolean? = null,
    val unsupportedReason: String? = null,
    /**
     * letta-mobile-by4vm.121: the model takes image input (the AI Edge Gallery allowlist's own
     * field name). The on-device bridge advertises it to letta-code, which otherwise strips images.
     */
    val llmSupportImage: Boolean = false,
) {
    val id: String = "$modelId/$modelFile"
    val isLiteRtLmCompatible: Boolean = modelFile.endsWith(".litertlm", ignoreCase = true)
    val isSupported: Boolean = supportedFlag != false && isLiteRtLmCompatible
    val primaryAccelerator: String = defaultConfig.accelerators.firstOrNull()?.lowercase() ?: "cpu"
}

@Serializable
data class EmbeddedModelDefaultConfig(
    val maxTokens: Int,
    val accelerators: List<String>,
)

class EmbeddedModelCatalogParser {
    fun parse(rawJson: String): List<EmbeddedModelCatalogEntry> =
        catalogJson.decodeFromString<List<EmbeddedModelCatalogEntry>>(rawJson)
}

fun List<EmbeddedModelCatalogEntry>.supportedEmbeddedModels(): List<EmbeddedModelCatalogEntry> = filter { it.isSupported }
