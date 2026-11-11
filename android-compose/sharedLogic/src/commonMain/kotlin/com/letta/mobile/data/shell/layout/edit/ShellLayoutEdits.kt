package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.chat.layout.ChatViews
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.data.shell.sidemenu.PanelViews
import com.letta.mobile.data.shell.sidemenu.RailViews

/**
 * Applies [ShellLayoutOp]s to a [ShellLayoutConfig] under the [ShellLayoutCatalog]'s rules: a lane
 * holds only the views it may hold, a slot appears once (and in only one of main and side), the page
 * never leaves the main lane, Settings never leaves the side menu, the rail keeps its Home button, and
 * no lane grows past its cap.
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
        ShellLanes.CHAT -> config.chatLane()
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
    private val lanes = mutableMapOf<ShellLayoutLaneSpec, List<ModuleSlot>>()
    private val touched = mutableSetOf<ShellLayoutLaneSpec>()

    private fun slots(spec: ShellLayoutLaneSpec): List<ModuleSlot> = lanes.getOrPut(spec) { ShellLayoutEdits.slots(base, spec.lane) }

    private fun write(spec: ShellLayoutLaneSpec, slots: List<ModuleSlot>) {
        lanes[spec] = slots
        touched += spec
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
        touched += ShellLayoutCatalog.lanes
        return emptyList()
    }

    private fun setLane(op: ShellLayoutOp.SetLane): List<Problem> {
        val spec = ShellLayoutCatalog.lane(op.lane) ?: return listOf(unknownLane(op.lane))
        val problems = op.slots.mapNotNull { slotProblem(spec, it) } +
            op.slots.groupBy { it }.filterValues { it.size > 1 }.keys.map { duplicate(it, spec) } +
            op.slots.mapNotNull { elsewhere(spec, it) } +
            keepsRequired(spec, op.slots)
        if (problems.isEmpty()) write(spec, op.slots)
        return problems
    }

    private fun add(op: ShellLayoutOp.Add): List<Problem> {
        val spec = ShellLayoutCatalog.lane(op.lane) ?: return listOf(unknownLane(op.lane))
        val current = slots(spec)
        val problem = slotProblem(spec, op.slot)
            ?: duplicate(op.slot, spec).takeIf { op.slot in current }
            ?: elsewhere(spec, op.slot)
            ?: indexProblem(op.index, current)
        return applied(problem) { write(spec, current.inserted(op.slot, op.index)) }
    }

    private fun remove(op: ShellLayoutOp.Remove): List<Problem> {
        val spec = ShellLayoutCatalog.lane(op.lane) ?: return listOf(unknownLane(op.lane))
        val current = slots(spec)
        val problem = notFound(op.slot, spec).takeIf { op.slot !in current } ?: leavingProblem(spec, op.slot)
        return applied(problem) { write(spec, current - op.slot) }
    }

    private fun move(op: ShellLayoutOp.Move): List<Problem> {
        val from = ShellLayoutCatalog.lane(op.lane) ?: return listOf(unknownLane(op.lane))
        val target = ShellLayoutCatalog.lane(op.toLane) ?: return listOf(unknownLane(op.toLane))
        if (op.slot !in slots(from)) return listOf(notFound(op.slot, from))
        return if (from == target) moveWithin(from, op) else moveAcross(from, target, op)
    }

    /** [op] moving its slot to another place in [lane], the lane it is already in. */
    private fun moveWithin(lane: ShellLayoutLaneSpec, op: ShellLayoutOp.Move): List<Problem> {
        val remaining = slots(lane) - op.slot
        return applied(indexProblem(op.index, remaining)) { write(lane, remaining.inserted(op.slot, op.index)) }
    }

    /** [op] moving its slot out of [from] into [target], another lane. */
    private fun moveAcross(from: ShellLayoutLaneSpec, target: ShellLayoutLaneSpec, op: ShellLayoutOp.Move): List<Problem> {
        val destination = slots(target)
        val problem = leavingProblem(from, op.slot)
            ?: slotProblem(target, op.slot)
            ?: duplicate(op.slot, target).takeIf { op.slot in destination }
            ?: indexProblem(op.index, destination)
        return applied(problem) {
            write(from, slots(from) - op.slot)
            write(target, destination.inserted(op.slot, op.index))
        }
    }

    /** What the edit breaks as a whole, in the lanes it wrote: a lane past its cap, or a protected slot gone. */
    fun finalProblems(): List<Problem> = touched.flatMap { spec ->
        val count = slots(spec).size
        val full = ShellLayoutEditRule.LANE_FULL to "Lane ${spec.lane} would hold $count slots; it takes at most ${spec.maxSlots}."
        listOfNotNull(full.takeIf { count > spec.maxSlots }) + keepsRequired(spec, slots(spec))
    }

    /** The edited layout: the base with each lane the ops wrote replaced. */
    fun build(): ShellLayoutConfig = base.copy(lanes = base.lanes + touched.associate { it.lane to slots(it) })

    /** A main or side slot that is already in the other of the two. */
    private fun elsewhere(spec: ShellLayoutLaneSpec, slot: ModuleSlot): Problem? {
        val other = otherOf(spec) ?: return null
        return (ShellLayoutEditRule.DUPLICATE_SLOT to "${slot.describe()} is already in lane ${other.lane}; move it from there instead.")
            .takeIf { slot in slots(other) }
    }
}

/** [problem] as an op's result, making the op's [change] only when there is none. */
private inline fun applied(problem: Problem?, change: () -> Unit): List<Problem> {
    if (problem == null) change()
    return listOfNotNull(problem)
}

/** The other of the main and side lanes, or null for any other lane. */
private fun otherOf(spec: ShellLayoutLaneSpec): ShellLayoutLaneSpec? = when (spec.lane) {
    ShellLanes.MAIN -> ShellLayoutCatalog.lane(ShellLanes.SIDE)
    ShellLanes.SIDE -> ShellLayoutCatalog.lane(ShellLanes.MAIN)
    else -> null
}

/** A view that must stay in [lane]: it may move within the lane but never leave it, breaking [rule] if it would. */
private class Protection(private val lane: String, val view: RuntimeViewId, rule: ShellLayoutEditRule) {
    /** The problem reported when [view] would leave [lane]. */
    val problem: Problem = rule to "${view.value} must stay in lane $lane."

    /** Whether this protection holds in the lane [spec]. */
    fun guards(spec: ShellLayoutLaneSpec): Boolean = spec.lane == lane
}

/** The protected slots, in the order their problems are reported. */
private val protections = listOf(
    Protection(ShellLanes.MAIN, ShellViews.PAGE, ShellLayoutEditRule.PAGE_STAYS_IN_MAIN),
    Protection(ShellLanes.PANEL, PanelViews.SETTINGS, ShellLayoutEditRule.SETTINGS_STAYS),
    Protection(ShellLanes.RAIL, RailViews.HOME, ShellLayoutEditRule.HOME_STAYS),
    Protection(ShellLanes.CHAT, ChatViews.TRANSCRIPT, ShellLayoutEditRule.CHAT_READ_SEND_STAYS),
    Protection(ShellLanes.CHAT, ChatViews.COMPOSER, ShellLayoutEditRule.CHAT_READ_SEND_STAYS),
)

/** Why [slot] cannot stay in, or go from, the lane [spec] it would leave. */
private fun leavingProblem(spec: ShellLayoutLaneSpec, slot: ModuleSlot): Problem? =
    protections.firstOrNull { it.guards(spec) && it.view == slot.view }?.problem

/** Each protected view of the lane [spec] that [slots] would leave out. */
private fun keepsRequired(spec: ShellLayoutLaneSpec, slots: List<ModuleSlot>): List<Problem> =
    protections.filter { protection -> protection.guards(spec) && slots.none { it.view == protection.view } }.map { it.problem }

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

/** An [index] that is not a place in [slots]: 0 is first, and their size is just past the last. */
private fun indexProblem(index: Int?, slots: List<ModuleSlot>): Problem? {
    val size = slots.size
    return (ShellLayoutEditRule.INDEX_OUT_OF_RANGE to "index $index is outside 0..$size.").takeIf { index != null && index !in 0..size }
}

private fun unknownLane(lane: String): Problem =
    ShellLayoutEditRule.UNKNOWN_LANE to "No lane $lane; lanes are ${ShellLayoutCatalog.lanes.joinToString { it.lane }}."

private fun duplicate(slot: ModuleSlot, spec: ShellLayoutLaneSpec): Problem =
    ShellLayoutEditRule.DUPLICATE_SLOT to "${slot.describe()} is already in lane ${spec.lane}."

private fun notFound(slot: ModuleSlot, spec: ShellLayoutLaneSpec): Problem =
    ShellLayoutEditRule.SLOT_NOT_FOUND to "${slot.describe()} is not in lane ${spec.lane}."

private fun ModuleSlot.describe(): String = objectRef?.let { "${view.value} ($it)" } ?: view.value

private fun List<ModuleSlot>.inserted(slot: ModuleSlot, index: Int?): List<ModuleSlot> =
    toMutableList().apply { add(index ?: size, slot) }
