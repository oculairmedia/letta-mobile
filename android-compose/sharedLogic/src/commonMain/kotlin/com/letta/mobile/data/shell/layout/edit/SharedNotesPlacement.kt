package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews

/**
 * The person's "Shared notes" toggle as a layout change: showing puts the document view first in
 * the side lane, beside the page (the main agent's chat stays where it is); hiding takes it out of
 * whichever lane holds it. The change goes through [ShellLayoutEdits], so it obeys the same rules as
 * an agent's edit and is undoable like one.
 */
object SharedNotesPlacement {
    /** The document view's slot; it names no object. */
    val SLOT = ModuleSlot(ShellViews.DOCUMENT)

    /** True when [config] already shows the document view in the main or side lane. */
    fun shown(config: ShellLayoutConfig): Boolean = lanesHolding(config).isNotEmpty()

    /** True when [config] holds the document view in its side lane (a compact window draws it over the page). */
    fun inSideLane(config: ShellLayoutConfig): Boolean = SLOT in config.sideLane()

    /** [config] with the document view shown or hidden, or why it cannot be (a full side lane). */
    fun toggled(config: ShellLayoutConfig): ShellLayoutEditResult {
        val holding = lanesHolding(config)
        val ops = if (holding.isEmpty()) {
            listOf(ShellLayoutOp.Add(ShellLanes.SIDE, SLOT, index = 0))
        } else {
            holding.map { lane -> ShellLayoutOp.Remove(lane, SLOT) }
        }
        return ShellLayoutEdits.apply(config, ops)
    }

    private fun lanesHolding(config: ShellLayoutConfig): List<String> = buildList {
        if (SLOT in config.mainLane()) add(ShellLanes.MAIN)
        if (SLOT in config.sideLane()) add(ShellLanes.SIDE)
    }
}
