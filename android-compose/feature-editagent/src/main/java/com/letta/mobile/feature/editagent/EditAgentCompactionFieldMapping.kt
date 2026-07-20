package com.letta.mobile.feature.editagent

import com.letta.mobile.data.model.Agent

internal fun Agent.compactionFields(): CompactionFields {
    val compactionSettings = compactionSettings
    return CompactionFields(
        summarizationPrompt = compactionSettings?.prompt.orEmpty(),
        compactionClipChars = compactionSettings?.clipChars ?: 50_000,
        slidingWindowPercentage = compactionSettings
            ?.slidingWindowPercentage
            ?.toFloat()
            ?.coerceIn(0f, 1f)
            ?: 0.3f,
        promptAcknowledgement = compactionSettings?.promptAcknowledgement ?: false,
        compactionMode = compactionSettings?.mode ?: "sliding_window",
        compactionModel = compactionSettings?.model.orEmpty(),
        compactionModelSettingsJson = compactionSettings
            ?.modelSettings
            ?.toSettingsJson()
            .orEmpty(),
    )
}
