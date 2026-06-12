package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.LettaConfig
import java.util.Locale

data class EmbeddedLettaCodeModelSelection(
    val modelHandle: String,
    val modelPath: String?,
    val runtime: String,
    val accelerator: String,
    val maxTokens: Int,
    /**
     * Custom OpenAI-compatible endpoint (letta-mobile-3icw7). When set, the
     * embedded runtime routes LLM calls here instead of starting the
     * on-device LiteRT bridge; no .litertlm model is required.
     */
    val customProviderBaseUrl: String? = null,
    val customProviderApiKey: String? = null,
) {
    val isCustomProvider: Boolean
        get() = customProviderBaseUrl != null

    val openAiModelId: String
        get() = modelHandle.toOpenAiModelId()

    val lettaCodeModelHandle: String
        get() = "lmstudio/$openAiModelId"

    val startKey: String
        get() = listOf(
            modelHandle,
            modelPath.orEmpty(),
            runtime,
            accelerator,
            maxTokens.toString(),
            customProviderBaseUrl.orEmpty(),
        ).joinToString("|")

    companion object {
        const val DEFAULT_MODEL_HANDLE = "local/default"
        const val DEFAULT_MODEL_RUNTIME = "litert-lm"
        const val DEFAULT_ACCELERATOR = "gpu"
        const val DEFAULT_MAX_TOKENS = 4096
        fun from(config: LettaConfig): EmbeddedLettaCodeModelSelection {
            val customBaseUrl = config.localProviderBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            return EmbeddedLettaCodeModelSelection(
                modelHandle = if (customBaseUrl != null) {
                    config.localProviderModel.normalizedOr(DEFAULT_MODEL_HANDLE)
                } else {
                    config.localModelHandle.normalizedOr(DEFAULT_MODEL_HANDLE)
                },
                modelPath = config.localModelPath?.trim()?.takeIf { it.isNotBlank() },
                runtime = config.localModelRuntime.normalizedOr(DEFAULT_MODEL_RUNTIME).lowercase(Locale.US),
                accelerator = config.localModelAccelerator.normalizedOr(DEFAULT_ACCELERATOR).lowercase(Locale.US),
                maxTokens = config.localModelMaxTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS,
                customProviderBaseUrl = customBaseUrl,
                customProviderApiKey = config.localProviderApiKey?.trim()?.takeIf { it.isNotBlank() },
            )
        }
    }
}

private fun String?.normalizedOr(defaultValue: String): String =
    this?.trim()?.takeIf { it.isNotBlank() } ?: defaultValue

private fun String.toOpenAiModelId(): String =
    trim()
        .removePrefix("local/")
        .removePrefix("lmstudio/")
        .removePrefix("llama-cpp/")
        .removePrefix("llama.cpp/")
        .ifBlank { "default" }
