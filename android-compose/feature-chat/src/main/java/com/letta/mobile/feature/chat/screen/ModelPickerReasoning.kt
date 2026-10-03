package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * letta-mobile-w4q4p: the callback that switches model + reasoning effort
 * together (effort null = provider default). The shared picker
 * (letta-mobile-w4q4p.6.1) reads the efforts each model offers from the host
 * catalog and shows chips for them; this carries what a chip does.
 */
internal data class ModelPickerReasoning(
    val onEffortSelected: (handle: String, effort: String?) -> Unit = { _, _ -> },
)

/** Supplied by the chat scaffold; pickers opened elsewhere ignore effort chips. */
internal val LocalModelPickerReasoning = staticCompositionLocalOf { ModelPickerReasoning() }
