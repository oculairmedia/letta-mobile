package com.letta.mobile.ui.memory

import androidx.compose.ui.geometry.Rect
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.graph.MemoryGraphTuning

/** A node and its slot in the static label priority order. */
internal class MemoryLabelEntry(val index: Int, val node: MemoryGraphNode)

/** Holds each candidate's visibility until enough consecutive overlap or clear decisions agree. */
internal class MemoryLabelHysteresis(nodes: List<MemoryGraphNode>, private val overlaps: (Rect) -> Boolean) {
    private val wasShown = BooleanArray(nodes.size)
    private val wasCandidate = BooleanArray(nodes.size)
    private val overlapStreak = IntArray(nodes.size)
    private val clearStreak = IntArray(nodes.size)

    fun keeps(entry: MemoryLabelEntry, chip: Rect, tuning: MemoryGraphTuning): Boolean {
        val index = entry.index
        val margin = tuning.labelHysteresisMargin
        val count = tuning.labelHysteresisCount.coerceAtLeast(1f)
        if (!wasCandidate[index]) return !overlaps(chip)
        if (wasShown[index]) {
            overlapStreak[index] = if (overlaps(chip.deflate(margin))) overlapStreak[index] + 1 else 0
            return overlapStreak[index] < count
        }
        clearStreak[index] = if (overlaps(chip.inflate(margin))) 0 else clearStreak[index] + 1
        return clearStreak[index] >= count
    }

    fun record(entry: MemoryLabelEntry, chip: Rect?, keep: Boolean): Boolean {
        val index = entry.index
        wasCandidate[index] = true
        val showing = keep && chip != null
        if (showing != wasShown[index]) resetStreaks(entry)
        wasShown[index] = showing
        return showing
    }

    fun forget(entry: MemoryLabelEntry) {
        wasCandidate[entry.index] = false
        wasShown[entry.index] = false
        resetStreaks(entry)
    }

    private fun resetStreaks(entry: MemoryLabelEntry) {
        overlapStreak[entry.index] = 0
        clearStreak[entry.index] = 0
    }
}
