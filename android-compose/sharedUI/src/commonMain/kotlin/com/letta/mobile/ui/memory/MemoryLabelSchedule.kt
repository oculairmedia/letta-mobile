package com.letta.mobile.ui.memory

/** Decides when moving labels may be culled again; focus, tuning and settling take effect at once. */
internal class MemoryLabelSchedule {
    private var last: MemoryLabelContext? = null
    private var wasMoving = false
    private var framesSinceDecision = 0

    fun due(context: MemoryLabelContext, moving: Boolean): Boolean {
        val changed = changed(context)
        val settling = wasMoving && !moving
        framesSinceDecision++
        val movingDue = moving && framesSinceDecision >= context.tuning.labelCullInterval
        val due = changed || settling || movingDue
        last = context
        wasMoving = moving
        if (due) framesSinceDecision = 0
        return due
    }

    private fun changed(context: MemoryLabelContext): Boolean {
        val previous = last ?: return true
        return focusChanged(previous, context) || previous.zoom != context.zoom || previous.tuning != context.tuning
    }

    private fun focusChanged(previous: MemoryLabelContext, context: MemoryLabelContext): Boolean =
        previous.selectedId != context.selectedId || previous.hoveredId != context.hoveredId
}
