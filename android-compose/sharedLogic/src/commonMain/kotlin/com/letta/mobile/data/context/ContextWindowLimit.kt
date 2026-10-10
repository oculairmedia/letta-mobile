package com.letta.mobile.data.context

import com.letta.mobile.data.context.estimate.ESTIMATE_SOURCE
import com.letta.mobile.data.context.limit.AppliedContextLimit
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalog

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
    if (override != null) return modelCatalogWindowOf(models, override)
    if (agent == null) return null
    return agent.contextWindowLimit.positive()
        ?: agent.llmConfig?.contextWindow.positive()
        ?: modelCatalogWindowOf(models, agent.model)
}

/**
 * letta-mobile-joigh: the catalog window of model [value] (a picker token, handle, id or alias):
 * the most that model can be run at, i.e. the context-limit slider's ceiling.
 *
 * An exact picker token wins. Otherwise every row the value names counts, and rows that share one
 * handle (a 200k and a 1M route of the same model) resolve to the largest window, as letta-code's
 * `/context-limit` does for a limit above the smaller route. Null when no row lists a window.
 */
fun modelCatalogWindowOf(models: List<LlmModel>, value: String?): Int? {
    val wanted = value?.takeIf { it.isNotBlank() } ?: return null
    ModelCatalog.selectedModel(models, wanted)?.contextWindow.positive()?.let { return it }
    return models
        .filter { it.handle == wanted || it.id == wanted || ModelCatalog.valueOf(it) == wanted || wanted in it.selectionAliases }
        .mapNotNull { it.contextWindow.positive() }
        .maxOrNull()
}

/**
 * letta-mobile-joigh: the drawer card's windows for one conversation.
 *
 * [pinned] is a limit this session just applied under the same model: no cache re-reads the record
 * after `/context-limit`, so it is the newest truth and beats even the host record ([recordWith]).
 * [window] is the client's figure: the host's own (the agent record or a per-conversation pick's
 * catalog window, see [contextWindowTokensOf]), else the model's catalog window. [modelMax] is that
 * catalog window, the limit slider's ceiling; callers fill it from the chat's model list, else the
 * sheet catalog ([modelCatalogWindowOf]).
 *
 * The catalog fallback fixes a conversation switched to a model the chat's own model list does not
 * carry (an Iroh host catalog model): it had no window at all, so the meter read "112.8k" with
 * "0.0%" against a model the sheet listed as 1M.
 */
data class FocusedContextWindows(val pinned: Int?, val window: Int?, val modelMax: Int?) {
    /** The window [ContextMeter.of] gets as its fallback. */
    val meterWindow: Int? get() = pinned ?: window

    /** The host record [ContextMeter.of] reads, with a just-applied limit in place of its stale window. */
    fun recordWith(overview: ContextWindowOverview?): ContextWindowOverview? =
        pinned?.let { limit -> overview?.copy(contextWindowSizeMax = limit) } ?: overview

    /** The limit in force: the just-applied one, else the host record's (estimator answers only), else [window]. */
    fun current(overview: ContextWindowOverview?): Int? =
        pinned ?: overview?.takeIf { it.source == ESTIMATE_SOURCE }?.contextWindowSizeMax.positive() ?: window

    companion object {
        fun of(applied: AppliedContextLimit?, modelValue: String?, focusWindow: Int?, modelMax: Int?): FocusedContextWindows =
            FocusedContextWindows(
                pinned = applied?.takeIf { it.modelValue == modelValue }?.tokens.positive(),
                window = focusWindow.positive() ?: modelMax.positive(),
                modelMax = modelMax.positive(),
            )
    }
}

private fun Int?.positive(): Int? = this?.takeIf { it > 0 }
