package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.LettaConfig
import java.util.Locale

data class EmbeddedLettaCodeModelSelection(
    val modelHandle: String,
    val modelPath: String?,
    val runtime: String,
    val accelerator: String,
    val maxTokens: Int,
) {
    val openAiModelId: String
        get() = modelHandle.substringAfterLast('/').takeIf { it.isNotBlank() } ?: DEFAULT_OPENAI_MODEL_ID

    val lettaCodeModelHandle: String
        get() = "lmstudio/$openAiModelId"

    val startKey: String
        get() = listOf(modelHandle, modelPath.orEmpty(), runtime, accelerator, maxTokens.toString()).joinToString("|")

    companion object {
        const val DEFAULT_MODEL_HANDLE = "local/on-device"
        const val DEFAULT_MODEL_RUNTIME = "litert-lm"
        const val DEFAULT_ACCELERATOR = "gpu"
        const val DEFAULT_MAX_TOKENS = 4096
        private const val DEFAULT_OPENAI_MODEL_ID = "on-device"

        fun from(config: LettaConfig): EmbeddedLettaCodeModelSelection = EmbeddedLettaCodeModelSelection(
            modelHandle = config.localModelHandle.normalizedOr(DEFAULT_MODEL_HANDLE),
            modelPath = config.localModelPath?.trim()?.takeIf { it.isNotBlank() },
            runtime = config.localModelRuntime.normalizedOr(DEFAULT_MODEL_RUNTIME).lowercase(Locale.US),
            accelerator = config.localModelAccelerator.normalizedOr(DEFAULT_ACCELERATOR).lowercase(Locale.US),
            maxTokens = config.localModelMaxTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS,
        )
    }
}

private fun String?.normalizedOr(defaultValue: String): String =
    this?.trim()?.takeIf { it.isNotBlank() } ?: defaultValue
