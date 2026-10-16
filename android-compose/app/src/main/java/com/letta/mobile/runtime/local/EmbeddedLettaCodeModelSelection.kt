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

    val routesToOpenAiCompatibleProvider: Boolean
        get() = isCustomProvider

    val requiresOnDeviceModel: Boolean
        get() = !routesToOpenAiCompatibleProvider

    val effectiveProviderBaseUrl: String?
        get() = customProviderBaseUrl

    val effectiveProviderApiKey: String?
        get() = customProviderApiKey

    val openAiModelId: String
        get() = modelHandle.toOpenAiModelId()

    /** The agent model handle on the runtime's `lc-lmstudio` provider; [lettaCodeLookupId] maps it back to [openAiModelId]. */
    val lettaCodeModelHandle: String
        get() = "$LETTA_CODE_LMSTUDIO_HANDLE_PREFIX$openAiModelId"

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
        const val DEFAULT_LM_STUDIO_API_KEY = "not-needed"
        fun from(config: LettaConfig): EmbeddedLettaCodeModelSelection {
            val localModelPath = config.localModelPath?.trim()?.takeIf { it.isNotBlank() }
            val localModelHandle = config.localModelHandle?.trim()?.takeIf { it.isNotBlank() }
            val providerModel = config.localProviderModel?.trim()?.takeIf { it.isNotBlank() }
            val customBaseUrl = config.localProviderBaseUrl?.trim()?.trimEnd('/')?.takeIf {
                it.isNotBlank() && localModelPath == null
            }
            return EmbeddedLettaCodeModelSelection(
                modelHandle = if (customBaseUrl != null) {
                    providerModel.normalizedOr(DEFAULT_MODEL_HANDLE)
                } else {
                    localModelHandle.normalizedOr(DEFAULT_MODEL_HANDLE)
                },
                modelPath = localModelPath,
                runtime = config.localModelRuntime.normalizedOr(DEFAULT_MODEL_RUNTIME).lowercase(Locale.US),
                accelerator = config.localModelAccelerator.normalizedOr(DEFAULT_ACCELERATOR).lowercase(Locale.US),
                maxTokens = config.localModelMaxTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS,
                customProviderBaseUrl = customBaseUrl,
                customProviderApiKey = config.localProviderApiKey?.trim()?.takeIf { customBaseUrl != null && it.isNotBlank() },
            )
        }
    }
}

/** letta-code's handle prefix for its built-in `lmstudio` provider (`handlePrefixes: ["lmstudio/"]`). */
const val LETTA_CODE_LMSTUDIO_HANDLE_PREFIX = "lmstudio/"

/**
 * letta-mobile-by4vm.121.1: the model id letta-code 0.34.14 looks up for an `lmstudio/` agent
 * handle. `resolvePiModelFromAgent` → `stripProviderHandlePrefix` removes the leading
 * `lmstudio/` once and keeps the rest verbatim (`google/gemma-3n-E2B-it-litert-lm` keeps its
 * slash); `LocalPiModelsRuntime.resolveTurn` then needs that exact id in the provider's discovered
 * list (`GET <base>/api/v0/models`, else `GET <base>/models`, ids taken verbatim). Every
 * OpenAI-compatible endpoint behind `lc-lmstudio` (the on-device bridge, a model server) must
 * therefore advertise this id, or the turn fails with `Unknown model … for provider "lmstudio"`.
 */
fun lettaCodeLookupId(handle: String): String = handle.removePrefix(LETTA_CODE_LMSTUDIO_HANDLE_PREFIX)

private fun String?.normalizedOr(defaultValue: String): String =
    this?.trim()?.takeIf { it.isNotBlank() } ?: defaultValue

private fun String.toOpenAiModelId(): String =
    trim()
        .removePrefix("local/")
        .removePrefix(LETTA_CODE_LMSTUDIO_HANDLE_PREFIX)
        .removePrefix("llama-cpp/")
        .removePrefix("llama.cpp/")
        .ifBlank { "default" }
