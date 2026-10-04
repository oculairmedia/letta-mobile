package com.letta.mobile.data.context

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LlmModel

/**
 * letta-mobile-r2zo8: the focused conversation's context window, from data a client already
 * holds — no extra call:
 *
 *  1. a per-conversation model switch ([modelOverride]) uses that model's catalog window,
 *     since the agent's own limit describes a different model;
 *  2. otherwise the agent's `context_window_limit`, then its `llm_config.context_window`;
 *  3. otherwise the catalog window of the agent's model.
 *
 * Null when none of them is known: the caller then shows the total without a window rather
 * than inventing one.
 */
fun contextWindowTokensOf(
    agent: Agent?,
    models: List<LlmModel>,
    modelOverride: String? = null,
): Int? {
    val override = modelOverride?.takeIf { it.isNotBlank() }
    if (override != null) return catalogWindow(models, override)
    if (agent == null) return null
    return agent.contextWindowLimit.positive()
        ?: agent.llmConfig?.contextWindow.positive()
        ?: agent.model?.let { catalogWindow(models, it) }
}

private fun catalogWindow(models: List<LlmModel>, handle: String): Int? =
    models.firstOrNull { it.handle == handle || it.id == handle || handle in it.selectionAliases }
        ?.contextWindow
        .positive()

private fun Int?.positive(): Int? = this?.takeIf { it > 0 }
