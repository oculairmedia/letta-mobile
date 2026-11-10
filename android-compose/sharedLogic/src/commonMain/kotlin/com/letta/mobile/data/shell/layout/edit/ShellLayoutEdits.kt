package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.data.shell.sidemenu.PanelViews

/**
 * Applies [ShellLayoutOp]s to a [ShellLayoutConfig] under the [ShellLayoutCatalog]'s rules: a lane
 * holds only the views it may hold, a slot appears once (and in only one of main and side), the page
 * never leaves the main lane, Settings never leaves the side menu, and no lane grows past its cap.
 * Every op is checked; when any is invalid nothing is applied and every problem is reported.
 */
object ShellLayoutEdits {
    /** The most ops one edit may carry. */
    const val MAX_OPS = 20

    /** The slots [lane] shows in [config], as the shell draws them. */
    fun slots(config: ShellLayoutConfig, lane: String): List<ModuleSlot> = when (lane) {
        ShellLanes.MAIN -> config.mainLane()
        ShellLanes.SIDE -> config.sideLane()
        ShellLanes.HOME -> config.homeLane()
        ShellLanes.RAIL -> config.railLane()
        ShellLanes.PANEL -> config.panelLane()
        else -> config.lane(lane)
    }

    /** Every catalog lane whose slots differ between [before] and [after], in catalog order. */
    fun changes(before: ShellLayoutConfig, after: ShellLayoutConfig): List<ShellLayoutLaneChange> =
        ShellLayoutCatalog.lanes.mapNotNull { spec ->
            val was = slots(before, spec.lane)
            val now = slots(after, spec.lane)
            if (was == now) null else ShellLayoutLaneChange(spec.lane, was, now)
        }

    /** [ops] applied to [config] in order: the layout they make, or every rule they break. */
    fun apply(config: ShellLayoutConfig, ops: List<ShellLayoutOp>): ShellLayoutEditResult {
        if (ops.isEmpty() || ops.size > MAX_OPS) {
            return reject(listOf(ShellLayoutEditError(null, ShellLayoutEditRule.OP_COUNT, "Send between 1 and $MAX_OPS ops.")))
        }
        val draft = Draft(config)
        val errors = ops.flatMapIndexed { index, op -> draft.apply(op).map { (rule, detail) -> ShellLayoutEditError(index, rule, detail) } }
        val final = draft.finalProblems().map { (rule, detail) -> ShellLayoutEditError(null, rule, detail) }
        return if (errors.isEmpty() && final.isEmpty()) ShellLayoutEditResult.Applied(config, draft.build()) else reject(errors + final)
    }

    private fun reject(errors: List<ShellLayoutEditError>) = ShellLayoutEditResult.Rejected(errors)
}

/** A rule broken and what was wrong. */
private typealias Problem = Pair<ShellLayoutEditRule, String>

/** The layout being edited: each catalog lane's slots, and which lanes the ops wrote. */
private class Draft(private var base: ShellLayoutConfig) {
    private val lanes = mutableMapOf<String, List<ModuleSlot>>()
    private val touched = mutableSetOf<String>()

    private fun slots(lane: String): List<ModuleSlot> = lanes.getOrPut(lane) { ShellLayoutEdits.slots(base, lane) }

    private fun write(lane: String, slots: List<ModuleSlot>) {
        lanes[lane] = slots
        touched += lane
    }

    /** Applies [op] when it is valid; otherwise leaves the draft as it was and returns why not. */
    fun apply(op: ShellLayoutOp): List<Problem> = when (op) {
        is ShellLayoutOp.SetLane -> setLane(op)
        is ShellLayoutOp.Add -> add(op)
        is ShellLayoutOp.Remove -> remove(op)
        is ShellLayoutOp.Move -> move(op)
        ShellLayoutOp.Reset -> reset()
    }

    private fun reset(): List<Problem> {
        base = ShellLayoutConfig.Default
        lanes.clear()
        touched += ShellLayoutCatalog.lanes.map { it.lane }
        return emptyList()
    }

    private fun setLane(op: ShellLayoutOp.SetLane): List<Problem> {
        val spec = ShellLayoutCatalog.lane(op.lane) ?: return listOf(unknownLane(op.lane))
        val problems = op.slots.mapNotNull { slotProblem(spec, it) } +
            op.slots.groupBy { it }.filterValues { it.size > 1 }.keys.map { duplicate(it, op.lane) } +
            op.slots.mapNotNull { elsewhere(op.lane, it) } +
            keepsRequired(op.lane, op.slots)
        if (problems.isEmpty()) write(op.lane, op.slots)
        return problems
    }

    private fun add(op: ShellLayoutOp.Add): List<Problem> {
        val spec = ShellLayoutCatalog.lane(op.lane) ?: return listOf(unknownLane(op.lane))
        val current = slots(op.lane)
        val problem = slotProblem(spec, op.slot)
            ?: duplicate(op.slot, op.lane).takeIf { op.slot in current }
            ?: elsewhere(op.lane, op.slot)
            ?: indexProblem(op.index, current.size)
        if (problem == null) write(op.lane, current.inserted(op.slot, op.index))
        return listOfNotNull(problem)
    }

    private fun remove(op: ShellLayoutOp.Remove): List<Problem> {
        if (ShellLayoutCatalog.lane(op.lane) == null) return listOf(unknownLane(op.lane))
        val current = slots(op.lane)
        val problem = notFound(op.slot, op.lane).takeIf { op.slot !in current } ?: leavingProblem(op.lane, op.slot)
        if (problem == null) write(op.lane, current - op.slot)
        return listOfNotNull(problem)
    }

    private fun move(op: ShellLayoutOp.Move): List<Problem> {
        if (ShellLayoutCatalog.lane(op.lane) == null) return listOf(unknownLane(op.lane))
        val target = ShellLayoutCatalog.lane(op.toLane) ?: return listOf(unknownLane(op.toLane))
        if (op.slot !in slots(op.lane)) return listOf(notFound(op.slot, op.lane))
        val within = op.lane == op.toLane
        val remaining = slots(op.lane) - op.slot
        val destination = if (within) remaining else slots(op.toLane)
        val problem = (if (within) null else leavingProblem(op.lane, op.slot) ?: slotProblem(target, op.slot))
            ?: duplicate(op.slot, op.toLane).takeIf { !within && op.slot in destination }
            ?: indexProblem(op.index, destination.size)
        if (problem == null) {
            write(op.lane, remaining)
            write(op.toLane, destination.inserted(op.slot, op.index))
        }
        return listOfNotNull(problem)
    }

    /** What the edit breaks as a whole, in the lanes it wrote: a lane past its cap, or a protected slot gone. */
    fun finalProblems(): List<Problem> = touched.flatMap { lane ->
        val spec = ShellLayoutCatalog.lane(lane) ?: return@flatMap emptyList()
        val count = slots(lane).size
        val full = ShellLayoutEditRule.LANE_FULL to "Lane ${spec.lane} would hold $count slots; it takes at most ${spec.maxSlots}."
        listOfNotNull(full.takeIf { count > spec.maxSlots }) + keepsRequired(lane, slots(lane))
    }

    /** The edited layout: the base with each lane the ops wrote replaced. */
    fun build(): ShellLayoutConfig = base.copy(lanes = base.lanes + touched.associateWith { slots(it) })

    /** Why [slot] cannot stay in, or go from, the lane it would leave. */
    private fun leavingProblem(lane: String, slot: ModuleSlot): Problem? = when {
        lane == ShellLanes.MAIN && slot.view == ShellViews.PAGE -> pageStays()
        lane == ShellLanes.PANEL && slot.view == PanelViews.SETTINGS -> settingsStays()
        else -> null
    }

    /** A main or side slot that is already in the other of the two. */
    private fun elsewhere(lane: String, slot: ModuleSlot): Problem? {
        val other = when (lane) {
            ShellLanes.MAIN -> ShellLanes.SIDE
            ShellLanes.SIDE -> ShellLanes.MAIN
            else -> return null
        }
        return (ShellLayoutEditRule.DUPLICATE_SLOT to "${slot.describe()} is already in lane $other; move it from there instead.")
            .takeIf { slot in slots(other) }
    }

    private fun keepsRequired(lane: String, slots: List<ModuleSlot>): List<Problem> = listOfNotNull(
        pageStays().takeIf { lane == ShellLanes.MAIN && slots.none { it.view == ShellViews.PAGE } },
        settingsStays().takeIf { lane == ShellLanes.PANEL && slots.none { it.view == PanelViews.SETTINGS } },
    )
}

private fun slotProblem(spec: ShellLayoutLaneSpec, slot: ModuleSlot): Problem? {
    val view = spec.view(slot.view)
        ?: return ShellLayoutEditRule.VIEW_NOT_ALLOWED to
            "Lane ${spec.lane} cannot hold ${slot.view.value}; it takes ${spec.views.joinToString { it.view.value }}."
    val ref = slot.objectRef
    return when {
        view.needsObject && ref.isNullOrBlank() -> ShellLayoutEditRule.OBJECT_REQUIRED to "${slot.view.value} needs an object (its id)."
        !view.needsObject && ref != null -> ShellLayoutEditRule.OBJECT_NOT_ALLOWED to "${slot.view.value} takes no object."
        ref != null && ref.length > ShellLayoutCatalog.MAX_OBJECT_LENGTH ->
            ShellLayoutEditRule.OBJECT_TOO_LONG to "An object is at most ${ShellLayoutCatalog.MAX_OBJECT_LENGTH} characters."
        else -> null
    }
}

private fun indexProblem(index: Int?, size: Int): Problem? =
    (ShellLayoutEditRule.INDEX_OUT_OF_RANGE to "index $index is outside 0..$size.").takeIf { index != null && index !in 0..size }

private fun unknownLane(lane: String): Problem =
    ShellLayoutEditRule.UNKNOWN_LANE to "No lane $lane; lanes are ${ShellLayoutCatalog.lanes.joinToString { it.lane }}."

private fun duplicate(slot: ModuleSlot, lane: String): Problem =
    ShellLayoutEditRule.DUPLICATE_SLOT to "${slot.describe()} is already in lane $lane."

private fun notFound(slot: ModuleSlot, lane: String): Problem =
    ShellLayoutEditRule.SLOT_NOT_FOUND to "${slot.describe()} is not in lane $lane."

private fun pageStays(): Problem =
    ShellLayoutEditRule.PAGE_STAYS_IN_MAIN to "${ShellViews.PAGE.value} must stay in lane ${ShellLanes.MAIN}."

private fun settingsStays(): Problem =
    ShellLayoutEditRule.SETTINGS_STAYS to "${PanelViews.SETTINGS.value} must stay in lane ${ShellLanes.PANEL}."

private fun ModuleSlot.describe(): String = objectRef?.let { "${view.value} ($it)" } ?: view.value

private fun List<ModuleSlot>.inserted(slot: ModuleSlot, index: Int?): List<ModuleSlot> =
    toMutableList().apply { add(index ?: size, slot) }
