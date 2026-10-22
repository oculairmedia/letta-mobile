package com.letta.mobile.data.repository.modelcontrol

/**
 * A model row's Thinking switch. [toggledEffort] is the effort a flip of the switch picks: the
 * thinking-off level while [on], else the model's own default level (null: the provider default).
 */
data class ThinkingToggle(val on: Boolean, val toggledEffort: String?)

/**
 * What a picker row's options offer: the [thinking] switch (null where the model cannot turn
 * thinking off), its effort [levels] (wire literals in ladder order, without the thinking-off one)
 * and the effort in force for the row ([current], null: the provider default).
 */
data class ModelEffortMenu(val thinking: ThinkingToggle?, val levels: List<String>, val current: String?) {
    val isEmpty: Boolean get() = thinking == null && levels.isEmpty()
}

/** The upstream `reasoning_effort` that turns thinking off. */
const val THINKING_OFF_EFFORT = "none"

/**
 * The one mapping between thinking and reasoning effort: a model can switch thinking off when it
 * advertises [THINKING_OFF_EFFORT] beside at least one other level; thinking is off while [current]
 * is that level. A model that later exposes a real thinking flag changes only this function.
 */
fun thinkingToggleOf(efforts: List<String>, current: String?, defaultEffort: String?): ThinkingToggle? {
    val off = efforts.firstOrNull(::isThinkingOff) ?: return null
    if (efforts.all(::isThinkingOff)) return null
    val on = current == null || !isThinkingOff(current)
    return ThinkingToggle(on = on, toggledEffort = if (on) off else defaultEffort?.takeUnless(::isThinkingOff))
}

private fun isThinkingOff(effort: String): Boolean = effort.equals(THINKING_OFF_EFFORT, ignoreCase = true)

/**
 * This row's options, with [remembered] (the effort last picked for the model in this session) in
 * force before the catalog's default tier.
 */
fun ModelPickerEntry.effortMenu(remembered: String?): ModelEffortMenu {
    val default = tier?.effort
    val current = remembered ?: default
    return ModelEffortMenu(
        thinking = thinkingToggleOf(efforts, current, default),
        levels = efforts.filterNot(::isThinkingOff),
        current = current,
    )
}
